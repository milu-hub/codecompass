package com.codecompass.analyzer.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 验收：import 矩阵逐条钉死（PYTHON_ANALYZER_PLAN.md §3.4）。
 *
 * 断言的边都用 (from 短名, to 短名) 表达，避免把冗长 id 塞进测试。
 */
class PythonImportResolverTest {

    @TempDir
    Path repoRoot;

    private record Pair(String from, String to) {
    }

    private AnalyzeResult analyze(List<String> files) {
        List<CodeUnitFileInfo> infos = files.stream()
                .map(path -> new CodeUnitFileInfo(path, "", "", "python"))
                .toList();
        return new PythonAnalyzer().analyze(new AnalyzeRequest("repo", repoRoot, infos));
    }

    private void write(String relativePath, String content) throws Exception {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /** 把边换算成 (from 短名, to 短名)。 */
    private static List<Pair> pairs(AnalyzeResult result) {
        // 用 id 的 # 段做短名（#fqn 的最后一个 . 之后是类名/函数名）
        return result.dependencies().stream()
                .map(edge -> new Pair(shortName(edge.fromCodeUnitId()), shortName(edge.toCodeUnitId())))
                .toList();
    }

    private static String shortName(String unitId) {
        String fqn = unitId.substring(unitId.indexOf('#') + 1);
        int lastDot = fqn.lastIndexOf('.');
        return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
    }

    @Test
    @DisplayName("import 矩阵：from 导入、别名 import、相对导入（from . 与 from .x）全部成边")
    void resolvesImportMatrix() throws Exception {
        write("app.py", """
                from pkg.models import Point
                import pkg.utils as u

                class App:
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n");
        write("pkg/utils.py", "def helper():\n    pass\n");
        write("pkg/views.py", """
                from .models import Point
                from . import utils

                def make():
                    return Point()
                """);

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py", "pkg/utils.py", "pkg/views.py"));

        assertThat(result.dependencies()).allSatisfy(edge -> {
            assertThat(edge.kind()).isEqualTo("import");
            assertThat(edge.language()).isEqualTo("python");
        });
        assertThat(pairs(result)).containsExactlyInAnyOrder(
                new Pair("App", "Point"),       // from pkg.models import Point
                new Pair("App", "helper"),      // import pkg.utils as u
                new Pair("make", "Point"),      // from .models import Point
                new Pair("make", "helper"));    // from . import utils
    }

    @Test
    @DisplayName("仓库外 import 丢弃（无幽灵节点），重复 import 去重成一条边")
    void dropsExternalImportsAndDeduplicates() throws Exception {
        write("app.py", """
                import numpy as np
                import numpy
                from pkg.models import Point
                from pkg.models import Point

                class App:
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py"));

        assertThat(pairs(result)).containsExactly(new Pair("App", "Point"));
    }

    @Test
    @DisplayName("from pkg import models（子模块目标）与 from pkg.utils import *（星导入）")
    void resolvesSubmoduleAndStarTargets() throws Exception {
        write("app.py", """
                from pkg import models
                from pkg.utils import *

                class App:
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n");
        write("pkg/utils.py", "def helper():\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py", "pkg/utils.py"));

        // from pkg import models → 子模块 pkg.models 的 Point；from pkg.utils import * → helper
        assertThat(pairs(result)).containsExactlyInAnyOrder(
                new Pair("App", "Point"), new Pair("App", "helper"));
    }

    @Test
    @DisplayName("导入自己所在的模块不产生自环")
    void dropsSelfLoops() throws Exception {
        write("app.py", """
                import app

                class App:
                    pass
                """);

        AnalyzeResult result = analyze(List.of("app.py"));

        assertThat(result.dependencies()).isEmpty();
    }
}
