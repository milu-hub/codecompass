package com.codecompass.web;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.persistence.LearningPathEntity;
import com.codecompass.persistence.LearningPathRepository;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.LearningPath;
import com.codecompass.service.LearningPathService;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.NotReadyResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * F2 学习路线 REST。
 *
 * <p>POST 生成（已生成则直接返回持久化结果，不重调 LLM）；GET 获取（未生成 404）。
 * 状态语义沿用 T7：未知任务 404、未完成 409。
 */
@RestController
@RequestMapping("/api/repos/{taskId}")
public class LearningPathController {

    private final AnalysisTaskStore store;
    private final LearningPathService service;
    private final LearningPathRepository repository;
    private final JsonMapper jsonMapper;

    public LearningPathController(AnalysisTaskStore store, LearningPathService service,
                                  LearningPathRepository repository, JsonMapper jsonMapper) {
        this.store = store;
        this.service = service;
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/learning-path")
    public ResponseEntity<?> generate(@PathVariable String taskId) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        }
        String repoUrl = snapshot.url();
        String commitSha = snapshot.outcome().commitSha();

        LearningPathEntity existing = repository.findByRepoUrlAndCommitSha(repoUrl, commitSha).orElse(null);
        if (existing != null) {
            return ResponseEntity.ok(parse(existing.getPathJson()));
        }

        LearningPath path = service.generate(snapshot.outcome().result(),
                snapshot.outcome().roles(), repoUrl, commitSha);
        try {
            repository.saveAndFlush(new LearningPathEntity(repoUrl, commitSha,
                    write(path), Instant.now()));
        } catch (DataIntegrityViolationException race) {
            // 并发下唯一约束兜底：别人已生成，读出来返回
            return repository.findByRepoUrlAndCommitSha(repoUrl, commitSha)
                    .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(parse(entity.getPathJson())))
                    .orElseGet(() -> ResponseEntity.internalServerError()
                            .body(new ErrorResponse("学习路线生成冲突，请重试")));
        }
        return ResponseEntity.ok(path);
    }

    @GetMapping("/learning-path")
    public ResponseEntity<?> get(@PathVariable String taskId) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        }
        return repository.findByRepoUrlAndCommitSha(snapshot.url(), snapshot.outcome().commitSha())
                .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(parse(entity.getPathJson())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("尚未生成学习路线，请先 POST")));
    }

    private String write(LearningPath path) {
        try {
            return jsonMapper.writeValueAsString(path);
        } catch (Exception e) {
            throw new IllegalStateException("学习路线序列化失败", e);
        }
    }

    private LearningPath parse(String json) {
        try {
            return jsonMapper.readValue(json, LearningPath.class);
        } catch (Exception e) {
            throw new IllegalStateException("学习路线反序列化失败", e);
        }
    }
}
