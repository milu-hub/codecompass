package com.codecompass.analyzer.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 要求 1 的直接验证：8 个核心注解全部能识别。
 *
 * <p>为什么不能只靠黄金样本：实测两个黄金样本里 {@code @Autowired} 与 {@code @Repository}
 * 的出现次数**都是 0** —— petclinic 与 microservices 都用构造器注入且不加 {@code @Autowired}
 * （Spring 4.3 起单构造器可省略），仓储则是 Spring Data 接口、不带注解。
 * 所以这两个注解的识别能力只能用合成用例验证。
 *
 * <p>刻意用 {@link SpringBootTest} 而不是自己 new 一份配置：这样读的是
 * {@code application.yml} 里真实生效的注解列表。在测试里抄一份配置，两边迟早会漂移，
 * 而这类漂移恰恰是"配置驱动"最容易失效的地方。
 */
@SpringBootTest
class SpringCoreAnnotationTest {

    @Autowired
    private JavaSpringAnalyzer analyzer;

    @Autowired
    private CoreAnnotationClassifier classifier;

    @TempDir
    Path repoRoot;

    @Test
    @DisplayName("8 个核心注解全部识别：6 个类级 + 2 个成员级")
    void identifiesAllEightCoreAnnotations() throws IOException {
        writeAllEightAnnotationSources();

        AnalyzeResult result = analyzeAll();

        // 类级 6 个
        assertThat(classifier.roleOf(unitNamed(result, "DemoApplication"))).contains("entry");
        assertThat(classifier.roleOf(unitNamed(result, "OwnerController"))).contains("controller");
        assertThat(classifier.roleOf(unitNamed(result, "OwnerApi"))).contains("controller");
        assertThat(classifier.roleOf(unitNamed(result, "OwnerService"))).contains("service");
        assertThat(classifier.roleOf(unitNamed(result, "OwnerRepository"))).contains("repository");
        assertThat(classifier.roleOf(unitNamed(result, "OwnerFormatter"))).contains("component");

        // 成员级 2 个：必须去 FieldInfo / MethodInfo 找，类级注解里没有它们
        CodeUnitInfo controller = unitNamed(result, "OwnerController");
        CodeUnitInfo config = unitNamed(result, "OwnerConfiguration");
        assertThat(classifier.classLevelCoreAnnotations(controller))
                .as("@Autowired 在字段上，绝不该出现在类级注解里")
                .doesNotContain("@Autowired");
        assertThat(classifier.memberLevelCoreAnnotations(result, controller.id()))
                .contains("@Autowired");
        assertThat(classifier.memberLevelCoreAnnotations(result, config.id()))
                .contains("@Bean");
    }

    @Test
    @DisplayName("类名像 Controller 但没有注解时不给角色——只信注解")
    void doesNotInferRoleFromClassName() throws IOException {
        write("src/main/java/com/demo/NotAnnotatedController.java", """
                package com.demo;
                public class NotAnnotatedController {
                }
                """);

        AnalyzeResult result = analyzeAll();

        assertThat(classifier.roleOf(unitNamed(result, "NotAnnotatedController")))
                .as("按类名猜角色是这类实现最典型的错误")
                .isEmpty();
    }

    @Test
    @DisplayName("按角色计数：@Configuration 也归 component，所以 component 是 2 而不是 1")
    void countsRolesAcrossTheSample() throws IOException {
        writeAllEightAnnotationSources();

        AnalyzeResult result = analyzeAll();

        assertThat(classifier.roleOf(unitNamed(result, "OwnerConfiguration")))
                .as("@Configuration 归为 component —— 实测两个黄金样本共有 6 个纯 @Configuration 类，"
                        + "不认会漏掉配置类")
                .contains("component");
        assertThat(classifier.countByRole(result))
                .containsEntry("controller", 2L)
                .containsEntry("service", 1L)
                .containsEntry("repository", 1L)
                .containsEntry("entry", 1L)
                .containsEntry("component", 2L);
    }

    // ---------- helpers ----------

    private void writeAllEightAnnotationSources() throws IOException {
        write("src/main/java/com/demo/DemoApplication.java", """
                package com.demo;

                @SpringBootApplication
                public class DemoApplication {
                }
                """);
        write("src/main/java/com/demo/OwnerController.java", """
                package com.demo;

                @Controller
                public class OwnerController {
                    @Autowired
                    private OwnerRepository ownerRepository;

                    public OwnerController(OwnerRepository ownerRepository) {
                        this.ownerRepository = ownerRepository;
                    }
                }
                """);
        write("src/main/java/com/demo/OwnerApi.java", """
                package com.demo;

                @RestController
                public class OwnerApi {
                }
                """);
        write("src/main/java/com/demo/OwnerService.java", """
                package com.demo;

                @Service
                public class OwnerService {
                }
                """);
        write("src/main/java/com/demo/OwnerRepository.java", """
                package com.demo;

                @Repository
                public class OwnerRepository {
                }
                """);
        write("src/main/java/com/demo/OwnerFormatter.java", """
                package com.demo;

                @Component
                public class OwnerFormatter {
                }
                """);
        write("src/main/java/com/demo/OwnerConfiguration.java", """
                package com.demo;

                @Configuration
                public class OwnerConfiguration {
                    @Bean
                    public String ownerName() {
                        return "owner";
                    }
                }
                """);
    }

    private void write(String relativePath, String source) throws IOException {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
    }

    private AnalyzeResult analyzeAll() {
        List<CodeUnitFileInfo> files = List.of(
                new CodeUnitFileInfo("src/main/java/com/demo/DemoApplication.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerController.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerApi.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerService.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerRepository.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerFormatter.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/OwnerConfiguration.java", "", "", "java"),
                new CodeUnitFileInfo("src/main/java/com/demo/NotAnnotatedController.java", "", "", "java"));
        return analyzer.analyze(new AnalyzeRequest("repo-1", repoRoot, files));
    }

    private static CodeUnitInfo unitNamed(AnalyzeResult result, String name) {
        return result.codeUnits().stream()
                .filter(unit -> unit.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("结果中找不到类：" + name
                        + "，实际：" + result.codeUnits().stream().map(CodeUnitInfo::name).toList()
                        + "，失败文件：" + result.failedFiles()));
    }
}
