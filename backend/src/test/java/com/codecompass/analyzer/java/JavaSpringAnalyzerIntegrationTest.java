package com.codecompass.analyzer.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import com.codecompass.repo.CloneResult;
import com.codecompass.repo.CodeUnitFileInfo;
import com.codecompass.repo.GitRepositoryCloner;
import com.codecompass.repo.SourceFileScanner;
import com.codecompass.repo.TempWorkspaceManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 验收：贯通 T1 克隆 → T2 扫描 → T3 注册表派发 → T4 分析 的完整链路，在两个黄金样本上真机跑。
 *
 * 需要网络，@Tag("integration")，默认构建排除。
 */
@Tag("integration")
@SpringBootTest
class JavaSpringAnalyzerIntegrationTest {

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
    private TempWorkspaceManager tempWorkspaceManager;

    @Test
    @DisplayName("petclinic：全部类解析成功、framework=spring、行号与真实文件对得上、" +
            "且存在 OwnerController -> OwnerRepository 的边")
    void analyzesPetclinic() {
        withRepository(PETCLINIC, repoDir -> {
            AnalyzeResult result = analyze(repoDir);

            assertThat(result.language()).isEqualTo("java");
            assertThat(result.framework()).isEqualTo("spring");
            assertThat(result.codeUnits()).hasSizeGreaterThan(20);

            assertThat(result.failedFiles())
                    .as("petclinic 是标准 Java 代码，应当一个文件都不失败。"
                            + "若这里非空，多半是语言级别没设对（JavaParser 默认 JAVA_11 解析不了 record）")
                    .isEmpty();

            // T3 契约：1-based 闭区间、列表非 null、框架已填
            assertThat(result.codeUnits()).allSatisfy(unit -> {
                assertThat(unit.startLine()).isGreaterThanOrEqualTo(1);
                assertThat(unit.endLine()).isGreaterThanOrEqualTo(unit.startLine());
                assertThat(unit.language()).isEqualTo("java");
                assertThat(unit.framework()).isEqualTo("spring");
                assertThat(unit.annotations()).isNotNull();
                assertThat(unit.fields()).isNotNull();
            });

            // 引用完整性
            Set<String> unitIds = result.codeUnits().stream()
                    .map(CodeUnitInfo::id).collect(Collectors.toSet());
            assertThat(result.dependencies()).isNotEmpty();
            assertThat(result.dependencies()).allSatisfy(edge -> {
                assertThat(unitIds).contains(edge.fromCodeUnitId());
                assertThat(unitIds).contains(edge.toCodeUnitId());
            });

            CodeUnitInfo ownerController = unitNamed(result, "OwnerController");
            assertThat(ownerController.packageName())
                    .isEqualTo("org.springframework.samples.petclinic.owner");
            assertThat(ownerController.annotations()).contains("@Controller");

            // ★ 行号反查：拿真实文件内容验证 startLine 落在声明范围内。
            // 这是唯一能抓住 0-based 误解的方式 —— 编译与逻辑测试都看不出来。
            //
            // 语义说明：JavaParser 把注解算进类型声明的范围，因此 startLine 指向 @Controller
            // 那一行而不是 class 关键字那一行。这对 T10 的引用展示是好事（能带出注解上下文），
            // 所以断言写成"范围首行是注解或声明本身"，而不是"就是 class 那一行"。
            List<String> lines = readAllLines(repoDir.resolve(ownerController.filePath()));
            assertThat(lines.get(ownerController.startLine() - 1))
                    .as("range 首行应是注解或声明本身。若为空白/import/package，说明行号整体偏了一行")
                    .satisfiesAnyOf(
                            first -> assertThat(first.trim()).startsWith("@"),
                            first -> assertThat(first).contains("class OwnerController"));
            assertThat(lines.get(ownerController.startLine() - 1).trim())
                    .as("OwnerController 带 @Controller，故范围从注解行开始")
                    .startsWith("@");

            int declarationLine = -1;
            for (int i = ownerController.startLine() - 1; i < ownerController.endLine(); i++) {
                if (lines.get(i).contains("class OwnerController")) {
                    declarationLine = i;
                    break;
                }
            }
            assertThat(declarationLine)
                    .as("类声明必须落在 [startLine, endLine] 闭区间内")
                    .isGreaterThanOrEqualTo(0);
            assertThat(lines.get(ownerController.endLine() - 1))
                    .as("endLine 指向类的结束花括号")
                    .contains("}");

            // ★ TASKBOOK T6 的验收标准，在此提前于 T4 验证
            CodeUnitInfo ownerRepository = unitNamed(result, "OwnerRepository");
            assertThat(result.dependencies())
                    .as("OwnerController 持有 OwnerRepository 字段，应当有一条边")
                    .anySatisfy(edge -> {
                        assertThat(edge.fromCodeUnitId()).isEqualTo(ownerController.id());
                        assertThat(edge.toCodeUnitId()).isEqualTo(ownerRepository.id());
                    });
        });
    }

    @Test
    @DisplayName("microservices：8 个模块的类全部解析成功且 framework=spring")
    void analyzesMicroservices() {
        withRepository(MICROSERVICES, repoDir -> {
            AnalyzeResult result = analyze(repoDir);

            assertThat(result.framework()).isEqualTo("spring");
            assertThat(result.failedFiles())
                    .as("多模块样本同样应当零失败")
                    .isEmpty();

            Set<String> modules = result.codeUnits().stream()
                    .map(unit -> unit.filePath().split("/")[0])
                    .collect(Collectors.toSet());
            assertThat(modules).containsExactlyInAnyOrder(
                    "spring-petclinic-admin-server",
                    "spring-petclinic-api-gateway",
                    "spring-petclinic-config-server",
                    "spring-petclinic-customers-service",
                    "spring-petclinic-discovery-server",
                    "spring-petclinic-genai-service",
                    "spring-petclinic-vets-service",
                    "spring-petclinic-visits-service");

            assertThat(result.dependencies()).isNotEmpty();
        });
    }

    // ---------- helpers ----------

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
        return analyzer.analyze(new AnalyzeRequest("integration-repo", repoDir, files));
    }

    private static CodeUnitInfo unitNamed(AnalyzeResult result, String name) {
        return result.codeUnits().stream()
                .filter(unit -> unit.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中找不到类：" + name));
    }

    private static List<String> readAllLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
