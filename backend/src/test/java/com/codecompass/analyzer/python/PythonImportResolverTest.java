package com.codecompass.analyzer.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4 验收：import 矩阵逐条钉死（PYTHON_ANALYZER_PLAN.md §3.4）。
 *
 * <p><b>归属粒度</b>：一条 import 只连到**真正用到它的单元**（按 AST 的 NAME 出现判定），
 * 不再是"该文件的所有单元"。理由：旧的文件级归属会把边数放大成
 * |文件内单元| × |目标单元| —— 实测 flask 147 单元产生 512 条边，其中 4 个单元就贡献 160 条，
 * 图成了不可读的毛球（真机见 docs/PROGRESS.md）。
 *
 * <p>断言的边都用 (from 短名, to 短名) 表达，避免把冗长 id 塞进测试。
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
        return new PythonAnalyzer(new PythonAnalyzeProperties()).analyze(
                new AnalyzeRequest("repo", repoRoot, infos));
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
                    def build(self):
                        return Point(), u.helper()
                """);
        write("pkg/models.py", "class Point:\n    pass\n");
        write("pkg/utils.py", "def helper():\n    pass\n");
        write("pkg/views.py", """
                from .models import Point
                from . import utils

                def make():
                    return Point(), utils.helper()
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

                def build():
                    return Point()
                """);
        write("pkg/models.py", "class Point:\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py"));

        assertThat(pairs(result)).containsExactly(new Pair("build", "Point"));
    }

    @Test
    @DisplayName("from pkg import models（子模块目标）与 from pkg.utils import *（星导入）")
    void resolvesSubmoduleAndStarTargets() throws Exception {
        write("app.py", """
                from pkg import models
                from pkg.utils import *

                class App:
                    def build(self):
                        return models.Point()
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

                def clone():
                    return app.App()
                """);

        AnalyzeResult result = analyze(List.of("app.py"));

        assertThat(result.dependencies()).isEmpty();
    }

    // ---------- 归属粒度（本轮新增契约） ----------

    @Test
    @DisplayName("只连真正用到导入名的单元：同文件里没用到它的单元不连边")
    void onlyUnitsThatUseImportedNameGetEdges() throws Exception {
        write("app.py", """
                from pkg.models import Point, Unused

                def uses():
                    return Point()

                def ignores():
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n\n\nclass Unused:\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py"));

        assertThat(pairs(result))
                .as("旧的文件级归属会额外产生 ignores→Point / ignores→Unused / uses→Unused 三条假边")
                .containsExactly(new Pair("uses", "Point"));
    }

    @Test
    @DisplayName("别名按别名判使用：import a.b as x 只看 x，import a.b 只看根名 a")
    void aliasAndRootNameCountAsUsage() throws Exception {
        write("app.py", """
                import pkg.utils as u
                import pkg.models

                def use_alias():
                    return u.helper()

                def use_root():
                    return pkg.models.Point()

                def use_none():
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n");
        write("pkg/utils.py", "def helper():\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py", "pkg/utils.py"));

        assertThat(pairs(result)).containsExactlyInAnyOrder(
                new Pair("use_alias", "helper"),
                new Pair("use_root", "Point"));
    }

    @Test
    @DisplayName("from X import C as D：按别名 D 判使用")
    void fromImportAliasCountsAsUsage() throws Exception {
        write("app.py", """
                from pkg.models import Point as P

                def build():
                    return P()
                """);
        write("pkg/models.py", "class Point:\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py"));

        assertThat(pairs(result)).containsExactly(new Pair("build", "Point"));
    }

    @Test
    @DisplayName("星导入名字不可知：保留文件粒度（唯一保留的粗粒度情形）")
    void starImportKeepsFileGranularity() throws Exception {
        write("app.py", """
                from pkg.utils import *

                def a():
                    pass

                def b():
                    pass
                """);
        write("pkg/utils.py", "def helper():\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/utils.py"));

        assertThat(pairs(result))
                .as("星导入导入哪些名字在语法上不可知，只能让该文件所有单元都连边")
                .containsExactlyInAnyOrder(new Pair("a", "helper"), new Pair("b", "helper"));
    }

    @Test
    @DisplayName("模块级用法不属于任何单元：不连边（已知召回损失，模块不是单元）")
    void moduleLevelUsageProducesNoEdge() throws Exception {
        write("app.py", """
                from pkg.models import Point

                point = Point()


                class App:
                    pass
                """);
        write("pkg/models.py", "class Point:\n    pass\n");

        AnalyzeResult result = analyze(List.of("app.py", "pkg/models.py"));

        assertThat(pairs(result))
                .as("类级图没有'模块'节点；模块级用法无处归属，宁可不连也不fan-out")
                .isEmpty();
    }
}
