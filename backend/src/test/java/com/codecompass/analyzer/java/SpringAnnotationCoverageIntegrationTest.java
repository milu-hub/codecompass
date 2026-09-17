package com.codecompass.analyzer.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.yaml.snakeyaml.Yaml;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.LanguageAnalyzer;
import com.codecompass.analyzer.LanguageAnalyzerRegistry;
import com.codecompass.repo.CloneResult;
import com.codecompass.repo.CodeUnitFileInfo;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 验收：核心注解类识别覆盖率。
 *
 * <p>口径（TASKBOOK §04）：分母 = 黄金样本中人工标注的「带核心注解的类」总数。
 * 标注文件在 {@code src/test/resources/golden/*.yaml}，其地面真值是用**独立脚本直接解析原始
 * 源码**得到的 —— 若拿本解析器的输出去誊一份标注，覆盖率恒为 100%，这个指标就毫无意义。
 *
 * <p>分母只含「带核心注解的类」，因此不带注解的类（如 petclinic 的
 * {@code OwnerRepository}，它是 Spring Data 接口）不进分母。
 *
 * <p>需要网络，@Tag("integration")，默认构建排除。
 */
@Tag("integration")
@SpringBootTest
class SpringAnnotationCoverageIntegrationTest {

    /** TASKBOOK §04 的验收目标。 */
    private static final double COVERAGE_TARGET = 0.8;

    @Autowired
    private GitRepositoryCloner cloner;

    @Autowired
    private SourceFileScanner scanner;

    @Autowired
    private LanguageAnalyzerRegistry registry;

    @Autowired
    private CoreAnnotationClassifier classifier;

    @Autowired
    private TempWorkspaceManager tempWorkspaceManager;

    @ParameterizedTest(name = "覆盖率达标：{0}")
    @ValueSource(strings = {
            "golden/spring-petclinic.yaml",
            "golden/spring-petclinic-microservices.yaml"})
    void coverageMeetsTarget(String goldenResource) {
        Map<String, Object> golden = loadGolden(goldenResource);
        String repositoryUrl = String.valueOf(golden.get("repository"));
        List<Map<String, String>> expected = stringMaps(golden.get("coreAnnotatedClasses"));

        withRepository(repositoryUrl, repoDir -> {
            AnalyzeResult result = analyze(repoDir);
            Map<String, CodeUnitInfo> unitsByQualifiedName = indexByQualifiedName(result);

            List<String> missed = new ArrayList<>();
            for (Map<String, String> entry : expected) {
                String qualifiedName = entry.get("name");
                String annotation = entry.get("annotation");
                CodeUnitInfo unit = unitsByQualifiedName.get(qualifiedName);
                if (unit == null) {
                    missed.add(qualifiedName + " -> 该类未被解析出来");
                } else if (!unit.annotations().contains(annotation)) {
                    missed.add(qualifiedName + " -> 期望 " + annotation + "，实际 " + unit.annotations());
                }
            }

            double coverage = expected.isEmpty()
                    ? 1.0
                    : (double) (expected.size() - missed.size()) / expected.size();

            assertThat(coverage)
                    .as("%s 的核心注解类识别覆盖率 %.1f%%，未识别 %d/%d：%s",
                            goldenResource, coverage * 100, missed.size(), expected.size(), missed)
                    .isGreaterThanOrEqualTo(COVERAGE_TARGET);
            assertThat(missed)
                    .as("这两个样本是标准 Spring 代码，解析器又是我们自己的，理论上应当全中；"
                            + "有任何遗漏都说明识别逻辑退化，而不是样本太难")
                    .isEmpty();
        });
    }

    @ParameterizedTest(name = "入口类被识别为 entry：{0}")
    @ValueSource(strings = {
            "golden/spring-petclinic.yaml",
            "golden/spring-petclinic-microservices.yaml"})
    void entryClassesAreIdentified(String goldenResource) {
        Map<String, Object> golden = loadGolden(goldenResource);
        String repositoryUrl = String.valueOf(golden.get("repository"));
        @SuppressWarnings("unchecked")
        List<String> entryClasses = (List<String>) golden.get("entryClasses");

        withRepository(repositoryUrl, repoDir -> {
            AnalyzeResult result = analyze(repoDir);
            Map<String, CodeUnitInfo> unitsByQualifiedName = indexByQualifiedName(result);

            assertThat(entryClasses).isNotEmpty();
            assertThat(entryClasses).allSatisfy(qualifiedName -> {
                CodeUnitInfo unit = unitsByQualifiedName.get(qualifiedName);
                assertThat(unit).as("入口类未被解析出来：%s", qualifiedName).isNotNull();
                assertThat(classifier.roleOf(unit))
                        .as("入口类 %s 应被判为 entry", qualifiedName)
                        .contains("entry");
            });
        });
    }

    @ParameterizedTest(name = "核心依赖关系可解析为边：{0}")
    @ValueSource(strings = {
            "golden/spring-petclinic.yaml",
            "golden/spring-petclinic-microservices.yaml"})
    void coreDependenciesBecomeEdges(String goldenResource) {
        Map<String, Object> golden = loadGolden(goldenResource);
        String repositoryUrl = String.valueOf(golden.get("repository"));
        List<Map<String, String>> dependencies = stringMaps(golden.get("coreDependencies"));

        withRepository(repositoryUrl, repoDir -> {
            AnalyzeResult result = analyze(repoDir);
            Map<String, CodeUnitInfo> unitsByQualifiedName = indexByQualifiedName(result);

            List<String> missing = new ArrayList<>();
            for (Map<String, String> dependency : dependencies) {
                CodeUnitInfo from = unitsByQualifiedName.get(dependency.get("from"));
                CodeUnitInfo to = unitsByQualifiedName.get(dependency.get("to"));
                if (from == null || to == null) {
                    missing.add(dependency + " -> 单元未解析出来");
                    continue;
                }
                boolean edgeExists = result.dependencies().stream().anyMatch(edge ->
                        edge.fromCodeUnitId().equals(from.id()) && edge.toCodeUnitId().equals(to.id()));
                if (!edgeExists) {
                    missing.add(dependency.get("from") + " -> " + dependency.get("to"));
                }
            }

            assertThat(missing)
                    .as("人工标注的核心依赖关系应当都能解析成边")
                    .isEmpty();
        });
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadGolden(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到黄金样本标注文件：" + resource);
            }
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> stringMaps(Object value) {
        return value == null ? List.of() : (List<Map<String, String>>) value;
    }

    private static Map<String, CodeUnitInfo> indexByQualifiedName(AnalyzeResult result) {
        Map<String, CodeUnitInfo> index = new LinkedHashMap<>();
        result.codeUnits().forEach(unit -> index.putIfAbsent(
                unit.packageName().isEmpty() ? unit.name() : unit.packageName() + "." + unit.name(),
                unit));
        return index;
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
        AnalyzeResult result = analyzer.analyze(new AnalyzeRequest("golden", repoDir, files));
        assertThat(result.failedFiles()).as("解析失败的样本文件：%s", result.failedFiles()).isEmpty();
        return result;
    }
}
