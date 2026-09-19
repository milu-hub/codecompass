package com.codecompass.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 验收：对真实 spring-petclinic 跑一次完整浅克隆。
 *
 * 需要网络，因此打 @Tag("integration")，默认构建会排除（见 pom 的 surefire 配置）。
 * 单独运行：{@code mvn test -Dsurefire.excludedGroups=}
 */
@Tag("integration")
@SpringBootTest
class GitRepositoryClonerIntegrationTest {

    private static final String PETCLINIC = "https://github.com/spring-projects/spring-petclinic";

    @Autowired
    private GitRepositoryCloner cloner;

    @Autowired
    private TempWorkspaceManager tempWorkspaceManager;

    @Autowired
    private CloneProperties properties;

    @Test
    @DisplayName("真实克隆 spring-petclinic：pom.xml 在、src/main/java 在、工作区在临时根下")
    void clonesSpringPetclinic() throws IOException {
        CloneResult result = cloner.clone(PETCLINIC);
        assertThat(result.success())
                .as("克隆应成功，实际错误：%s", result.errorMessage())
                .isTrue();

        Path repoDir = Path.of(result.localPath());
        try {
            assertThat(repoDir.resolve("pom.xml")).exists();
            assertThat(repoDir.resolve("src/main/java")).isDirectory();
            // P2 引入 Python 整仓语言（source-root '.'）后，sparse-checkout 退化为全量检出，
            // src/test 也会被拉下：多语言产品在克隆完成前无法预知仓库语言，全量是安全兜底。
            // 原「src/test 被稀疏检出排除」断言在多语言配置下不再成立，故移除。

            Path realTempRoot = properties.getTempRoot().toRealPath();
            assertThat(repoDir.toRealPath().startsWith(realTempRoot))
                    .as("localPath 必须落在配置的临时根之下，否则后续删除校验会拒绝")
                    .isTrue();

            long javaFiles;
            try (var paths = Files.walk(repoDir)) {
                javaFiles = paths.filter(path -> path.toString().endsWith(".java")).count();
            }
            assertThat(javaFiles)
                    .as("petclinic 的 src/main/java 下应有数十个类文件")
                    .isGreaterThan(20);

            assertThat(Files.readString(repoDir.resolve("pom.xml"))).contains("spring-petclinic");
        } finally {
            tempWorkspaceManager.delete(repoDir.getParent());
        }
    }

    @Test
    @DisplayName("不存在的仓库：返回明确失败信息而非含糊超时，且不留残留目录")
    void reportsClearFailureForMissingRepository() throws IOException {
        CloneResult result = cloner.clone(
                "https://github.com/spring-projects/definitely-not-a-real-repo-codecompass-xyz");

        assertThat(result.success()).isFalse();
        assertThat(result.localPath()).isNull();
        assertThat(result.errorMessage()).isNotBlank();
        assertThat(result.errorMessage())
                .as("实测 git 对不存在的仓库要 63.7 秒才返回，已越过 60 秒硬超时，"
                        + "所以文案必须点明可能原因，不能只写一句 timeout")
                .containsAnyOf("仓库不存在", "克隆失败");

        Path tempRoot = properties.getTempRoot();
        if (Files.isDirectory(tempRoot)) {
            try (var entries = Files.list(tempRoot)) {
                assertThat(entries.toList())
                        .as("失败的克隆不能留下工作区")
                        .isEmpty();
            }
        }
    }
}
