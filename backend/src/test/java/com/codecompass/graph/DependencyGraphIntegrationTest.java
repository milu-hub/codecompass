package com.codecompass.graph;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.LanguageAnalyzerRegistry;
import com.codecompass.analyzer.java.CoreAnnotationClassifier;
import com.codecompass.repo.CloneResult;
import com.codecompass.repo.CodeUnitFileInfo;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 验收：真实样本的图能建出来、能渲染成 Mermaid、且包含人工标注的核心依赖边。
 *
 * 需要网络，@Tag("integration")，默认构建排除。
 */
@Tag("integration")
@SpringBootTest
class DependencyGraphIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";
    private static final String MICROSERVICES =
            "https://github.com/spring-petclinic/spring-petclinic-microservices";

    @Autowired
    private GitRepositoryCloner cloner;

    @Autowired
    private SourceFileScanner scanner;

    @Autowired
    private LanguageAnalyzerRegistry registry;

    @Autowired
    private CoreAnnotationClassifier classifier;

    @Autowired
    private DependencyGraphBuilder graphBuilder;

    @Autowired
    private TempWorkspaceManager tempWorkspaceManager;

    @Test
    @DisplayName("petclinic：存在 OwnerController -> OwnerRepository 的边，且图能渲染为 Mermaid")
    void petclinicGraphContainsRequiredEdgeAndRenders() {
        withRepository(PETCLINIC, repoDir -> {
            AnalyzeResult result = analyze(repoDir);
            DependencyGraph graph = graphBuilder.build(result, roleMap(result));

            // T6 验收（TASKS.md）：这条边必须存在
            CodeUnitInfo ownerController = unitNamed(result, "OwnerController");
            CodeUnitInfo ownerRepository = unitNamed(result, "OwnerRepository");
            assertThat(graph.edges()).anySatisfy(edge -> {
                assertThat(edge.fromCodeUnitId()).isEqualTo(ownerController.id());
                assertThat(edge.toCodeUnitId()).isEqualTo(ownerRepository.id());
            });

            // 可渲染
            assertThat(graph.mermaid()).startsWith("graph LR").contains("-->");

            // 渲染的文本里不得出现原始 id 的路径分隔符 "/"（节点 id 已用序号 n0..，label 用简单类名）。
            // 注：不再断言 doesNotContain("#")——T24 起 classDef 用十六进制颜色（fill:#e3f2fd），# 是合法语法。
            assertThat(graph.mermaid()).doesNotContain("/");

            // 引用完整性
            Set<String> nodeIds = graph.nodes().stream().map(GraphNode::id).collect(Collectors.toSet());
            assertThat(graph.edges()).allSatisfy(edge -> {
                assertThat(nodeIds).contains(edge.fromCodeUnitId());
                assertThat(nodeIds).contains(edge.toCodeUnitId());
            });

            // 实测 petclinic 有 6 个孤立单元，应被报告而不是画进图
            assertThat(graph.isolatedCodeUnitIds()).isNotEmpty();
            assertThat(graph.nodes()).extracting(GraphNode::id)
                    .doesNotContainAnyElementsOf(graph.isolatedCodeUnitIds());

            // 邻域：OwnerController 的一跳邻域应包含 OwnerRepository
            DependencyGraph neighborhood = graphBuilder.neighborhoodOf(graph, ownerController.id(), 1);
            assertThat(neighborhood.nodes()).extracting(GraphNode::label)
                    .contains("OwnerController", "OwnerRepository");
            assertThat(neighborhood.mermaid()).startsWith("graph LR").contains("-->");
        });
    }

    @Test
    @DisplayName("microservices：8 个模块的图整体可渲染，孤立节点被单独报告")
    void microservicesGraphRenders() {
        withRepository(MICROSERVICES, repoDir -> {
            AnalyzeResult result = analyze(repoDir);
            DependencyGraph graph = graphBuilder.build(result, roleMap(result));

            assertThat(graph.mermaid()).startsWith("graph LR").contains("-->");
            assertThat(graph.nodes()).isNotEmpty();
            assertThat(graph.isolatedCodeUnitIds())
                    .as("实测微服务样本有 22/54 个孤立单元，必须被报告")
                    .hasSizeGreaterThan(10);

            Set<String> nodeIds = graph.nodes().stream().map(GraphNode::id).collect(Collectors.toSet());
            assertThat(graph.edges()).allSatisfy(edge -> {
                assertThat(nodeIds).contains(edge.fromCodeUnitId());
                assertThat(nodeIds).contains(edge.toCodeUnitId());
            });

            // 节点的角色来自 T5 分类器（通过入参传入，图构建器自身不认识框架）
            assertThat(graph.nodes()).anySatisfy(node ->
                    assertThat(node.role()).isEqualTo("entry"));
        });
    }

    // ---------- helpers ----------

    private Map<String, String> roleMap(AnalyzeResult result) {
        Map<String, String> roles = new LinkedHashMap<>();
        for (CodeUnitInfo unit : result.codeUnits()) {
            classifier.roleOf(unit).ifPresent(role -> roles.put(unit.id(), role));
        }
        return roles;
    }

    private void withRepository(String repositoryUrl, Consumer<Path> action) {
        CloneResult cloneResult = cloner.clone(repositoryUrl);
        assertThat(cloneResult.success())
                .as("前置克隆应成功，实际错误：%s", cloneResult.errorMessage())
                .isTrue();

        Path repoDir = Path.of(cloneResult.localPath());
        try {
            action.accept(repoDir);
        } finally {
            tempWorkspaceManager.delete(repoDir.getParent());
        }
    }

    private AnalyzeResult analyze(Path repoDir) {
        List<CodeUnitFileInfo> files = scanner.scan(repoDir);
        LanguageAnalyzer analyzer = registry.forLanguage("java")
                .orElseThrow(() -> new AssertionError(
                        "注册表未提供 java 分析器，已支持：" + registry.supportedLanguages()));
        AnalyzeResult result = analyzer.analyze(new AnalyzeRequest("graph-it", repoDir, files));
        assertThat(result.failedFiles()).as("解析失败：%s", result.failedFiles()).isEmpty();
        return result;
    }

    private static CodeUnitInfo unitNamed(AnalyzeResult result, String name) {
        return result.codeUnits().stream()
                .filter(unit -> unit.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中找不到类：" + name));
    }
}
