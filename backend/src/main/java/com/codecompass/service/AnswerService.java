package com.codecompass.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.retrieve.CodeRetriever;
import com.codecompass.retrieve.RetrievedSnippet;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T10 问答编排：检索（T9）→ 拼提示词 → LLM → 解析 → 引用逐条校验 → 不匹配丢弃重试。
 *
 * <p><b>行号只来自检索层</b>：提示词里每行前缀真实行号，LLM 报出的引用逐条与检索片段
 * 做严格包含比对，不匹配的丢弃、带反馈重试（预算 {@code maxAttempts} 封顶）。
 * 传输类错误不进重试循环，直接以 {@link LlmException} 向上（控制器 → 502）。
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
    private final LlmClient llmClient;
    private final LlmProperties properties;
    private final JsonMapper lenientMapper;

    public AnswerService(CodeRetriever retriever, LlmClient llmClient,
                         LlmProperties properties, JsonMapper jsonMapper) {
        this.retriever = retriever;
        this.llmClient = llmClient;
        this.properties = properties;
        // 宽松实例只管解析 LLM 输出（常带结尾杂字符），不动全局 Bean —— T0 钉死的决策
        this.lenientMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public AnswerResponse ask(AnalysisTaskSnapshot snapshot, String question, String unitId) {
        List<RetrievedSnippet> snippets = retriever.retrieve(
                question, snapshot.outcome(), unitId);
        if (snippets.isEmpty()) {
            // 问答必须基于检索片段：没检索到就明说，而不是把空上下文甩给 LLM
            return new AnswerResponse(NO_SNIPPETS_ANSWER, List.of(), properties.getModel());
        }

        String userPrompt = buildPrompt(question, snippets, List.of());
        for (int attempt = 1; attempt <= maxAttempts(); attempt++) {
            String raw = llmClient.complete(SYSTEM_PROMPT, userPrompt);
            ParsedAnswer parsed = parse(raw);
            ValidationResult validation = validate(parsed.references(), snippets);
            if (validation.invalid().isEmpty() || attempt == maxAttempts()) {
                return new AnswerResponse(parsed.answer(), validation.valid(), properties.getModel());
            }
            userPrompt = buildPrompt(question, snippets, validation.invalid());
        }
        throw new IllegalStateException("不可达：重试循环必在 maxAttempts 处返回");
    }

    private int maxAttempts() {
        return Math.max(1, properties.getMaxAttempts());
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
            prompt.append("### 片段 ").append(index++).append("：")
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
