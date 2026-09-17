package com.codecompass.analyzer;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 语言中立核心模型的契约。
 *
 * 这些断言看起来琐碎，但它们守的是两条**不会靠编译或功能测试暴露**的问题：
 *
 * <ol>
 *   <li><b>列表字段永不返回 null</b>：否则前端、Mermaid 渲染、引用校验到处都要判空。
 *       把它做进 record 的紧凑构造器，契约就从"文档承诺"变成"结构事实"。</li>
 *   <li><b>行号必须是 1-based 闭区间</b>：这是唯一会被最终用户直接看到（T10 引用跳转）、
 *       却无法靠编译或逻辑测试自证的字段。SCHEMA.md 里的 {@code "startLine": 0} 只是占位符，
 *       但看起来极像"从 0 开始"。若真按 0-based 实现，T10 的行号准确率验收会整体偏一行。
 *       在构造器上挡住非法行号，能把这种不可见的 off-by-one 变成响亮的失败。</li>
 * </ol>
 */
class AnalyzerModelContractTest {

    private static CodeUnitInfo unit(int startLine, int endLine) {
        return new CodeUnitInfo("unit-1", "repo-1", "src/main/java/com/example/A.java",
                "java", "spring", "com.example", "A", "class",
                List.of("@Service"), List.of(new FieldInfo("dep", "B", List.of())),
                startLine, endLine);
    }

    @Nested
    @DisplayName("列表字段的 null 规范化与不可变性")
    class ListContracts {

        @Test
        @DisplayName("null 列表一律规范化为空列表，而不是原样保留")
        void nullListsBecomeEmpty() {
            CodeUnitInfo info = new CodeUnitInfo("unit-1", "repo-1", "f.java", "java", "",
                    "pkg", "A", "class", null, null, 1, 2);
            MethodInfo method = new MethodInfo("m-1", "unit-1", "run", "void run()", null, 1, 2);
            AnalyzeResult result = new AnalyzeResult("repo-1", "java", "", null, null, null, null);

            assertThat(info.annotations()).isEmpty();
            assertThat(info.fields()).isEmpty();
            assertThat(method.annotations()).isEmpty();
            assertThat(result.codeUnits()).isEmpty();
            assertThat(result.methods()).isEmpty();
            assertThat(result.dependencies()).isEmpty();
            assertThat(result.failedFiles()).isEmpty();
        }

        @Test
        @DisplayName("传入可变列表也被复制成不可变，record 才是真正的值对象")
        void listsAreDefensivelyCopied() {
            List<String> mutable = new ArrayList<>(List.of("@Service"));

            CodeUnitInfo info = new CodeUnitInfo("unit-1", "repo-1", "f.java", "java", "",
                    "pkg", "A", "class", mutable, List.of(), 1, 2);

            mutable.add("@Component");
            assertThat(info.annotations())
                    .as("外部改动不得影响已构造的值对象")
                    .containsExactly("@Service");
            assertThatThrownBy(() -> info.annotations().add("@Component"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("列表中的 null 元素被拒绝，避免脏数据流到前端")
        void nullElementsAreRejected() {
            List<String> withNull = new ArrayList<>();
            withNull.add("@Service");
            withNull.add(null);

            assertThatThrownBy(() -> new CodeUnitInfo("unit-1", "repo-1", "f.java", "java", "",
                    "pkg", "A", "class", withNull, List.of(), 1, 2))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("FieldInfo 的注解列表同样规范化")
        void fieldInfoNormalizesAnnotations() {
            assertThat(new FieldInfo("dep", "B", null).annotations()).isEmpty();
            assertThat(new FieldInfo("dep", "B", List.of("@Autowired")).annotations())
                    .containsExactly("@Autowired");
        }
    }

    @Nested
    @DisplayName("行号守卫：1-based 闭区间")
    class LineNumberContracts {

        @Test
        @DisplayName("startLine 为 0 被拒绝——这正是 0-based 误解会产生的值")
        void rejectsZeroStartLine() {
            assertThatThrownBy(() -> unit(0, 10))
                    .as("行号从 1 开始；0 意味着实现者按 0-based 处理了，必须当场失败")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startLine");
        }

        @Test
        @DisplayName("负数行号被拒绝")
        void rejectsNegativeStartLine() {
            assertThatThrownBy(() -> unit(-1, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("endLine 小于 startLine 被拒绝")
        void rejectsEndBeforeStart() {
            assertThatThrownBy(() -> unit(20, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("endLine");
        }

        @Test
        @DisplayName("单行单元合法（startLine == endLine）")
        void acceptsSingleLineUnit() {
            assertThat(unit(7, 7).startLine()).isEqualTo(7);
        }

        @Test
        @DisplayName("MethodInfo 的行号受同样的守卫")
        void methodInfoHasSameLineGuards() {
            assertThatThrownBy(() -> new MethodInfo("m-1", "unit-1", "run", "void run()",
                    List.of(), 0, 3))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MethodInfo("m-1", "unit-1", "run", "void run()",
                    List.of(), 5, 3))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("完整实例可直接构造并读取各字段")
    void buildsCompleteInstance() {
        CodeUnitInfo info = unit(10, 42);

        assertThat(info.id()).isEqualTo("unit-1");
        assertThat(info.repositoryId()).isEqualTo("repo-1");
        assertThat(info.filePath()).isEqualTo("src/main/java/com/example/A.java");
        assertThat(info.language()).isEqualTo("java");
        assertThat(info.framework()).isEqualTo("spring");
        assertThat(info.kind()).isEqualTo("class");
        assertThat(info.annotations()).containsExactly("@Service");
        assertThat(info.fields()).hasSize(1);
        assertThat(info.startLine()).isEqualTo(10);
        assertThat(info.endLine()).isEqualTo(42);
    }

    @Test
    @DisplayName("DependencyEdge.kind 是开放取值：任意语言自定义的边类型都能表达")
    void dependencyKindIsOpenEnded() {
        DependencyEdge edge = new DependencyEdge("e-1", "repo-1", "unit-1", "unit-2",
                "klingon-honor-bond", "klingon");

        assertThat(edge.kind()).isEqualTo("klingon-honor-bond");
    }

    @Test
    @DisplayName("CodeUnitInfo.kind 是开放取值：无需改核心即可表达新语言的单元类型")
    void codeUnitKindIsOpenEnded() {
        CodeUnitInfo info = new CodeUnitInfo("unit-1", "repo-1", "m.py", "python", "",
                "pkg", "m", "module", List.of(), List.of(), 1, 5);

        assertThat(info.kind()).isEqualTo("module");
    }
}
