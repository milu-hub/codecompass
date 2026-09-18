package com.codecompass.analyzer.python;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.UnitRoleAnnotator;

/**
 * P5：Python 角色标注（Django / Flask / FastAPI + 纯脚本约定）。
 *
 * <p>Python 没有注解，角色靠两条信号（都来自配置，不硬编码）：
 * <ol>
 *   <li>装饰器名：Flask/FastAPI 的路由装饰器 → controller；dataclass/attrs → entity；</li>
 *   <li>模块路径末段：{@code models.py} → entity、{@code views.py} → controller（Django 约定）；</li>
 * </ol>
 * 按配置声明顺序取第一个命中者；另外函数名叫 {@code main} 的一律算 entry（脚本入口约定）。
 */
public class PythonRoleAnnotator implements UnitRoleAnnotator {

    private final Map<String, List<String>> decoratorRoles;
    private final Map<String, List<String>> moduleRoles;

    public PythonRoleAnnotator(PythonAnalyzeProperties properties) {
        this.decoratorRoles = properties.getDecoratorRoles();
        this.moduleRoles = properties.getModuleRoles();
    }

    @Override
    public String language() {
        return "python";
    }

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

    /** 单元角色；无匹配返回空。 */
    public java.util.Optional<String> roleOf(CodeUnitInfo unit) {
        if (unit == null) {
            return java.util.Optional.empty();
        }
        // 1) 装饰器名
        for (Map.Entry<String, List<String>> role : decoratorRoles.entrySet()) {
            if (containsAny(unit.annotations(), role.getValue())) {
                return java.util.Optional.of(role.getKey());
            }
        }
        // 2) 模块路径末段
        String moduleSegment = moduleSegment(unit);
        for (Map.Entry<String, List<String>> role : moduleRoles.entrySet()) {
            if (role.getValue().contains(moduleSegment)) {
                return java.util.Optional.of(role.getKey());
            }
        }
        // 3) 脚本入口约定
        if ("function".equals(unit.kind()) && "main".equals(unit.name())) {
            return java.util.Optional.of("entry");
        }
        return java.util.Optional.empty();
    }

    private static String moduleSegment(CodeUnitInfo unit) {
        String module = PythonModuleNames.modulePath(unit.filePath());
        int lastDot = module.lastIndexOf('.');
        return lastDot < 0 ? module : module.substring(lastDot + 1);
    }

    private static boolean containsAny(List<String> annotations, List<String> markers) {
        if (annotations == null || markers == null) {
            return false;
        }
        return markers.stream().anyMatch(annotations::contains);
    }
}
