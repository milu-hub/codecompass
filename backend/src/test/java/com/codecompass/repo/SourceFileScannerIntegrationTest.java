package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 验收：对 T1 真机克隆出来的 spring-petclinic 做扫描。
 *
 * 这是"Java glob 方言"那个坑的最终防线 —— 单模块仓库的源码位于 {@code src/main/java/...}，
 * 没有任何前缀目录，正是 Java {@code PathMatcher} 匹配不到、而 git 能检出的那种布局。
 *
 * 需要网络，@Tag("integration")，默认构建排除。
 */
@Tag("integration")
@SpringBootTest
class SourceFileScannerIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    /**
     * 多模块黄金样本（TASKBOOK §03 第二行）。
     *
     * 与 petclinic 恰好互补：petclinic 是单模块、源码在**根级** {@code src/main/java/...}
     * （零前缀，专门照出 glob 方言的坑）；这个是 8 个模块、**没有**根级源码，
     * 每一处源码都带模块名前缀。
     */
    private static final String MICROSERVICES =
            "https://github.com/spring-petclinic/spring-petclinic-microservices";

    private static final List<String> MICROSERVICES_MODULES = List.of(
            "spring-petclinic-admin-server",
            "spring-petclinic-api-gateway",
            "spring-petclinic-config-server",
            "spring-petclinic-customers-service",
            "spring-petclinic-discovery-server",
            "spring-petclinic-genai-service",
            "spring-petclinic-vets-service",
            "spring-petclinic-visits-service");

    @Autowired
    private GitRepositoryCloner cloner;

    @Autowired
    private SourceFileScanner scanner;

    @Autowired
    private TempWorkspaceManager tempWorkspaceManager;

    @Test
    @DisplayName("真机 petclinic：文件数与磁盘实际一致，包名正确，language 全为 java")
    void scansRealPetclinic() throws IOException {
        CloneResult cloneResult = cloner.clone(PETCLINIC);
        assertThat(cloneResult.success())
                .as("前置克隆应成功，实际错误：%s", cloneResult.errorMessage())
                .isTrue();

        Path repoDir = Path.of(cloneResult.localPath());
        try {
            List<CodeUnitFileInfo> found = scanner.scan(repoDir);

            assertThat(found)
                    .as("单模块仓库若返回空，就是踩了 Java glob 的 **/ 不匹配零层前缀那个坑")
                    .isNotEmpty();

            // 验收：文件数与磁盘实际一致（这里用独立遍历复核，不复用扫描器的推导逻辑）
            long actualJavaFiles = countJavaFilesOnDisk(repoDir);
            assertThat(found).hasSize((int) actualJavaFiles);

            assertThat(found).allSatisfy(info -> {
                assertThat(info.language()).isEqualTo("java");
                assertThat(info.relativePath()).startsWith("src/main/java/").doesNotContain("\\");
                assertThat(info.unitName()).isNotBlank();
                assertThat(info.packageName()).doesNotContain("/");
            });

            // package-info.java 必须被排除
            assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                    .noneMatch(path -> path.endsWith("package-info.java"));

            // 抽查黄金样本里人工标注过的核心类
            assertThat(found).anySatisfy(info -> {
                assertThat(info.unitName()).isEqualTo("OwnerController");
                assertThat(info.packageName()).isEqualTo("org.springframework.samples.petclinic.owner");
            });
            assertThat(found).anySatisfy(info ->
                    assertThat(info.unitName()).isEqualTo("PetClinicApplication"));
        } finally {
            tempWorkspaceManager.delete(repoDir.getParent());
        }
    }

    @Test
    @DisplayName("真机多模块 microservices：8 个模块全部扫到，包名带模块内的完整层级")
    void scansRealMultiModuleRepository() throws IOException {
        CloneResult cloneResult = cloner.clone(MICROSERVICES);
        assertThat(cloneResult.success())
                .as("前置克隆应成功，实际错误：%s", cloneResult.errorMessage())
                .isTrue();

        Path repoDir = Path.of(cloneResult.localPath());
        try {
            List<CodeUnitFileInfo> found = scanner.scan(repoDir);

            // 验收：文件数与磁盘实际一致
            assertThat(found).hasSize((int) countJavaFilesOnDisk(repoDir));

            // 验收：每个模块都扫到，且没有多出预期之外的模块
            Set<String> scannedModules = found.stream()
                    .map(info -> info.relativePath().split("/")[0])
                    .collect(Collectors.toSet());
            assertThat(scannedModules).containsExactlyInAnyOrderElementsOf(MICROSERVICES_MODULES);

            assertThat(found).allSatisfy(info -> {
                assertThat(info.language()).isEqualTo("java");
                assertThat(info.unitName()).isNotBlank();
                // 包名不能带模块名前缀：模块名不是包的一部分
                assertThat(info.packageName()).doesNotContain("spring-petclinic");
            });
            assertThat(found).extracting(CodeUnitFileInfo::relativePath)
                    .allSatisfy(path -> assertThat(path).contains("/src/main/java/"));

            // 抽查一个具体的类，确认包名推导在多模块路径下依然正确
            assertThat(found).anySatisfy(info -> {
                assertThat(info.unitName()).isEqualTo("CustomersServiceApplication");
                assertThat(info.relativePath())
                        .isEqualTo("spring-petclinic-customers-service/src/main/java/"
                                + "org/springframework/samples/petclinic/customers/CustomersServiceApplication.java");
                assertThat(info.packageName())
                        .isEqualTo("org.springframework.samples.petclinic.customers");
            });
        } finally {
            tempWorkspaceManager.delete(repoDir.getParent());
        }
    }

    /** 独立于扫描器实现的口径：磁盘上所有满足源码根与扩展名、且未被排除的文件。 */    private long countJavaFilesOnDisk(Path repoDir) throws IOException {
        try (var paths = Files.walk(repoDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> repoDir.relativize(path).toString()
                            .replace('\\', '/').contains("src/main/java/"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("package-info.java") && !name.equals("module-info.java");
                    })
                    .count();
        }
    }
}
