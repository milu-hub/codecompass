package com.codecompass.web;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.persistence.QuizEntity;
import com.codecompass.persistence.QuizRepository;
import com.codecompass.service.AchievementService;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.LlmException;
import com.codecompass.service.Quiz;
import com.codecompass.service.QuizService;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.NotReadyResponse;
import com.codecompass.web.dto.QuizGradeView;
import com.codecompass.web.dto.QuizSubmitRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * F4 自动测验 REST。POST 生成（存 quizzes）；submit 从库读题判分。
 */
@RestController
public class QuizController {

    private static final Logger log = LoggerFactory.getLogger(QuizController.class);

    private final AnalysisTaskStore store;
    private final QuizService service;
    private final QuizRepository repository;
    private final AchievementService achievementService;
    private final JsonMapper jsonMapper;

    public QuizController(AnalysisTaskStore store, QuizService service,
                          QuizRepository repository, AchievementService achievementService,
                          JsonMapper jsonMapper) {
        this.store = store;
        this.service = service;
        this.repository = repository;
        this.achievementService = achievementService;
        this.jsonMapper = jsonMapper;
    }

    public record GenerateRequest(List<String> codeUnitIds) {
    }

    @PostMapping("/api/repos/{taskId}/quiz")
    public ResponseEntity<?> generate(@PathVariable String taskId, @RequestBody GenerateRequest request) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        }
        try {
            Quiz quiz = service.generate(snapshot.outcome().result(), snapshot.outcome().sourceLines(),
                    snapshot.url(), snapshot.outcome().commitSha(),
                    request == null ? List.of() : request.codeUnitIds());
            repository.save(new QuizEntity(quiz.id(), quiz.repoUrl(), quiz.commitSha(),
                    write(quiz), Instant.now()));
            return ResponseEntity.ok(quiz);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (LlmException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/api/quizzes/{quizId}/submit")
    public ResponseEntity<?> submit(@PathVariable String quizId, @RequestBody QuizSubmitRequest request) {
        QuizEntity entity = repository.findById(quizId).orElse(null);
        if (entity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("测验不存在"));
        }
        Quiz quiz = parse(entity.getQuizJson());
        List<QuizService.AnswerSubmission> answers = request == null || request.answers() == null
                ? List.of()
                : request.answers().stream()
                        .map(answer -> new QuizService.AnswerSubmission(answer.questionId(), answer.answerIndex()))
                        .toList();
        QuizService.GradeResult grade = service.grade(quiz, answers);
        // T19 触发点：QUIZ_MASTER（100% 正确率；refId=quizId 幂等）。记录失败不打断判分。
        String clientId = ClientIdentityHolder.get();
        if (clientId != null && grade.total() > 0 && grade.accuracy() == 1.0) {
            try {
                achievementService.record(clientId, "quiz_perfect", entity.getRepoUrl(), quizId);
            } catch (RuntimeException e) {
                log.warn("成就记录失败（quiz_perfect）：{}", e.getMessage());
            }
        }
        return ResponseEntity.ok(new QuizGradeView(grade.correct(), grade.total(), grade.accuracy()));
    }

    private String write(Quiz quiz) {
        try {
            return jsonMapper.writeValueAsString(quiz);
        } catch (Exception e) {
            throw new IllegalStateException("测验序列化失败", e);
        }
    }

    private Quiz parse(String json) {
        try {
            return jsonMapper.readValue(json, Quiz.class);
        } catch (Exception e) {
            throw new IllegalStateException("测验反序列化失败", e);
        }
    }
}
