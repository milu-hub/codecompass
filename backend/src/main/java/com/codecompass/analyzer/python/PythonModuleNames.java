package com.codecompass.analyzer.python;

import java.util.ArrayList;
import java.util.List;

/**
 * P2：Python 的模块路径 / 包名 / 限定名 / 单元 id 规则（纯函数，PYTHON_ANALYZER_PLAN.md §3.3）。
 *
 * <p><b>id 与 Java 同构</b>：{@code repositoryId + ":" + relativePath + "#" + 限定名} ——
 * 结构一致意味着业务层、缓存 key（T11）、点击定位对两门语言不做任何区分。
 *
 * <p><b>为什么这些必须写成纯函数并钉死</b>（计划 §4.3）：id 一旦不稳定，
 * 缓存 key 漂移、测试无法逐字节复现、点击定位跳错，三件事连坐。
 * 模块路径是**路径推导**，不是从代码里读出来的 —— Python 没有 package 声明；
 * 没有 {@code __init__.py} 的目录（namespace package）照样推导，这是特性而不是待办。
 */
public final class PythonModuleNames {

    private PythonModuleNames() {
    }

    /**
     * 文件相对路径 → 模块路径。
     * <ul>
     * <li>{@code a/b/c.py} → {@code a.b.c}</li>
     * <li>{@code setup.py} → {@code setup}（顶层模块合法）</li>
     * <li>{@code a/b/__init__.py} → {@code a.b}（__init__ 归属包本身）</li>
     * </ul>
     * 分隔符统一按 {@code /} 处理（扫描器已归一，这里再防御一次，避免 Windows 反斜杠漏进 id）。
     */
    public static String modulePath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        String[] segments = normalized.split("/");
        String fileName = segments.length == 0 ? "" : segments[segments.length - 1];
        if (fileName.isEmpty()) {
            return "";
        }
        String stem = fileName.endsWith(".py")
                ? fileName.substring(0, fileName.length() - 3)
                : fileName;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (!segment.isEmpty()) {
                parts.add(segment);
            }
        }
        if (!"__init__".equals(stem) && !stem.isEmpty()) {
            parts.add(stem);
        }
        return String.join(".", parts);
    }

    /** 模块路径的父包：{@code a.b.c} → {@code a.b}；顶层模块 → {@code ""}。 */
    public static String packageName(String modulePath) {
        if (modulePath == null || modulePath.isBlank()) {
            return "";
        }
        int lastDot = modulePath.lastIndexOf('.');
        return lastDot < 0 ? "" : modulePath.substring(0, lastDot);
    }

    /** 模块内定义的限定名：类 → {@code module.ClassName}；模块级函数 → {@code module.fn}。 */
    public static String qualifiedName(String modulePath, String name) {
        String module = modulePath == null ? "" : modulePath;
        String simple = name == null ? "" : name;
        return module.isBlank() ? simple : module + "." + simple;
    }

    /**
     * 单元 id：与 Java 完全同构（{@code repo:相对路径#限定名}）。
     * 同名模块分布在两个目录（如 {@code src1/util.py} 与 {@code src2/util.py}）时，
     * 相对路径不同，id 依然唯一 —— 模块名歧义是 P4 依赖解析要处理的事，不该靠 id 去消解。
     */
    public static String unitId(String repositoryId, String relativePath, String qualifiedName) {
        return repositoryId + ":" + relativePath + "#" + qualifiedName;
    }
}
