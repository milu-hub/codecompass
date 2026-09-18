package com.codecompass.analyzer.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 验收：Python 角色标注（装饰器 / 模块路径约定 / 脚本入口）。
 */
class PythonRoleAnnotatorTest {

    private PythonRoleAnnotator annotator() {
        PythonAnalyzeProperties properties = new PythonAnalyzeProperties();
        properties.setDecoratorRoles(new LinkedHashMap<>(Map.of(
                "controller", List.of("app.route", "router.get", "api_view"),
                "entity", List.of("dataclass"))));
        properties.setModuleRoles(new LinkedHashMap<>(Map.of(
                "entity", List.of("models"),
                "controller", List.of("views"))));
        return new PythonRoleAnnotator(properties);
    }

    private CodeUnitInfo unit(String id, String filePath, String name, String kind, List<String> annotations) {
        return new CodeUnitInfo(id, "repo", filePath, "python", "", filePath.replace('/', '.'), name, kind,
                annotations, List.of(), 1, 2);
    }

    @Test
    @DisplayName("装饰器名命中角色：Flask/FastAPI 路由 → controller，dataclass → entity")
    void classifiesByDecorator() {
        PythonRoleAnnotator annotator = annotator();
        assertThat(annotator.roleOf(unit("u1", "app.py", "index", "function", List.of("app.route"))))
                .contains("controller");
        assertThat(annotator.roleOf(unit("u2", "models.py", "Point", "class", List.of("dataclass"))))
                .contains("entity");
    }

    @Test
    @DisplayName("模块路径末段命中角色：models.py → entity，views.py → controller")
    void classifiesByModuleSuffix() {
        PythonRoleAnnotator annotator = annotator();
        assertThat(annotator.roleOf(unit("u1", "app/models.py", "User", "class", List.of())))
                .contains("entity");
        assertThat(annotator.roleOf(unit("u2", "app/views.py", "home", "function", List.of())))
                .contains("controller");
    }

    @Test
    @DisplayName("模块级函数名 main → entry（脚本入口约定）")
    void classifiesScriptEntry() {
        assertThat(annotator().roleOf(unit("u1", "cli.py", "main", "function", List.of())))
                .contains("entry");
    }

    @Test
    @DisplayName("无任何信号时返回空；annotate 只输出有角色的单元")
    void returnsEmptyWhenNoSignal() {
        PythonRoleAnnotator annotator = annotator();
        CodeUnitInfo plain = unit("u1", "util.py", "helper", "function", List.of());
        assertThat(annotator.roleOf(plain)).isEmpty();

        AnalyzeResult result = new AnalyzeResult("repo", "python", "",
                List.of(plain, unit("u2", "app.py", "main", "function", List.of())),
                List.of(), List.of(), List.of());
        Map<String, String> roles = annotator.annotate(result);
        assertThat(roles).containsOnlyKeys("u2");
        assertThat(roles.get("u2")).isEqualTo("entry");
    }

    @Test
    @DisplayName("language() = python")
    void declaresPythonLanguage() {
        assertThat(annotator().language()).isEqualTo("python");
    }
}
