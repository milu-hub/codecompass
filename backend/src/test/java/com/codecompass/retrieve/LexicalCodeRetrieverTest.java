package com.codecompass.retrieve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FieldInfo;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.graph.DependencyGraph;
import com.codecompass.graph.GraphNode;
import com.codecompass.service.AnalysisTaskSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词法检索。
 *
 * 最关键的是 content 的行号边界：分析模型的行号是 1-based 闭区间（T3 守卫），
 * 而 sourceLines 是 0-based 的 List。测试用编号行（L1、L2…）生成源码快照，
 * 任何 off-by-one 都会让断言里"content 精确等于 L10..L12"当场失败 ——
 * 而不是像手写内容那样和实现错在同一个方向上。
 */
class LexicalCodeRetrieverTest {

    private static final String OWNER_CONTROLLER_FILE = "src/main/java/com/example/OwnerController.java";
    private static final String OWNER_REPOSITORY_FILE = "src/main/java/com/example/OwnerRepository.java";

    private final LexicalCodeRetriever retriever =
            new LexicalCodeRetriever(new RetrieveProperties());

    // ---------- 类名 / 方法名命中 ----------

    @Test
    @DisplayName("问题里的类名命中：返回该类的完整范围，content 首行 L1、末行 L40")
    void classNameRetrievesClassRange() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("OwnerController", outcome, null);

        RetrievedSnippet snippet = snippets.stream()
                .filter(s -> s.file().equals(OWNER_CONTROLLER_FILE)).findFirst().orElseThrow();
        assertThat(snippet.startLine()).isEqualTo(1);
        assertThat(snippet.endLine()).isEqualTo(40);
        assertThat(snippet.language()).isEqualTo("java");
        assertThat(snippet.content()).startsWith("L1\n").endsWith("L40");
    }

    @Test
    @DisplayName("问题里的方法名命中：返回**方法**的行范围而非类范围")
    void methodNameRetrievesMethodRange() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("processFindForm", outcome, null);

        RetrievedSnippet method = snippets.stream()
                .filter(s -> s.startLine() == 10).findFirst().orElseThrow();
        assertThat(method.endLine()).isEqualTo(20);
        assertThat(method.content()).isEqualTo(String.join("\n", numberedLines(10, 20)));
        assertThat(method.file()).isEqualTo(OWNER_CONTROLLER_FILE);
    }

    @Test
    @DisplayName("camelCase 与大小写不敏感：owner controller 与 OwnerController 互相命中")
    void matchesCamelCaseAndCaseInsensitively() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        assertThat(retriever.retrieve("owner controller", outcome, null))
                .anySatisfy(snippet -> assertThat(snippet.file()).isEqualTo(OWNER_CONTROLLER_FILE));
        assertThat(retriever.retrieve("OWNERCONTROLLER", outcome, null)).isNotEmpty();
    }

    @Test
    @DisplayName("类名命中排在方法名命中之前（权重更高）")
    void classNameScoresHigherThanMethodName() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();
        // "OwnerController" 既命中类名（2.0）也命中…… 不，类名与字段都算。用纯方法名对照：
        List<RetrievedSnippet> byMethod = retriever.retrieve("processFindForm", outcome, null);
        List<RetrievedSnippet> byClass = retriever.retrieve("OwnerController", outcome, null);

        assertThat(byClass.get(0).score())
                .as("类名命中（2.0）应高于方法名命中（1.0）")
                .isGreaterThan(byMethod.get(0).score());
    }

    // ---------- 锚点 ----------

    @Test
    @DisplayName("中文问题 + 锚点：即使词法零命中，也保底返回锚点类")
    void anchorIsAlwaysIncludedForChineseQuestion() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("表单提交是怎么处理的", outcome, "u-oc");

        assertThat(snippets).anySatisfy(snippet -> {
            assertThat(snippet.file()).isEqualTo(OWNER_CONTROLLER_FILE);
            assertThat(snippet.startLine()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("锚点的一跳依赖（出边）一并入围")
    void anchorPullsInDirectDependencies() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("表单提交", outcome, "u-oc");

        assertThat(snippets).anySatisfy(snippet ->
                assertThat(snippet.file()).isEqualTo(OWNER_REPOSITORY_FILE));
    }

    @Test
    @DisplayName("孤立锚点（不在图的节点里）也能保底返回 —— 单元查 result、邻居查 graph")
    void isolatedAnchorStillIncluded() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("它是什么", outcome, "u-pet");

        assertThat(snippets).anySatisfy(snippet ->
                assertThat(snippet.file()).isEqualTo("src/main/java/com/example/Pet.java"));
    }

    // ---------- 空命中与去重 ----------

    @Test
    @DisplayName("无锚点且词法零命中：返回空列表（T10 据此提示先点击一个类）")
    void emptyHitsWithoutAnchorYieldsEmptyList() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        assertThat(retriever.retrieve("zzzz 完全不存在的词", outcome, null)).isEmpty();
    }

    @Test
    @DisplayName("同一文件同一范围只出现一次（锚点与词法同时命中不重复）")
    void deduplicatesByFileAndRange() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = retriever.retrieve("OwnerController", outcome, "u-oc");

        long controllerRanges = snippets.stream()
                .filter(s -> s.file().equals(OWNER_CONTROLLER_FILE) && s.startLine() == 1)
                .count();
        assertThat(controllerRanges).isEqualTo(1);
    }

    // ---------- 截断与排序 ----------

    @Test
    @DisplayName("结果按分数降序、再按 file/startLine 排序，且截断到配置上限")
    void sortsAndCapsSnippets() {
        RetrieveProperties properties = new RetrieveProperties();
        properties.setMaxSnippets(2);
        LexicalCodeRetriever capped = new LexicalCodeRetriever(properties);
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();

        List<RetrievedSnippet> snippets = capped.retrieve("OwnerController", outcome, "u-oc");

        assertThat(snippets).hasSize(2);
        assertThat(snippets.get(0).score()).isGreaterThanOrEqualTo(snippets.get(1).score());
    }

    @Test
    @DisplayName("sourceLines 缺该文件时 content 为空串、不抛异常（显式而非静默）")
    void missingSourceLinesYieldsEmptyContent() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome = outcome();
        AnalysisTaskSnapshot.AnalysisOutcome withoutLines = new AnalysisTaskSnapshot.AnalysisOutcome(
                outcome.result(), outcome.graph(), outcome.roles(),
                Map.of(OWNER_CONTROLLER_FILE, numberedLines(40)));  // 缺 Pet 的文件

        List<RetrievedSnippet> snippets = retriever.retrieve("Pet", withoutLines, null);
        RetrievedSnippet pet = snippets.stream()
                .filter(s -> s.file().equals("src/main/java/com/example/Pet.java"))
                .findFirst().orElseThrow();

        assertThat(pet.content()).isEmpty();
    }

    // ---------- 构造夹具 ----------

    private static List<String> numberedLines(int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add("L" + i);
        }
        return lines;
    }

    private static List<String> numberedLines(int from, int to) {
        List<String> lines = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            lines.add("L" + i);
        }
        return lines;
    }

    private static CodeUnitInfo unit(String id, String file, String name, int start, int end,
                                     String... annotations) {
        return new CodeUnitInfo(id, "repo-1", file, "java", "spring",
                "com.example", name, "class", List.of(annotations), List.of(), start, end);
    }

    private static AnalysisTaskSnapshot.AnalysisOutcome outcome() {
        CodeUnitInfo ownerController = new CodeUnitInfo(
                "u-oc", "repo-1", OWNER_CONTROLLER_FILE, "java", "spring",
                "com.example", "OwnerController", "class",
                List.of("@Controller"), List.of(new FieldInfo("ownerRepository", "OwnerRepository", List.of())),
                1, 40);
        CodeUnitInfo ownerRepository = unit("u-or", OWNER_REPOSITORY_FILE, "OwnerRepository", 1, 10);
        CodeUnitInfo pet = unit("u-pet", "src/main/java/com/example/Pet.java", "Pet", 1, 15);

        MethodInfo processFindForm = new MethodInfo("m-1", "u-oc", "processFindForm",
                "public String processFindForm(Owner owner)", List.of("@GetMapping"), 10, 20);
        MethodInfo showOwner = new MethodInfo("m-2", "u-oc", "showOwner",
                "public String showOwner()", List.of(), 25, 30);

        AnalyzeResult result = new AnalyzeResult("repo-1", "java", "spring",
                List.of(ownerController, ownerRepository, pet),
                List.of(processFindForm, showOwner), List.of(), List.of());

        DependencyEdge edge = new DependencyEdge("e-1", "repo-1", "u-oc", "u-or", "field", "java");
        DependencyGraph graph = new DependencyGraph("repo-1", "java", "spring",
                List.of(node(ownerController), node(ownerRepository)),   // Pet 是孤立的，不在图节点里
                List.of(edge), List.of("u-pet"), "graph LR");

        Map<String, List<String>> sourceLines = new LinkedHashMap<>();
        sourceLines.put(OWNER_CONTROLLER_FILE, numberedLines(40));
        sourceLines.put(OWNER_REPOSITORY_FILE, numberedLines(10));
        sourceLines.put("src/main/java/com/example/Pet.java", numberedLines(15));

        return new AnalysisTaskSnapshot.AnalysisOutcome(result, graph, Map.of(), sourceLines);
    }

    private static GraphNode node(CodeUnitInfo unit) {
        return new GraphNode(unit.id(), unit.name(), unit.packageName() + "." + unit.name(),
                unit.kind(), "", unit.filePath(), unit.startLine(), unit.endLine());
    }
}
