package com.codecompass.analyzer.java;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.UnitRoleAnnotator;

/**
 * 配置驱动的核心注解分类。
 *
 * <p><b>本类不含任何注解字面量</b> —— 认哪些注解、算作什么角色，全部来自
 * {@link JavaAnalyzeProperties#getCoreAnnotations()}。这不只是"可配置"的形式要求：
 * 它使得"换一套核心注解定义"不需要改代码，也让"配置为空则什么都不认"成为可测试的性质。
 *
 * <p><b>角色判定只看注解，不看类名</b>。实测 microservices 里的
 * {@code VectorStoreController} 带的是 {@code @Component} 而非 {@code @RestController}，
 * 任何按名字猜角色的启发式都会误判。
 *
 * <p><b>类级与成员级注解分属两处</b>：{@code @Controller} 这类在
 * {@link CodeUnitInfo#annotations()}；而 {@code @Autowired} 在字段上、{@code @Bean} 在方法上，
 * 必须去 {@code FieldInfo} / {@code MethodInfo} 找。约定：配置里名为 {@value #MEMBER_ROLE}
 * 的角色用于声明成员级注解。
 *
 * <p>角色按配置声明顺序取第一个命中者，因此决定性的角色（如 entry）应写在前面。
 */
public class CoreAnnotationClassifier implements UnitRoleAnnotator {

    private static final Logger log = LoggerFactory.getLogger(CoreAnnotationClassifier.class);

    /** 保留角色名：声明成员级（字段/方法）注解，而非类级角色。 */
    private static final String MEMBER_ROLE = "member";

    private final Map<String, Map<String, Set<String>>> coreAnnotationsByFramework;
    private final Map<String, Set<String>> frameworkMarkersByFramework;
    private final Set<String> roleOnlyAnnotations;

    public CoreAnnotationClassifier(JavaAnalyzeProperties properties) {
        this.coreAnnotationsByFramework = normalizeCoreAnnotations(properties.getCoreAnnotations());
        this.frameworkMarkersByFramework = normalizeFrameworkMarkers(properties.getFrameworkMarkers());
        this.roleOnlyAnnotations = normalizeAll(properties.getRoleOnlyAnnotations());

        Set<String> missing = annotationsMissingFromFrameworkMarkers();
        if (!missing.isEmpty()) {
            // 两份配置各写一份必然漂移：能识别出角色却判定不出框架，或反之。
            // 不强制失败（语义确实不同，硬合并会让两件事互相绑架），但要让人看见。
            log.warn("以下注解配在 core-annotations 里但不在 framework-markers 里，框架识别可能不生效：{}", missing);
        }
    }

    /** 类级核心注解（配置里声明的那些与单元实际注解的交集）。 */
    public Set<String> classLevelCoreAnnotations(CodeUnitInfo unit) {
        if (unit == null) {
            return Set.of();
        }
        Set<String> declared = allClassLevelAnnotations(unit.framework());
        if (declared.isEmpty() || unit.annotations() == null) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        unit.annotations().stream().filter(declared::contains).forEach(found::add);
        return Set.copyOf(found);
    }

    /** 单元角色；无匹配返回空。按配置声明顺序取第一个命中者。 */
    public Optional<String> roleOf(CodeUnitInfo unit) {
        if (unit == null || unit.annotations() == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, Set<String>> role : rolesOf(unit.framework()).entrySet()) {
            if (MEMBER_ROLE.equals(role.getKey())) {
                continue;
            }
            if (unit.annotations().stream().anyMatch(role.getValue()::contains)) {
                return Optional.of(role.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * 单元内部（字段与方法）声明的核心注解。
     *
     * <p>存在的理由：{@code @Autowired} 与 {@code @Bean} 永远不会出现在类级注解里，
     * 按类级查会把它们的识别率算成 0。
     */
    public Set<String> memberLevelCoreAnnotations(AnalyzeResult result, String codeUnitId) {
        if (result == null || codeUnitId == null) {
            return Set.of();
        }
        String framework = result.codeUnits().stream()
                .filter(unit -> codeUnitId.equals(unit.id()))
                .map(CodeUnitInfo::framework)
                .findFirst()
                .orElse(null);
        Set<String> declared = rolesOf(framework).getOrDefault(MEMBER_ROLE, Set.of());
        if (declared.isEmpty()) {
            return Set.of();
        }

        Set<String> found = new LinkedHashSet<>();
        result.methods().stream()
                .filter(method -> codeUnitId.equals(method.codeUnitId()))
                .forEach(method -> method.annotations().stream()
                        .filter(declared::contains).forEach(found::add));
        result.codeUnits().stream()
                .filter(unit -> codeUnitId.equals(unit.id()))
                .forEach(unit -> unit.fields().stream()
                        .forEach(field -> field.annotations().stream()
                                .filter(declared::contains).forEach(found::add)));
        return Set.copyOf(found);
    }

    /** 按角色聚合计数，供 T12 的覆盖率复核与前端分组使用。 */
    public Map<String, Long> countByRole(AnalyzeResult result) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (result == null) {
            return counts;
        }
        for (CodeUnitInfo unit : result.codeUnits()) {
            roleOf(unit).ifPresent(role -> counts.merge(role, 1L, Long::sum));
        }
        return counts;
    }

    /** 配了角色却没配进 framework-markers 的注解。空集表示两份配置一致。 */
    public Set<String> annotationsMissingFromFrameworkMarkers() {
        Set<String> missing = new LinkedHashSet<>();
        coreAnnotationsByFramework.forEach((framework, roles) -> {
            Set<String> markers = frameworkMarkersByFramework.getOrDefault(framework, Set.of());
            roles.values().stream()
                    .flatMap(Set::stream)
                    .filter(annotation -> !markers.contains(annotation))
                    // 只作角色的注解（@Entity/@Mapper 等非框架注解）不算配置漂移
                    .filter(annotation -> !roleOnlyAnnotations.contains(annotation))
                    .forEach(missing::add);
        });
        return missing;
    }

    /** 实现 {@link UnitRoleAnnotator}：产出 id → 角色 映射，供语言中立的编排层使用。 */
    @Override
    public Map<String, String> annotate(AnalyzeResult result) {
        Map<String, String> roles = new LinkedHashMap<>();
        if (result == null) {
            return roles;
        }
        for (CodeUnitInfo unit : result.codeUnits()) {
            roleOf(unit).ifPresent(role -> roles.put(unit.id(), role));
        }
        return Map.copyOf(roles);
    }

    // ---------- 内部 ----------

    private Set<String> allClassLevelAnnotations(String framework) {
        Set<String> all = new LinkedHashSet<>();
        rolesOf(framework).forEach((role, annotations) -> {
            if (!MEMBER_ROLE.equals(role)) {
                all.addAll(annotations);
            }
        });
        return all;
    }

    private Map<String, Set<String>> rolesOf(String framework) {
        if (framework == null || framework.isBlank()) {
            return Map.of();
        }
        return coreAnnotationsByFramework.getOrDefault(framework, Map.of());
    }

    private static Map<String, Map<String, Set<String>>> normalizeCoreAnnotations(
            Map<String, Map<String, List<String>>> configured) {
        Map<String, Map<String, Set<String>>> normalized = new LinkedHashMap<>();
        if (configured == null) {
            return normalized;
        }
        configured.forEach((framework, roles) -> {
            Map<String, Set<String>> byRole = new LinkedHashMap<>();
            if (roles != null) {
                roles.forEach((role, annotations) -> byRole.put(role, normalizeAll(annotations)));
            }
            normalized.put(framework, byRole);
        });
        return normalized;
    }

    private static Map<String, Set<String>> normalizeFrameworkMarkers(Map<String, List<String>> configured) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        if (configured != null) {
            configured.forEach((framework, markers) -> normalized.put(framework, normalizeAll(markers)));
        }
        return normalized;
    }

    private static Set<String> normalizeAll(List<String> annotations) {
        Set<String> normalized = new LinkedHashSet<>();
        if (annotations != null) {
            annotations.stream().map(CoreAnnotationClassifier::normalize)
                    .filter(value -> !value.isEmpty())
                    .forEach(normalized::add);
        }
        return normalized;
    }

    /**
     * 配置里注解写法宽容：{@code Controller} / {@code @Controller} /
     * {@code @org.springframework.stereotype.Controller} 都规范成 {@code @Controller}，
     * 与 SCHEMA.md 的写法以及分析器产出的形式一致。
     */
    private static String normalize(String annotation) {
        String value = annotation == null ? "" : annotation.trim();
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        int lastDot = value.lastIndexOf('.');
        if (lastDot >= 0) {
            value = value.substring(lastDot + 1);
        }
        return value.isEmpty() ? "" : "@" + value;
    }
}
