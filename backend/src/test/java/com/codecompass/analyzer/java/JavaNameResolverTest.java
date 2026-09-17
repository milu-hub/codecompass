package com.codecompass.analyzer.java;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 类型名解析（无符号求解，规则须自己定死）。
 *
 * <p>这里返回的是**按优先级排列的候选全限定名**，由调用方取第一个在仓库索引中存在的。
 * 之所以不在这里直接判定，是因为"哪个候选真的存在"取决于整个仓库的单元索引，
 * 而顺序规则属于本类 —— 职责分开后两件事都能独立测试。
 *
 * <p>顺序本身就是正确性：Java 里显式 import **优先于**同包同名类。顺序反了会把边
 * 连到同包的另一个类上；而"图里存在一条边"这种断言根本发现不了连错。
 */
class JavaNameResolverTest {

    private static JavaNameResolver resolver(String currentPackage,
                                             List<String> singleTypeImports,
                                             List<String> wildcardImports) {
        return new JavaNameResolver(currentPackage, singleTypeImports, wildcardImports);
    }

    @Test
    @DisplayName("已限定的名字：先按原样，再试当前包前缀")
    void qualifiedNameYieldsItselfThenPackagePrefixed() {
        List<String> candidates = resolver("com.cur", List.of(), List.of())
                .candidates("com.a.Foo");

        assertThat(candidates).containsExactly("com.a.Foo", "com.cur.com.a.Foo");
    }

    @Test
    @DisplayName("★ 顺序即正确性：显式 import 优先于同包同名类")
    void singleTypeImportBeatsCurrentPackage() {
        List<String> candidates = resolver("com.cur", List.of("com.other.Foo"), List.of())
                .candidates("Foo");

        assertThat(candidates)
                .as("Java 里显式 import 覆盖同包同名类；顺序反了会把边连到错误的类上")
                .startsWith("com.other.Foo");
        assertThat(candidates).contains("com.cur.Foo");
    }

    @Test
    @DisplayName("无 import 时用当前包")
    void fallsBackToCurrentPackage() {
        List<String> candidates = resolver("com.cur", List.of(), List.of()).candidates("Foo");

        assertThat(candidates).containsExactly("com.cur.Foo");
    }

    @Test
    @DisplayName("单类型 import 按简单名匹配（含嵌套类型的 import）")
    void singleTypeImportMatchesBySimpleName() {
        JavaNameResolver resolver = resolver("com.cur",
                List.of("com.a.Foo", "com.a.Outer.Inner"), List.of());

        assertThat(resolver.candidates("Foo")).startsWith("com.a.Foo");
        assertThat(resolver.candidates("Inner"))
                .as("import com.a.Outer.Inner 的简单名是 Inner")
                .startsWith("com.a.Outer.Inner");
    }

    @Test
    @DisplayName("通配 import 排在当前包之后")
    void wildcardImportComesAfterCurrentPackage() {
        List<String> candidates = resolver("com.cur", List.of(), List.of("com.wild", "com.wild2"))
                .candidates("Foo");

        assertThat(candidates).containsExactly("com.cur.Foo", "com.wild.Foo", "com.wild2.Foo");
    }

    @Test
    @DisplayName("默认包：候选名不带多余的点")
    void defaultPackageProducesCleanCandidate() {
        List<String> candidates = resolver("", List.of(), List.of("com.wild")).candidates("Foo");

        assertThat(candidates)
                .as("当前包为空串时不能拼出 \".Foo\" 这种畸形名")
                .containsExactly("Foo", "com.wild.Foo");
        assertThat(candidates).noneMatch(candidate -> candidate.startsWith("."));
    }

    @Test
    @DisplayName("候选去重且保序")
    void candidatesAreDeduplicated() {
        List<String> candidates = resolver("com.cur",
                List.of("com.cur.Foo"), List.of("com.cur")).candidates("Foo");

        assertThat(candidates).containsExactly("com.cur.Foo");
    }

    @Test
    @DisplayName("空白名字返回空候选，不抛异常")
    void blankNameYieldsNoCandidates() {
        JavaNameResolver resolver = resolver("com.cur", List.of(), List.of());

        assertThat(resolver.candidates(null)).isEmpty();
        assertThat(resolver.candidates("  ")).isEmpty();
    }
}
