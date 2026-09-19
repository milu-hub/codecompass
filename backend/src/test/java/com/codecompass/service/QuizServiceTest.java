package com.codecompass.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * F4 测验：reference 校验（行号必须来自发给 LLM 的源码）、题型/选项/下标校验、cap、判分。
 */
class QuizServiceTest {

    private static final String FILE_A = "src/main/java/com/example/A.java";
    private static final String FILE_B = "src/main/java/com/example/B.java";

    private LlmClient llmClient;
    private QuizService service;

    @BeforeEach
    void setUp() {
        llmClient = Mockito.mock(LlmClient.class);
        service = new QuizService(llmClient, JsonMapper.builder().build());
    }

    private static AnalyzeResult result() {
        CodeUnitInfo a = new CodeUnitInfo("u-a", "r", FILE_A, "java", "spring",
                "com.example", "A", "class", List.of(), List.of(), 1, 10);
        CodeUnitInfo b = new CodeUnitInfo("u-b", "r", FILE_B, "java", "spring",
                "com.example", "B", "class", List.of(), List.of(), 1, 10);
        return new AnalyzeResult("r", "java", "spring", List.of(a, b), List.of(), List.of(), List.of());
    }

    private static Map<String, List<String>> sourceLines() {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put(FILE_A, List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10"));
        lines.put(FILE_B, List.of("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10"));
        return lines;
    }

    private static String questionJson(String file, int start, int end) {
        return "{\"type\":\"single_choice\",\"question\":\"Q\",\"options\":[\"甲\",\"乙\",\"丙\",\"丁\"],"
                + "\"answer\":1,\"explanation\":\"E\",\"reference\":{\"file\":\"" + file
                + "\",\"startLine\":" + start + ",\"endLine\":" + end + "}}";
    }

    @Test
    @DisplayName("reference 落在选中类行区间内 → 保留；否则丢弃该题")
    void referenceContainmentFiltersQuestions() {
        when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"questions\":[" + questionJson(FILE_A, 2, 5)
                        + "," + questionJson(FILE_A, 99, 100)   // 越界 → 丢
                        + "," + questionJson("ghost.java", 1, 1) // 陌生文件 → 丢
                        + "]}");

        Quiz quiz = service.generate(result(), sourceLines(), "r", "sha", List.of("u-a", "u-b"));

        assertThat(quiz.questions()).hasSize(1);
        assertThat(quiz.questions().get(0).reference().file()).isEqualTo(FILE_A);
    }

    @Test
    @DisplayName("answer 下标越界或选项数不符 → 丢弃")
    void malformedQuestionsAreDropped() {
        when(llmClient.complete(anyString(), anyString())).thenReturn(
                "{\"questions\":["
                        + "{\"type\":\"single_choice\",\"question\":\"Q\",\"options\":[\"甲\",\"乙\"],\"answer\":9,\"explanation\":\"E\",\"reference\":{\"file\":\"" + FILE_A + "\",\"startLine\":1,\"endLine\":1}},"
                        + "{\"type\":\"true_false\",\"question\":\"Q\",\"options\":[\"正确\",\"错误\",\"多余\"],\"answer\":0,\"explanation\":\"E\",\"reference\":{\"file\":\"" + FILE_A + "\",\"startLine\":1,\"endLine\":1}}"
                        + "]}");

        Quiz quiz = service.generate(result(), sourceLines(), "r", "sha", List.of("u-a"));

        assertThat(quiz.questions()).isEmpty();
    }

    @Test
    @DisplayName("超过 10 道题时截断到 10")
    void questionsAreCappedAtTen() {
        StringBuilder json = new StringBuilder("{\"questions\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(questionJson(FILE_A, 1, 1));
        }
        json.append("]}");
        when(llmClient.complete(anyString(), anyString())).thenReturn(json.toString());

        Quiz quiz = service.generate(result(), sourceLines(), "r", "sha", List.of("u-a"));

        assertThat(quiz.questions()).hasSize(10);
    }

    @Test
    @DisplayName("未选择任何类 → 抛 IllegalArgumentException")
    void emptySelectionThrows() {
        assertThatThrownBy(() -> service.generate(result(), sourceLines(), "r", "sha", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少选择一个类");
    }

    @Test
    @DisplayName("选中超过 5 个类 → 抛 IllegalArgumentException（规格：至少 1 个、最多 5 个）")
    void selectionExceedingFiveThrows() {
        assertThatThrownBy(() -> service.generate(result(), sourceLines(), "r", "sha",
                List.of("u-a", "u-b", "u-c", "u-d", "u-e", "u-f")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多");
    }

    @Test
    @DisplayName("带 requestConfig 时用请求配置构造客户端（factory 收到该配置）")
    void generateUsesRequestConfigWhenProvided() {
        LlmConfig[] captured = new LlmConfig[1];
        LlmClient requestClient = Mockito.mock(LlmClient.class);
        when(requestClient.complete(anyString(), anyString())).thenReturn(
                "{\"questions\":[" + questionJson(FILE_A, 1, 1) + "]}");
        LlmConfig serverDefault = new LlmConfig("default", "deepseek",
                "https://server.example/v1", "sk-server", "server-model", true);
        LlmConfig requestConfig = new LlmConfig("request", "custom",
                "https://req.example/v1", "sk-req", "req-model", false);
        LlmClientFactory factory = config -> {
            captured[0] = config;
            return config == requestConfig ? requestClient : Mockito.mock(LlmClient.class);
        };
        QuizService svc = new QuizService(factory, serverDefault, JsonMapper.builder().build());

        Quiz quiz = svc.generate(result(), sourceLines(), "r", "sha", List.of("u-a"), requestConfig);

        assertThat(captured[0]).isEqualTo(requestConfig);
        assertThat(quiz.questions()).hasSize(1);
    }

    @Test
    @DisplayName("判分：正确数/总数/正确率")
    void gradeComputesAccuracy() {
        Quiz.Question q1 = new Quiz.Question("q0", "single_choice", "Q1",
                List.of("甲", "乙"), 0, "E", new AnswerResponse.Reference(FILE_A, "java", 1, 2));
        Quiz.Question q2 = new Quiz.Question("q1", "true_false", "Q2",
                List.of("正确", "错误"), 1, "E", new AnswerResponse.Reference(FILE_A, "java", 3, 4));
        Quiz quiz = new Quiz("quiz-1", "r", "sha", List.of(q1, q2));

        QuizService.GradeResult grade = service.grade(quiz, List.of(
                new QuizService.AnswerSubmission("q0", 0),   // 对
                new QuizService.AnswerSubmission("q1", 0))); // 错

        assertThat(grade.correct()).isEqualTo(1);
        assertThat(grade.total()).isEqualTo(2);
        assertThat(grade.accuracy()).isEqualTo(0.5);
    }
}
