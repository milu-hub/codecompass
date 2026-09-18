package com.codecompass.analyzer.java;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.FieldInfo;
import com.codecompass.analyzer.MethodInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心注解分类。
 *
 * <p>分类完全由配置驱动 —— 测试里既验证"配置成什么就认什么"，也验证
 * "配置为空时什么都不认"，后者是防止有人在代码里偷偷硬编码注解名。
 */
class CoreAnnotationClassifierTest {

    private static final String SPRING = "spring";

    private static JavaAnalyzeProperties springProperties() {
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setFrameworkMarkers(Map.of(SPRING, List.of(
                "@SpringBootApplication", "@RestController", "@Controller", "@Service",
                "@Repository", "@Component", "@Configuration", "@Autowired", "@Bean")));

        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("entry", List.of("@SpringBootApplication"));
        roles.put("controller", List.of("@Controller", "@RestController"));
        roles.put("service", List.of("@Service"));
        roles.put("repository", List.of("@Repository"));
        roles.put("component", List.of("@Component", "@Configuration"));
        roles.put("member", List.of("@Autowired", "@Bean"));
        properties.setCoreAnnotations(Map.of(SPRING, roles));
        return properties;
    }

    private static CoreAnnotationClassifier classifier() {
        return new CoreAnnotationClassifier(springProperties());
    }

    // ---------- T24：非框架注解的角色（entity / mapper） ----------

    @Test
    @DisplayName("非框架注解也可作角色：@Entity → entity、@Mapper → mapper")
    void nonFrameworkAnnotationsCanBeRoles() {
        JavaAnalyzeProperties properties = springProperties();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("entity", List.of("@Entity"));
        roles.put("mapper", List.of("@Mapper"));
        properties.setCoreAnnotations(Map.of(SPRING, roles));
        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(properties);

        assertThat(classifier.roleOf(unit("A", SPRING, "@Entity"))).contains("entity");
        assertThat(classifier.roleOf(unit("B", SPRING, "@Mapper"))).contains("mapper");
    }

    @Test
    @DisplayName("role-only 注解不计入配置漂移告警（@Entity/@Mapper 不是框架标记）")
    void roleOnlyAnnotationsAreExcludedFromDriftWarning() {
        JavaAnalyzeProperties properties = springProperties();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        roles.put("entity", List.of("@Entity"));
        roles.put("mapper", List.of("@Mapper"));
        properties.setCoreAnnotations(Map.of(SPRING, roles));
        properties.setRoleOnlyAnnotations(List.of("@Entity", "@Mapper"));

        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(properties);

        assertThat(classifier.annotationsMissingFromFrameworkMarkers()).isEmpty();
    }

    private static CodeUnitInfo unit(String name, String framework, String... annotations) {
        return new CodeUnitInfo("repo-1:f.java#" + name, "repo-1", "f.java", "java", framework,
                "com.example", name, "class", List.of(annotations), List.of(), 1, 10);
    }

    // ---------- 角色判定 ----------

    @Test
    @DisplayName("各角色按配置判定：@Controller / @RestController 都算 controller")
    void resolvesRolesFromConfiguration() {
        CoreAnnotationClassifier classifier = classifier();

        assertThat(classifier.roleOf(unit("A", SPRING, "@Controller"))).contains("controller");
        assertThat(classifier.roleOf(unit("B", SPRING, "@RestController"))).contains("controller");
        assertThat(classifier.roleOf(unit("C", SPRING, "@Service"))).contains("service");
        assertThat(classifier.roleOf(unit("D", SPRING, "@Repository"))).contains("repository");
        assertThat(classifier.roleOf(unit("E", SPRING, "@Component"))).contains("component");
        assertThat(classifier.roleOf(unit("F", SPRING, "@SpringBootApplication"))).contains("entry");
    }

    @Test
    @DisplayName("@Configuration 归为 component —— 实测两个黄金样本共有 7 个纯 @Configuration 类，不认会漏掉配置类")
    void configurationCountsAsComponent() {
        assertThat(classifier().roleOf(unit("Cfg", SPRING, "@Configuration"))).contains("component");
    }

    @Test
    @DisplayName("多个注解时按配置顺序取第一个命中的角色，不要求注解唯一")
    void resolvesRoleWhenMultipleAnnotationsPresent() {
        CoreAnnotationClassifier classifier = classifier();

        // 实测：PetClinicApplication 是 @SpringBootApplication + @ImportRuntimeHints
        assertThat(classifier.roleOf(unit("App", SPRING, "@SpringBootApplication", "@ImportRuntimeHints")))
                .contains("entry");
        // 实测：PetController 是 @Controller + @RequestMapping
        assertThat(classifier.roleOf(unit("PetController", SPRING, "@Controller", "@RequestMapping")))
                .contains("controller");
    }

    @Test
    @DisplayName("无核心注解的类没有角色")
    void plainClassHasNoRole() {
        CoreAnnotationClassifier classifier = classifier();

        assertThat(classifier.roleOf(unit("Plain", SPRING))).isEmpty();
        assertThat(classifier.roleOf(unit("Entity", SPRING, "@Entity", "@Table"))).isEmpty();
    }

    @Test
    @DisplayName("framework 为空的单元没有角色——纯 Java 仓库不套用框架角色")
    void unitWithoutFrameworkHasNoRole() {
        assertThat(classifier().roleOf(unit("A", "", "@Controller"))).isEmpty();
    }

    @Test
    @DisplayName("name 是 Controller 但没有注解时不算 controller——只信注解不信名字")
    void doesNotGuessRoleFromClassName() {
        // 实测：microservices 里名为 VectorStoreController 的类带的是 @Component
        assertThat(classifier().roleOf(unit("VectorStoreController", SPRING))).isEmpty();
        assertThat(classifier().roleOf(unit("VectorStoreController", SPRING, "@Component")))
                .as("名字像 Controller 但注解是 @Component，就该判成 component")
                .contains("component");
    }

    // ---------- 核心注解集合 ----------

    @Test
    @DisplayName("类级核心注解只取交集，非核心注解不混进来")
    void classLevelCoreAnnotationsIntersectWithConfiguration() {
        CoreAnnotationClassifier classifier = classifier();

        assertThat(classifier.classLevelCoreAnnotations(
                unit("A", SPRING, "@Controller", "@RequestMapping", "@Deprecated")))
                .containsExactly("@Controller");
    }

    @Test
    @DisplayName("@Autowired / @Bean 是成员级注解，从方法与字段里找，不在类的注解里")
    void memberLevelAnnotationsComeFromMethodsAndFields() {
        CoreAnnotationClassifier classifier = classifier();
        CodeUnitInfo controller = unit("OwnerController", SPRING, "@Controller");
        MethodInfo beanMethod = new MethodInfo("m-1", controller.id(), "restTemplate",
                "RestTemplate restTemplate()", List.of("@Bean"), 12, 14);
        MethodInfo plainMethod = new MethodInfo("m-2", controller.id(), "go", "void go()",
                List.of(), 16, 18);
        CodeUnitInfo withField = new CodeUnitInfo(controller.id(), "repo-1", "f.java", "java",
                SPRING, "com.example", "OwnerController", "class", List.of("@Controller"),
                List.of(new FieldInfo("repo", "Repo", List.of("@Autowired"))), 1, 20);

        AnalyzeResult result = new AnalyzeResult("repo-1", "java", SPRING,
                List.of(withField), List.of(beanMethod, plainMethod), List.of(), List.of());

        assertThat(classifier.memberLevelCoreAnnotations(result, controller.id()))
                .as("黄金样本里 @Autowired 出现 0 次、@Bean 全在方法上，所以这两个只能从成员上找")
                .containsExactlyInAnyOrder("@Autowired", "@Bean");
        assertThat(classifier.classLevelCoreAnnotations(withField))
                .as("@Autowired 在字段上，不会出现在类级注解里")
                .containsExactly("@Controller");
    }

    // ---------- 配置驱动（防止硬编码） ----------

    @Test
    @DisplayName("配置为空时 @Controller 也不被认作角色——证明没有任何硬编码注解名")
    void emptyConfigurationRecognizesNothing() {
        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(new JavaAnalyzeProperties());

        assertThat(classifier.roleOf(unit("A", SPRING, "@Controller"))).isEmpty();
        assertThat(classifier.classLevelCoreAnnotations(unit("A", SPRING, "@Controller"))).isEmpty();
    }

    @Test
    @DisplayName("自定义框架与自定义角色同样生效，核心层不认语言也不认框架")
    void customFrameworkAndRolesAreHonored() {
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setCoreAnnotations(Map.of("klingon-fw", Map.of("warrior", List.of("@Batleth"))));

        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(properties);

        assertThat(classifier.roleOf(unit("Worf", "klingon-fw", "@Batleth"))).contains("warrior");
        assertThat(classifier.roleOf(unit("Worf", SPRING, "@Controller"))).isEmpty();
    }

    @Test
    @DisplayName("配置里的注解写法宽容：带不带 @、写全限定名都能匹配")
    void annotationNotationIsNormalized() {
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setCoreAnnotations(Map.of(SPRING, Map.of(
                "controller", List.of("Controller", "@org.springframework.stereotype.Controller"))));

        CoreAnnotationClassifier classifier = new CoreAnnotationClassifier(properties);

        assertThat(classifier.roleOf(unit("A", SPRING, "@Controller"))).contains("controller");
        assertThat(classifier.roleOf(unit("B", SPRING, "@RestController"))).isEmpty();
    }

    // ---------- 统计与一致性 ----------

    @Test
    @DisplayName("按角色聚合计数")
    void countsByRole() {
        CoreAnnotationClassifier classifier = classifier();
        AnalyzeResult result = new AnalyzeResult("repo-1", "java", SPRING, List.of(
                unit("C1", SPRING, "@Controller"),
                unit("C2", SPRING, "@RestController"),
                unit("S1", SPRING, "@Service"),
                unit("Plain", SPRING)), List.of(), List.of(), List.of());

        assertThat(classifier.countByRole(result))
                .containsEntry("controller", 2L)
                .containsEntry("service", 1L)
                .doesNotContainKey("repository");
    }

    @Test
    @DisplayName("能报出「配了角色但没配进 framework-markers」的注解，供启动期提示漂移")
    void reportsAnnotationsMissingFromFrameworkMarkers() {
        JavaAnalyzeProperties properties = new JavaAnalyzeProperties();
        properties.setFrameworkMarkers(Map.of(SPRING, List.of("@Controller")));
        properties.setCoreAnnotations(Map.of(SPRING, Map.of(
                "controller", List.of("@Controller"),
                "service", List.of("@Service"))));

        assertThat(new CoreAnnotationClassifier(properties).annotationsMissingFromFrameworkMarkers())
                .as("两份配置各写一份会漂移，这里把漂移显式暴露出来")
                .containsExactly("@Service");
    }

    @Test
    @DisplayName("作为 UnitRoleAnnotator：annotate 输出 id→角色 映射，无角色者不进 Map")
    void annotatesResultWithRoleMap() {
        CoreAnnotationClassifier classifier = classifier();
        CodeUnitInfo controller = unit("OwnerController", SPRING, "@Controller");
        CodeUnitInfo plain = unit("Plain", SPRING);
        AnalyzeResult result = new AnalyzeResult("repo-1", "java", SPRING,
                List.of(controller, plain), List.of(), List.of(), List.of());

        Map<String, String> roles = classifier.annotate(result);

        assertThat(roles).containsEntry(controller.id(), "controller").doesNotContainKey(plain.id());
    }
}
