package com.codecompass.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.graph.DependencyGraphBuilder;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.service.AchievementService;
import com.codecompass.service.AnswerResponse;
import com.codecompass.service.AnswerService;
import com.codecompass.service.AnalysisOrchestrator;
import com.codecompass.service.AnalysisTaskSnapshot;
import com.codecompass.service.AnalysisTaskStore;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.LlmConfig;
import com.codecompass.service.LlmConfigService;
import com.codecompass.service.LlmException;
import com.codecompass.service.QaHistoryRecorder;
import com.codecompass.service.RateLimitExceededException;
import com.codecompass.web.dto.AnalysisTaskView;
import com.codecompass.web.dto.AskRequest;
import com.codecompass.web.dto.CreateRepoRequest;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.GraphResponse;
import com.codecompass.web.dto.NotReadyResponse;
import com.codecompass.web.dto.SourceView;

import jakarta.servlet.http.HttpServletRequest;

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

    private static final Logger log = LoggerFactory.getLogger(RepoController.class);

    private final GitRepositoryCloner cloner;
    private final AnalysisOrchestrator orchestrator;
    private final AnalysisTaskStore store;
    private final DependencyGraphBuilder graphBuilder;
    private final AnswerService answerService;
    private final AchievementService achievementService;
    private final QaHistoryRecorder qaHistoryRecorder;
    private final LlmConfigService llmConfigService;

    public RepoController(GitRepositoryCloner cloner,
                          AnalysisOrchestrator orchestrator,
                          AnalysisTaskStore store,
                          DependencyGraphBuilder graphBuilder,
                          AnswerService answerService,
                          AchievementService achievementService,
                          QaHistoryRecorder qaHistoryRecorder,
                          LlmConfigService llmConfigService) {
        this.cloner = cloner;
        this.orchestrator = orchestrator;
        this.store = store;
        this.graphBuilder = graphBuilder;
        this.answerService = answerService;
        this.achievementService = achievementService;
        this.qaHistoryRecorder = qaHistoryRecorder;
        this.llmConfigService = llmConfigService;
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
                .<ResponseEntity<?>>map(snapshot -> {
                    // T19 触发点：客户端首次观察到 done = 「分析成功后」（异步管线不带身份，
                    // 由轮询方身份记录；refId=taskId 幂等）。记录失败不打断状态查询。
                    if (AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
                        String clientId = ClientIdentityHolder.get();
                        if (clientId != null) {
                            try {
                                achievementService.record(clientId, "analyze", snapshot.url(), taskId);
                            } catch (RuntimeException e) {
                                log.warn("成就记录失败（analyze）：{}", e.getMessage());
                            }
                        }
                    }
                    return ResponseEntity.ok(AnalysisTaskView.from(snapshot));
                })
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
     * T14：单个类的源码行（T7 内存快照的切片）。只给选中类的范围，不是仓库转储。
     */
    @GetMapping("/{taskId}/source")
    public ResponseEntity<?> source(@PathVariable String taskId, @RequestParam String unit) {
        AnalysisTaskSnapshot snapshot = store.find(taskId).orElse(null);
        if (snapshot == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("任务不存在"));
        }
        if (!AnalysisTaskSnapshot.STATUS_DONE.equals(snapshot.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new NotReadyResponse(
                    taskId, snapshot.status(), "分析尚未完成，当前状态：" + snapshot.status()));
        }
        CodeUnitInfo codeUnit = snapshot.outcome().result().codeUnits().stream()
                .filter(u -> u.id().equals(unit))
                .findFirst()
                .orElse(null);
        if (codeUnit == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("单元不存在：" + unit));
        }
        List<String> all = snapshot.outcome().sourceLines().get(codeUnit.filePath());
        List<String> lines = new ArrayList<>();
        if (all != null) {
            int from = codeUnit.startLine() - 1;
            int to = Math.min(codeUnit.endLine(), all.size());
            if (from >= 0 && from < to) {
                lines.addAll(all.subList(from, to));
            }
        }
        return ResponseEntity.ok(new SourceView(codeUnit.filePath(), codeUnit.language(),
                codeUnit.startLine(), codeUnit.endLine(), List.copyOf(lines)));
    }

    /**
     * T10 问答。答案只能基于 T9 的检索片段；LLM 报出的引用逐条与片段比对（AnswerService 内）。
     *
     * <p>状态语义：未知 404；未完成 409（failed 时携带 errorMessage）；
     * 超限 429（T11 每日 token 上限）；LLM 通道故障 502（answer 根本不存在，不降级 200）。
     * T14：带 {@code anchorStartLine} 时按选中标识符的行锚点提问（不走缓存）。
     */
    @PostMapping("/{taskId}/ask")
    public ResponseEntity<?> ask(@PathVariable String taskId, @RequestBody AskRequest request,
                                 HttpServletRequest servletRequest) {
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
            String clientKey = resolveClientKey(servletRequest);
            LlmConfig requestConfig = resolveRequestLlmConfig(servletRequest);
            AnswerResponse answer;
            if (request.anchorStartLine() != null) {
                answer = answerService.ask(snapshot, question, unitId,
                        request.anchorStartLine(), request.anchorEndLine(), clientKey, requestConfig);
            } else {
                answer = answerService.ask(snapshot, question, unitId, clientKey, requestConfig);
            }
            // T19 触发点：提问成功后计数（TEN_QUESTIONS）。记录失败不打断问答。
            String clientId = ClientIdentityHolder.get();
            if (clientId != null) {
                try {
                    achievementService.record(clientId, "ask", snapshot.url(), null);
                } catch (RuntimeException e) {
                    log.warn("成就记录失败（ask）：{}", e.getMessage());
                }
            }
            // T21：问答历史旁路记录（F6 分享快照「前 10 条问答」的数据来源）
            qaHistoryRecorder.record(ClientIdentityHolder.get(), snapshot.url(),
                    snapshot.outcome().commitSha(), question, answer);
            return ResponseEntity.ok(answer);
        } catch (RateLimitExceededException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse(e.getMessage()));
        } catch (LlmException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * 匿名身份解析：{@code X-Client-Id}（匿名 session）→ {@code X-Forwarded-For} 首跳 →
     * {@code remoteAddr}。只读 remoteAddr 的话，dev 经 Vite 代理、生产经反代，
     * 所有请求都来自 127.0.0.1，限流会变成全站共享一个额度。
     */
    static String resolveClientKey(HttpServletRequest request) {
        String clientId = request.getHeader("X-Client-Id");
        if (clientId != null && !clientId.isBlank()) {
            return clientId.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 从请求头读取用户自填的 LLM 配置（{@code X-LLM-Api-Key} / {@code X-LLM-Base-Url} /
     * {@code X-LLM-Model}）。三个头都没有 key 时返回 {@code null}（回落服务端默认）；
     * base-url / model 缺省时用服务端默认补齐。
     *
     * <p><b>安全</b>：key 只在此构造临时 {@link LlmConfig}，用完即弃，绝不缓存、绝不落库、
     * 绝不记日志。
     */
    private LlmConfig resolveRequestLlmConfig(HttpServletRequest request) {
        String apiKey = request.getHeader("X-LLM-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        LlmConfig serverDefault = llmConfigService.getDefault();
        String baseUrl = firstNonBlank(request.getHeader("X-LLM-Base-Url"), serverDefault.baseUrl());
        String model = firstNonBlank(request.getHeader("X-LLM-Model"), serverDefault.model());
        return new LlmConfig("request", "custom", baseUrl, apiKey.trim(), model, false);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
