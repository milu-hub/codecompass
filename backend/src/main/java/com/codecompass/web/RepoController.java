package com.codecompass.web;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.graph.DependencyGraph;
import com.codecompass.graph.DependencyGraphBuilder;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.service.AnswerService;
import com.codecompass.service.AnalysisOrchestrator;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.LlmException;
import com.codecompass.web.dto.AnalysisTaskView;
import com.codecompass.web.dto.AskRequest;
import com.codecompass.web.dto.CreateRepoRequest;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.GraphResponse;
import com.codecompass.web.dto.NotReadyResponse;

/**
 * T7：图数据 REST API。T10 加问答端点。
 *
 * <p>POST 只做 URL 校验 + 建任务，**不等分析** —— 管线在后台池执行，克隆最长 60 秒，
 * 同步执行会耗尽 Tomcat 线程。
 *
 * <p>无登录鉴权（MVP）；响应结构固定（详见各 DTO 的字段说明）。
 */
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final GitRepositoryCloner cloner;
    private final AnalysisOrchestrator orchestrator;
    private final AnalysisTaskStore store;
    private final DependencyGraphBuilder graphBuilder;
    private final AnswerService answerService;

    public RepoController(GitRepositoryCloner cloner,
                          AnalysisOrchestrator orchestrator,
                          AnalysisTaskStore store,
                          DependencyGraphBuilder graphBuilder,
                          AnswerService answerService) {
        this.cloner = cloner;
        this.orchestrator = orchestrator;
        this.store = store;
        this.graphBuilder = graphBuilder;
        this.answerService = answerService;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody CreateRepoRequest request) {
        String url = request == null ? null : request.url();
        Optional<String> urlError = cloner.validateRepositoryUrl(url);
        if (urlError.isPresent()) {
            // 非法 URL 立刻 400，而不是建一个注定失败的任务
            return ResponseEntity.badRequest().body(new ErrorResponse(urlError.get()));
        }
        String taskId = orchestrator.submit(url);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AnalysisTaskView.from(store.find(taskId).orElseThrow()));
    }

    @GetMapping("/{taskId}/status")
    public ResponseEntity<?> status(@PathVariable String taskId) {
        return store.find(taskId)
                .<ResponseEntity<?>>map(snapshot -> ResponseEntity.ok(AnalysisTaskView.from(snapshot)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("任务不存在")));
    }

    @GetMapping("/{taskId}/graph")
    public ResponseEntity<?> graph(@PathVariable String taskId,
                                   @RequestParam(required = false) String unit,
                                   @RequestParam(required = false, defaultValue = "1") int depth) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        return switch (snapshot.status()) {
            case AnalysisTaskSnapshot.STATUS_DONE -> {
                if (unit == null || unit.isBlank()) {
                    yield ResponseEntity.ok(GraphResponse.from(snapshot));
                }
                // 点击某个类看它的依赖图：图语义留在后端（T6 的 neighborhoodOf 就是为此建的）
                DependencyGraph neighborhood = graphBuilder.neighborhoodOf(
                        snapshot.outcome().graph(), unit.trim(), Math.max(1, depth));
                yield ResponseEntity.ok(GraphResponse.scoped(snapshot, neighborhood));
            }
            // 任务存在且结局已定：200 携带 failed 与 errorMessage，前端一次拿到全部信息
            case AnalysisTaskSnapshot.STATUS_FAILED ->
                    ResponseEntity.ok(GraphResponse.failed(snapshot));
            default -> ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        };
    }

    /**
     * T10 问答。答案只能基于 T9 的检索片段；LLM 报出的引用逐条与片段比对（AnswerService 内）。
     *
     * <p>状态语义：未知 404；未完成 409（failed 时携带 errorMessage）；
     * LLM 通道故障 502（answer 根本不存在，不降级 200）。
     */
    @PostMapping("/{taskId}/ask")
    public ResponseEntity<?> ask(@PathVariable String taskId, @RequestBody AskRequest request) {
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("问题不能为空"));
        }
        String unitId = request.unitId() == null || request.unitId().isBlank()
                ? null : request.unitId().trim();

        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            String message = AnalysisTaskSnapshot.STATUS_FAILED.equals(snapshot.status())
                    ? "分析失败：" + snapshot.errorMessage()
                    : "分析尚未完成，当前状态：" + snapshot.status();
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new NotReadyResponse(taskId, snapshot.status(), message));
        }
        try {
            return ResponseEntity.ok(answerService.ask(snapshot, question, unitId));
        } catch (LlmException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }
}
