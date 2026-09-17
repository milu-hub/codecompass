package com.codecompass.analyzer;

import java.util.List;

/**
 * 语言中立核心模型的公共约束。包级私有 —— 它是实现细节，不属于对外契约。
 *
 * 两条约束都刻意做进构造器而不是写在文档里，因为它们在运行期不可见：
 * 列表为 null 只会让前端某个地方崩，行号差一位只有人工核对才发现。
 */
final class ModelSupport {

    private ModelSupport() {
    }

    /** null 规范化为空列表；非 null 则复制为不可变，使 record 成为真正的值对象。 */
    static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /**
     * 行号必须是 1-based 闭区间。
     *
     * <p>SCHEMA.md 里的 {@code "startLine": 0} 只是占位符，但看起来极像"从 0 开始"。
     * 真按 0-based 实现的话，T10 的引用跳转会整体偏移一行，而 T10 的验收标准是
     * "引用行号准确率 ≥ 90%" —— 这个错误在编译、单测、功能演示里都看不出来。
     * 挡在构造器上，就把它变成了响亮的失败。
     */
    static void requireValidLineRange(int startLine, int endLine) {
        if (startLine < 1) {
            throw new IllegalArgumentException(
                    "startLine 必须从 1 开始（1-based 闭区间），实际为 " + startLine);
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException(
                    "endLine(" + endLine + ") 不能小于 startLine(" + startLine + ")");
        }
    }
}
