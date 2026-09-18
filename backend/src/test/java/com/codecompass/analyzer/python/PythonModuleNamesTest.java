package com.codecompass.analyzer.python;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2 验收：模块路径 / 包名 / 限定名 / 单元 id 规则（PYTHON_ANALYZER_PLAN.md §3.3 与 §4.3）。
 *
 * 这些是纯函数契约：id 一旦不稳定，缓存 key 漂移、测试无法复现、点击定位跳错。
 * 这里同时钉死三件事：__init__ 归属包本身、namespace package（无 __init__）照样推导、
 * 同名模块靠相对路径保证 id 唯一（歧义留给 P4 的依赖解析，不靠 id 消解）。
 */
class PythonModuleNamesTest {

    @Test
    @DisplayName("模块路径：深层模块 / 顶层模块 / __init__ 归属包本身")
    void derivesModulePath() {
        assertThat(PythonModuleNames.modulePath("a/b/c.py")).isEqualTo("a.b.c");
        assertThat(PythonModuleNames.modulePath("setup.py")).isEqualTo("setup");
        assertThat(PythonModuleNames.modulePath("a/b/__init__.py")).isEqualTo("a.b");
        assertThat(PythonModuleNames.modulePath("single.py")).isEqualTo("single");
    }

    @Test
    @DisplayName("Windows 反斜杠必须归一化成 /，绝不能漏进模块路径或 id")
    void normalizesWindowsSeparators() {
        assertThat(PythonModuleNames.modulePath("a\\b\\c.py")).isEqualTo("a.b.c");
    }

    @Test
    @DisplayName("namespace package（目录里没有 __init__.py）照样按路径推导")
    void namespacePackageIsDerivedByPathOnly() {
        // 没有任何 __init__.py 的前提下，路径推导结果不变——这是特性，不是待办
        assertThat(PythonModuleNames.modulePath("ns/pkg/mod.py")).isEqualTo("ns.pkg.mod");
    }

    @Test
    @DisplayName("包名 = 模块路径去掉最后一段；顶层模块的包名为空串")
    void derivesPackageName() {
        assertThat(PythonModuleNames.packageName("a.b.c")).isEqualTo("a.b");
        assertThat(PythonModuleNames.packageName("a.b")).isEqualTo("a");
        assertThat(PythonModuleNames.packageName("setup")).isEqualTo("");
    }

    @Test
    @DisplayName("限定名：类 → module.Class；模块级函数 → module.fn；顶层 → 裸名")
    void derivesQualifiedName() {
        assertThat(PythonModuleNames.qualifiedName("a.b", "Point")).isEqualTo("a.b.Point");
        assertThat(PythonModuleNames.qualifiedName("a.b", "parse")).isEqualTo("a.b.parse");
        assertThat(PythonModuleNames.qualifiedName("setup", "main")).isEqualTo("setup.main");
        assertThat(PythonModuleNames.qualifiedName("", "main")).isEqualTo("main");
    }

    @Test
    @DisplayName("单元 id 与 Java 同构：repo:相对路径#限定名；同名模块仍唯一")
    void unitIdIsDeterministicAndUniqueForSameNameModules() {
        String first = PythonModuleNames.unitId("repo", "src1/util.py", "util.helper");
        String second = PythonModuleNames.unitId("repo", "src2/util.py", "util.helper");

        assertThat(first).isEqualTo("repo:src1/util.py#util.helper");
        assertThat(second).isEqualTo("repo:src2/util.py#util.helper");
        assertThat(first).as("同名模块分布在两个目录，靠相对路径保证 id 唯一").isNotEqualTo(second);
        assertThat(first).as("id 内不得出现反斜杠").doesNotContain("\\");
    }

    @Test
    @DisplayName("同一输入必须逐字节一致（可复现是缓存与测试的前提）")
    void isDeterministic() {
        String a = PythonModuleNames.unitId("repo", "a/b/c.py", "a.b.c.Main");
        String b = PythonModuleNames.unitId("repo", "a/b/c.py", "a.b.c.Main");
        assertThat(a).isEqualTo(b).isEqualTo("repo:a/b/c.py#a.b.c.Main");
    }
}
