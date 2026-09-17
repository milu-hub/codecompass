package com.codecompass.analyzer.java;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一个类型引用解析成候选全限定名，**按优先级排列**。
 *
 * <p>本类只按 Java 的可见性规则给出候选顺序，不判断"哪个候选真的存在于仓库里" ——
 * 那需要整个仓库的单元索引，属于调用方的职责。职责分开后两者都能独立测试。
 *
 * <p><b>顺序本身就是正确性</b>：Java 里显式 import 覆盖同包同名类。顺序反了会把边
 * 连到同包的另一个类上，而"图里存在一条边"这种断言根本发现不了连错。
 *
 * <p>注：设计文档里列的「同文件其它顶层类型」被当前包规则包含了 —— 顶层类型必与
 * 所在文件同包，因此候选相同，无需单列。
 */
public final class JavaNameResolver {

    private final String currentPackage;
    private final Map<String, String> singleTypeImports;
    private final List<String> wildcardImports;

    public JavaNameResolver(String currentPackage,
                            List<String> singleTypeImports,
                            List<String> wildcardImports) {
        this.currentPackage = currentPackage == null ? "" : currentPackage.trim();
        this.singleTypeImports = indexBySimpleName(singleTypeImports);
        this.wildcardImports = wildcardImports == null ? List.of() : List.copyOf(wildcardImports);
    }

    /** 按优先级返回候选全限定名（已去重保序）；无法给出候选时返回空。 */
    public List<String> candidates(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return List.of();
        }
        String name = typeName.trim();
        Set<String> candidates = new LinkedHashSet<>();

        if (name.indexOf('.') >= 0) {
            // 已限定：先认为它本身就是全限定名，再试当前包前缀（内部类/同包限定写法）
            candidates.add(name);
            addCurrentPackageCandidate(candidates, name);
            return List.copyOf(candidates);
        }

        String imported = singleTypeImports.get(name);
        if (imported != null) {
            candidates.add(imported);
        }
        addCurrentPackageCandidate(candidates, name);
        for (String prefix : wildcardImports) {
            if (prefix != null && !prefix.isBlank()) {
                candidates.add(prefix.trim() + "." + name);
            }
        }
        return List.copyOf(candidates);
    }

    /** 默认包下不加前缀 —— 否则会拼出 {@code ".Foo"} 这种畸形名。 */
    private void addCurrentPackageCandidate(Set<String> candidates, String name) {
        if (!currentPackage.isEmpty()) {
            candidates.add(currentPackage + "." + name);
        } else if (name.indexOf('.') < 0) {
            candidates.add(name);
        }
    }

    /** import 的简单名取最后一段，因此 {@code import com.a.Outer.Inner} 也能按 Inner 命中。 */
    private static Map<String, String> indexBySimpleName(List<String> imports) {
        Map<String, String> index = new LinkedHashMap<>();
        if (imports != null) {
            for (String imported : imports) {
                if (imported == null || imported.isBlank()) {
                    continue;
                }
                String fqn = imported.trim();
                int lastDot = fqn.lastIndexOf('.');
                String simpleName = lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
                index.putIfAbsent(simpleName, fqn);
            }
        }
        return Map.copyOf(index);
    }
}
