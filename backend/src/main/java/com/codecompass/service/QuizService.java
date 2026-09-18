package com.codecompass.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F4 自动测验生成与判分。
 *
 * <p><b>行号来自发给 LLM 的源码</b>：只把选中类（≤5 个）的带真实行号源码发给 LLM，
 * LLM 产出的每道题 reference 必须落在这些类的行区间内，不匹配丢弃该题。
 * 题型单选（2~4 选项）/判断（「正确/错误」），answer 统一 0-based 下标。
 */
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private static final int MIN_QUESTIONS = 5;
    private static final int MAX_QUESTIONS = 10;

    private static final String SYSTEM_PROMPT = """
            你是代码讲解助手，根据给定的源码生成测验题。
            规则：
            1. 题型只有两种：single_choice（单选，2~4 个选项）与 true_false（判断，选项固定 ["正确","错误"]）；
            2. 每题引用一行或一段源码，reference 的行号必须来自源码每行开头标注的真实行号，禁止编造；
            3. answer 是正确选项的 0-based 下标（整数）；
            4. 生成 5~10 道题，只输出 JSON：
            {"questions":[{"type":"single_choice","question":"...","options":["...","..."],"answer":0,"explanation":"...","reference":{"file":"...","startLine":1,"endLine":1}}]}
            不要输出 JSON 之外的任何内容。
            """;

    private final LlmClient llmClient;
    private final JsonMapper lenientMapper;

    public QuizService(LlmClient llmClient, JsonMapper jsonMapper) {
        this.llmClient = llmClient;
        this.lenientMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    public Quiz generate(AnalyzeResult result, Map<String, List<String>> sourceLines,
                         String repoUrl, String commitSha, List<String> selectedUnitIds) {
        List<CodeUnitInfo> selected = result.codeUnits().stream()
                .filter(unit -> selectedUnitIds != null && selectedUnitIds.contains(unit.id()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个类");
        }

        String raw = llmClient.complete(SYSTEM_PROMPT, buildPrompt(selected, sourceLines));
        List<Quiz.Question> questions = parseAndValidate(raw, selected, sourceLines);
        return new Quiz(UUID.randomUUID().toString(), repoUrl, commitSha, questions);
    }

    /** 判分：返回 {correct, total, accuracy}。 */
    public GradeResult grade(Quiz quiz, List<AnswerSubmission> answers) {
        if (quiz == null || answers == null) {
            return new GradeResult(0, 0, 0.0);
        }
        Map<String, Quiz.Question> byId = new java.util.HashMap<>();
        quiz.questions().forEach(question -> byId.put(question.id(), question));
        int correct = 0;
        for (AnswerSubmission answer : answers) {
            Quiz.Question question = byId.get(answer.questionId());
            if (question != null && question.answer() == answer.answerIndex()) {
                correct++;
            }
        }
        int total = quiz.questions().size();
        return new GradeResult(correct, total, total == 0 ? 0.0 : (double) correct / total);
    }

    public record AnswerSubmission(String questionId, int answerIndex) {
    }

    public record GradeResult(int correct, int total, double accuracy) {
    }

    // ---------- prompt / parse / validate ----------

    private static String buildPrompt(List<CodeUnitInfo> units, Map<String, List<String>> sourceLines) {
        StringBuilder prompt = new StringBuilder("以下是选中类的源码"
                + "（每行开头冒号前的数字是该行的真实行号，出题引用只能用它）：\n");
        for (CodeUnitInfo unit : units) {
            prompt.append("### ").append(unit.packageName()).append('.').append(unit.name())
                    .append("（").append(unit.filePath()).append("，行 ")
                    .append(unit.startLine()).append('–').append(unit.endLine()).append("）\n");
            List<String> lines = sourceLines == null ? List.of() : sourceLines.get(unit.filePath());
            if (lines != null) {
                int from = unit.startLine() - 1;
                int to = Math.min(unit.endLine(), lines.size());
                for (int i = from; i < to; i++) {
                    prompt.append(unit.startLine() + (i - from)).append(" | ").append(lines.get(i)).append('\n');
                }
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private List<Quiz.Question> parseAndValidate(String raw, List<CodeUnitInfo> units,
                                                 Map<String, List<String>> sourceLines) {
        List<Quiz.Question> questions = new ArrayList<>();
        try {
            JsonNode root = lenientMapper.readTree(AnswerService.stripFences(raw));
            JsonNode array = root.get("questions");
            if (array != null && array.isArray()) {
                int index = 0;
                for (JsonNode node : array) {
                    toQuestion(node, index, units).ifPresent(questions::add);
                    index++;
                    if (questions.size() >= MAX_QUESTIONS) {
                        break;
                    }
                }
            }
        } catch (JacksonException e) {
            log.warn("LLM 测验输出解析失败：{}", e.getMessage());
        }
        return List.copyOf(questions);
    }

    /** 逐条校验，任何字段非法即丢弃该题（不连坐整份测验）。 */
    private static java.util.Optional<Quiz.Question> toQuestion(JsonNode node, int index,
                                                                List<CodeUnitInfo> units) {
        if (node == null || !node.isObject()) {
            return java.util.Optional.empty();
        }
        String type = node.path("type").asText("");
        String text = node.path("question").asText("");
        String explanation = node.path("explanation").asText("");
        JsonNode optionsNode = node.get("options");
        if (!("single_choice".equals(type) || "true_false".equals(type))
                || text.isBlank() || explanation.isBlank()
                || optionsNode == null || !optionsNode.isArray()) {
            return java.util.Optional.empty();
        }
        List<String> options = new ArrayList<>();
        for (JsonNode option : optionsNode) {
            if (option.isTextual()) {
                options.add(option.asText());
            }
        }
        boolean typeOk = "single_choice".equals(type) ? (options.size() >= 2 && options.size() <= 4)
                : options.size() == 2;
        if (!typeOk) {
            return java.util.Optional.empty();
        }
        int answer = node.path("answer").isIntegralNumber() ? node.path("answer").asInt() : -1;
        if (answer < 0 || answer >= options.size()) {
            return java.util.Optional.empty();
        }
        AnswerResponse.Reference reference = referenceOf(node, units);
        if (reference == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Quiz.Question(
                "q" + index, type, text, options, answer, explanation, reference));
    }

    /** reference 必须落在某个选中类的行区间内（行号来自发给 LLM 的源码）。 */
    private static AnswerResponse.Reference referenceOf(JsonNode node, List<CodeUnitInfo> units) {
        String file = node.path("reference").path("file").asText("");
        int startLine = node.path("reference").path("startLine").asInt(-1);
        int endLine = node.path("reference").path("endLine").asInt(-1);
        if (file.isBlank() || startLine < 1 || endLine < startLine) {
            return null;
        }
        for (CodeUnitInfo unit : units) {
            if (unit.filePath().equals(file)
                    && startLine >= unit.startLine() && endLine <= unit.endLine()) {
                return new AnswerResponse.Reference(file, unit.language(), startLine, endLine);
            }
        }
        return null;
    }
}
