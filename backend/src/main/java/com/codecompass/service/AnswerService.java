package com.codecompass.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.retrieve.CodeRetriever;
import com.codecompass.retrieve.RetrievedSnippet;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T10 问答编排 + T11 缓存与限流：缓存 → 检索（T9）→ 限流 → 拼提示词 → LLM →
 * 解析 → 引用逐条校验 → 不匹配丢弃重试。
 *
 * <p><b>行号只来自检索层</b>：提示词里每行前缀真实行号，LLM 报出的引用逐条与检索片段
 * 做严格包含比对，不匹配的丢弃、带反馈重试（预算 {@code maxAttempts} 封顶）。
 * 传输类错误不进重试循环，直接以 {@link LlmException} 向上（控制器 → 502）。
 *
 * <p><b>T11 顺序是正确性的一部分</b>：缓存命中排在限流之前 —— 命中零 LLM 消耗，
 * 不该烧配额；限流排在 LLM 之前 —— 超限绝不触达 LLM。commitSha 缺失时不查不写缓存
 * （宁可 miss 不可错命中）。
 */
public class AnswerService {

    private static final Logger log = LoggerFactory.getLogger(AnswerService.class);

    static final String NO_SNIPPETS_ANSWER =
            "未检索到相关代码，无法回答。请先在类列表里点击相关类，再针对它提问。";

    private static final String SYSTEM_PROMPT = """
            你是代码讲解助手。规则：
            1. 只依据用户提供的代码片段回答，不得编造片段之外的代码；
            2. 引用行号必须来自片段中标注的真实行号（每行开头冒号前的数字），禁止猜测行号；
            3. 只输出一个 JSON 对象，形如 {"answer": "...", "references": [{"file": "...", "language": "...", "startLine": 1, "endLine": 2}]}；
            4. answer 用中文回答；不要输出 JSON 之外的任何内容。
            """;

    private final CodeRetriever retriever;
    private final LlmClientFactory llmClientFactory;
    private final LlmConfig serverDefaultConfig;
    private final LlmProperties properties;
    private final JsonMapper lenientMapper;
    private final CacheService cacheService;
    private final RateLimiter rateLimiter;

    /**
     * 兼容构造：固定单例客户端（学习路线/测验等不按请求配 key 的场景，以及既有测试）。
     * 内部把固定客户端包成「忽略 config」的工厂，回落逻辑退化为恒用该客户端。
     */
    public AnswerService(CodeRetriever retriever, LlmClient llmClient,
                         LlmProperties properties, JsonMapper jsonMapper,
                         CacheService cacheService, RateLimiter rateLimiter) {
        this(retriever, config -> llmClient,
                new LlmConfig("default", "openai", "", "", "gpt-4o-mini", true),
                properties, jsonMapper, cacheService, rateLimiter);
    }

    /** 主构造：按请求解析配置 —— 带请求头用临时客户端，否则回落服务端默认。 */
    public AnswerService(CodeRetriever retriever, LlmClientFactory llmClientFactory,
                         LlmConfig serverDefaultConfig, LlmProperties properties, JsonMapper jsonMapper,
                         CacheService cacheService, RateLimiter rateLimiter) {
        this.retriever = retriever;
        this.llmClientFactory = llmClientFactory;
        this.serverDefaultConfig = serverDefaultConfig;
        this.properties = properties;
        this.cacheService = cacheService;
        this.rateLimiter = rateLimiter;
        // 宽松实例只管解析 LLM 输出（常带结尾杂字符），不动全局 Bean —— T0 钉死的决策
        this.lenientMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public AnswerResponse ask(AnalysisTaskSnapshot snapshot, String question,
                              String unitId, String clientKey) {
        return ask(snapshot, question, unitId, null, null, clientKey, null);
    }

    public AnswerResponse ask(AnalysisTaskSnapshot snapshot, String question,
                              String unitId, String clientKey, LlmConfig requestConfig) {
        return ask(snapshot, question, unitId, null, null, clientKey, requestConfig);
    }

    /**
     * 行锚点版（T14 选中标识符提问）：把选中范围做成聚焦片段置顶进提示词。
     * 行号参与语义，缓存 key 不含行号会错命中 —— 行锚点提问**不走缓存**。
     */
    public AnswerResponse ask(AnalysisTaskSnapshot snapshot, String question, String unitId,
                              Integer anchorStartLine, Integer anchorEndLine, String clientKey) {
        return ask(snapshot, question, unitId, anchorStartLine, anchorEndLine, clientKey, null);
    }

    /** 带本次请求 LLM 配置（用户自填 key）的版本；{@code requestConfig} 为 null 时回落服务端默认。 */
    public AnswerResponse ask(AnalysisTaskSnapshot snapshot, String question, String unitId,
                              Integer anchorStartLine, Integer anchorEndLine, String clientKey,
                              LlmConfig requestConfig) {
        return askCore(snapshot, question, unitId, anchorStartLine, anchorEndLine, clientKey, requestConfig);
    }

    private AnswerResponse askCore(AnalysisTaskSnapshot snapshot, String question, String unitId,
                                   Integer anchorStartLine, Integer anchorEndLine, String clientKey,
                                   LlmConfig requestConfig) {
        // 本次请求生效的配置：请求头 > 服务端默认。
        // 缓存 key 与响应 model 都必须按它来 —— 否则用户自带模型会命中服务端模型的缓存
        // （拿到别的模型答的答案、且自己那个模型根本没被调用），响应里也会报错模型名。
        LlmConfig effective = requestConfig != null && requestConfig.configured()
                ? requestConfig : serverDefaultConfig;
        CacheKey cacheKey = anchorStartLine == null
                ? cacheKeyFor(snapshot, question, unitId, effective.model()) : null;
        if (cacheKey != null) {
            Optional<AnswerResponse> cached = cacheService.get(cacheKey);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        List<RetrievedSnippet> snippets = new ArrayList<>(
                retriever.retrieve(question, snapshot.outcome(), unitId));
        focusedSnippet(snapshot.outcome(), unitId, anchorStartLine, anchorEndLine)
                .ifPresent(focused -> {
                    snippets.removeIf(s -> s.file().equals(focused.file())
                            && s.startLine() == focused.startLine()
                            && s.endLine() == focused.endLine());
                    snippets.add(0, focused);
                });
        if (snippets.isEmpty()) {
            // 问答必须基于检索片段：没检索到就明说，而不是把空上下文甩给 LLM
            return new AnswerResponse(NO_SNIPPETS_ANSWER, List.of(), effective.model());
        }

        String userPrompt = buildPrompt(question, snippets, List.of());
        for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
            RateLimiter.Consumption consumption =
                    rateLimiter.consume(clientKey, estimateTokens(userPrompt));
            if (!consumption.allowed()) {
                throw new RateLimitExceededException(consumption.usedToday(), consumption.dailyLimit());
            }
            // 带用户自填 key 用临时客户端（用完即弃），否则回落服务端默认
            String raw = llmClientFactory.create(effective).complete(SYSTEM_PROMPT, userPrompt);
            ParsedAnswer parsed = parse(raw);
            ValidationResult validation = validate(parsed.references(), snippets);
            if (validation.invalid().isEmpty() || attempt == maxAttempts()) {
                AnswerResponse response = new AnswerResponse(
                        parsed.answer(), validation.valid(), effective.model());
                if (cacheKey != null) {
                    cacheService.put(cacheKey, response);
                }
                return response;
            }
            userPrompt = buildPrompt(question, snippets, validation.invalid());
        }
        throw new IllegalStateException("不可达：重试循环必在 maxAttempts 处返回");
    }

    // ---------- T14：行锚点聚焦片段 ----------

    /** 聚焦片段的分数标记：远高于检索权重，仅用于提示词排序与「聚焦」标注。 */
    private static final double FOCUSED_SCORE = 100.0;

    /**
     * 把选中范围切成聚焦片段（钳制在单元范围内）。unitId 未知或范围非法时返回空，
     * 调用方退化为普通检索 —— 行锚点是增强，不是硬前置。
     */
    private static Optional<RetrievedSnippet> focusedSnippet(AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                                             String unitId,
                                                             Integer anchorStartLine,
                                                             Integer anchorEndLine) {
        if (anchorStartLine == null || anchorStartLine < 1
                || outcome == null || outcome.result() == null || unitId == null) {
            return Optional.empty();
        }
        CodeUnitInfo unit = outcome.result().codeUnits().stream()
                .filter(u -> u.id().equals(unitId))
                .findFirst()
                .orElse(null);
        if (unit == null) {
            return Optional.empty();
        }
        int start = Math.max(unit.startLine(), Math.min(anchorStartLine, unit.endLine()));
        int end = anchorEndLine == null || anchorEndLine < start
                ? start
                : Math.min(Math.max(anchorEndLine, start), unit.endLine());
        String content = sliceLines(outcome.sourceLines(), unit.filePath(), start, end);
        return Optional.of(new RetrievedSnippet(
                unit.filePath(), unit.language(), start, end, content, FOCUSED_SCORE));
    }

    private static String sliceLines(Map<String, List<String>> sourceLines,
                                     String file, int start, int end) {
        List<String> lines = sourceLines == null ? null : sourceLines.get(file);
        if (lines == null) {
            return "";
        }
        int from = start - 1;
        int to = Math.min(end, lines.size());
        if (from < 0 || from >= to) {
            return "";
        }
        return String.join("\n", lines.subList(from, to));
    }

    private int maxAttempts() {
        return Math.max(1, properties.getMaxAttempts());
    }

    // ---------- T11：缓存 key 与 token 估算 ----------

    /**
     * commitSha 未知（null/空白）时返回 null —— 调用方据此完全跳过缓存。
     *
     * <p>{@code model} 传**本次请求生效的模型**（而非服务端配置）：不同模型的答案不可互用，
     * 否则用户自带模型会直接命中服务端模型的缓存条目。
     */
    private CacheKey cacheKeyFor(AnalysisTaskSnapshot snapshot, String question, String unitId,
                                 String model) {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = snapshot.outcome();
        String commitSha = outcome == null ? null : outcome.commitSha();
        if (commitSha == null || commitSha.isBlank()) {
            return null;
        }
        return new CacheKey(commitSha, resolveAnchorFile(outcome, unitId),
                hashQuestion(question), model);
    }

    private static String resolveAnchorFile(AnalysisTaskSnapshot.AnalysisOutcome outcome, String unitId) {
        if (unitId == null || unitId.isBlank() || outcome == null || outcome.result() == null) {
            return "";
        }
        return outcome.result().codeUnits().stream()
                .filter(unit -> unit.id().equals(unitId))
                .map(CodeUnitInfo::filePath)
                .findFirst()
                .orElse("");
    }

    static String hashQuestion(String question) {
        String normalized = question == null ? "" : question.replaceAll("\\s+", " ").trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 提示词级估算（字符数/4，中英同口径）。不解析 usage 字段 —— 那会迫使改 LlmClient 接口。 */
    static long estimateTokens(String userPrompt) {
        return Math.max(1, (SYSTEM_PROMPT.length() + userPrompt.length()) / 4);
    }

    // ---------- 提示词 ----------

    private static String buildPrompt(String question, List<RetrievedSnippet> snippets,
                                      List<RejectedReference> rejected) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("问题：\n").append(question).append("\n\n");
        prompt.append("以下是检索到的代码片段"
                + "（每行开头冒号前的数字是该行在文件中的真实行号，引用时只能使用这些行号）：\n");
        int index = 0;
        for (RetrievedSnippet snippet : snippets) {
            String focus = snippet.score() >= FOCUSED_SCORE ? "【聚焦】" : "";
            prompt.append("### ").append(focus).append("片段 ").append(index++).append("：")
                    .append(snippet.file()).append("（").append(snippet.language())
                    .append("，行 ").append(snippet.startLine())
                    .append("–").append(snippet.endLine()).append("）\n");
            String[] lines = snippet.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                prompt.append(snippet.startLine() + i).append(" | ").append(lines[i]).append('\n');
            }
            prompt.append('\n');
        }
        if (!rejected.isEmpty()) {
            prompt.append("上一轮你给出的以下引用未通过校验，已丢弃：\n");
            for (RejectedReference reference : rejected) {
                prompt.append("- ").append(reference.file())
                        .append(" 行 ").append(reference.startLine())
                        .append("–").append(reference.endLine())
                        .append("：行号不在任何检索片段内\n");
            }
            prompt.append("请重新回答：每个引用都必须逐条落在上述片段标注的行号区间内。\n");
        }
        return prompt.toString();
    }

    // ---------- 解析（宽松，逐字段容忍） ----------

    private ParsedAnswer parse(String raw) {
        String stripped = stripFences(raw);
        try {
            JsonNode root = lenientMapper.readTree(stripped);
            if (root == null || !root.isObject()) {
                return ParsedAnswer.raw(stripped);
            }
            JsonNode answerNode = root.path("answer");
            String answer = answerNode.isTextual() ? answerNode.asText() : stripped;
            List<ParsedReference> references = new ArrayList<>();
            JsonNode referencesNode = root.get("references");
            if (referencesNode != null && referencesNode.isArray()) {
                for (JsonNode element : referencesNode) {
                    toReference(element).ifPresent(references::add);
                }
            }
            return new ParsedAnswer(answer, references);
        } catch (JacksonException e) {
            log.debug("LLM 输出无法解析为 JSON，降级为纯文本答案：{}", e.getMessage());
            return ParsedAnswer.raw(stripped);
        }
    }

    /** 剥 markdown 围栏（首行 ```… 与末尾 ```），其余原样。 */
    static String stripFences(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }

    /** 逐条容忍：单条引用字段坏掉只丢这一条，不连坐整个答案。 */
    private static Optional<ParsedReference> toReference(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String file = textOrEmpty(node, "file");
        String language = textOrEmpty(node, "language");
        OptionalInt startLine = intOrEmpty(node, "startLine");
        OptionalInt endLine = intOrEmpty(node, "endLine");
        if (file.isBlank() || language.isBlank()
                || startLine.isEmpty() || endLine.isEmpty()
                || startLine.getAsInt() < 1 || endLine.getAsInt() < startLine.getAsInt()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedReference(
                file, language, startLine.getAsInt(), endLine.getAsInt()));
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /** 数字或数字字符串都收（LLM 常把行号写成字符串），其余当缺失。 */
    private static OptionalInt intOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (value.isIntegralNumber()) {
            return OptionalInt.of(value.intValue());
        }
        if (value.isTextual()) {
            try {
                return OptionalInt.of(Integer.parseInt(value.asText().trim()));
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    // ---------- 引用校验（行号只来自检索层） ----------

    private static ValidationResult validate(List<ParsedReference> references,
                                             List<RetrievedSnippet> snippets) {
        List<AnswerResponse.Reference> valid = new ArrayList<>();
        List<RejectedReference> invalid = new ArrayList<>();
        for (ParsedReference reference : references) {
            boolean contained = snippets.stream().anyMatch(snippet ->
                    snippet.file().equals(reference.file())
                            && snippet.language().equals(reference.language())
                            && reference.startLine() >= snippet.startLine()
                            && reference.endLine() <= snippet.endLine());
            if (contained) {
                valid.add(new AnswerResponse.Reference(reference.file(), reference.language(),
                        reference.startLine(), reference.endLine()));
            } else {
                invalid.add(new RejectedReference(reference.file(), reference.language(),
                        reference.startLine(), reference.endLine()));
            }
        }
        return new ValidationResult(List.copyOf(valid), List.copyOf(invalid));
    }

    private record ParsedAnswer(String answer, List<ParsedReference> references) {
        static ParsedAnswer raw(String text) {
            return new ParsedAnswer(text, List.of());
        }
    }

    private record ParsedReference(String file, String language, int startLine, int endLine) {
    }

    private record RejectedReference(String file, String language, int startLine, int endLine) {
    }

    private record ValidationResult(List<AnswerResponse.Reference> valid,
                                    List<RejectedReference> invalid) {
    }
}
