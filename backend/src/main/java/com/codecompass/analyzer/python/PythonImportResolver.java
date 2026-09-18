package com.codecompass.analyzer.python;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.xpath.XPath;

import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.python.parser.PythonLexer;
import com.codecompass.analyzer.python.parser.PythonParser;
import com.codecompass.analyzer.python.parser.PythonParser.Dotted_nameContext;
import com.codecompass.analyzer.python.parser.PythonParser.File_inputContext;
import com.codecompass.analyzer.python.parser.PythonParser.Import_fromContext;
import com.codecompass.analyzer.python.parser.PythonParser.Import_stmtContext;

/**
 * P4：import → 依赖边（PYTHON_ANALYZER_PLAN.md §3.4 那张规则表）。
 *
 * <p>两段式：{@link #collect} 把语法转成**归一化描述**（相对导入折算成绝对模块、别名剥掉），
 * {@link #resolve} 用纯字符串索引把描述落成边。resolve 不接触语法，所以每条规则都能
 * 用一个用例独立钉死。
 *
 * <p>粒度说明（P4 的显式简化）：一条 import 属于**整个文件**，因此连边起点是该文件里的
 * 全部单元；"import X" / "from X import *" 连到模块 X 的全部单元（类级图没有"模块"节点，
 * 只能落到它的单元上）。仓库外的目标（第三方库）在索引里找不到，自然丢弃。
 */
final class PythonImportResolver {

    private static final String KIND_IMPORT = "import";

    /** 一条 import 的归一化描述。targetModule 已是绝对模块路径；names 是 from 导入的名字；star 是 from X import *。 */
    record PythonImport(String targetModule, List<String> names, boolean star) {
        PythonImport {
            names = List.copyOf(names);
        }
    }

    private record Pair(String from, String to) {
    }

    /** 收集一棵树里所有 import（含嵌套在类/函数里的），相对导入按当前模块折算成绝对。 */
    List<PythonImport> collect(File_inputContext root, CommonTokenStream tokens, String modulePath) {
        List<PythonImport> imports = new ArrayList<>();
        PythonParser parser = new PythonParser(tokens);
        for (var node : XPath.findAll(root, "//import_stmt", parser)) {
            Import_stmtContext statement = (Import_stmtContext) node;
            if (statement.import_name() != null) {
                for (var dottedAsName : statement.import_name().dotted_as_names().dotted_as_name()) {
                    imports.add(new PythonImport(dottedAsName.dotted_name().getText(), List.of(), false));
                }
            } else {
                PythonImport fromImport = fromImport(statement.import_from(), tokens, modulePath);
                if (fromImport != null) {
                    imports.add(fromImport);
                }
            }
        }
        return imports;
    }

    /**
     * 把全部 import 描述落成边。索引只用 modulePath / 全限定名两类字符串。
     * 去重到 (from,to) 对、丢自环、按 id 排序（与 Java 侧同约定）。
     */
    List<DependencyEdge> resolve(String repositoryId, List<CodeUnitInfo> units,
                                 Map<String, List<String>> unitIdsByFile,
                                 Map<String, List<PythonImport>> importsByFile) {
        Map<String, List<String>> unitsByModule = new LinkedHashMap<>();
        Map<String, String> unitIdByQualifiedName = new HashMap<>();
        for (CodeUnitInfo unit : units) {
            String module = PythonModuleNames.modulePath(unit.filePath());
            unitsByModule.computeIfAbsent(module, k -> new ArrayList<>()).add(unit.id());
            unitIdByQualifiedName.putIfAbsent(
                    PythonModuleNames.qualifiedName(module, unit.name()), unit.id());
        }

        Map<Pair, String> kindByPair = new LinkedHashMap<>();
        for (CodeUnitInfo unit : units) {
            List<String> fromIds = unitIdsByFile.get(unit.filePath());
            if (fromIds == null || fromIds.isEmpty()) {
                continue;
            }
            for (PythonImport imp : importsByFile.getOrDefault(unit.filePath(), List.of())) {
                for (String from : fromIds) {
                    for (String target : targetsOf(imp, unitsByModule, unitIdByQualifiedName)) {
                        if (from.equals(target)) {
                            continue;   // 自环是依赖图里的噪声
                        }
                        kindByPair.putIfAbsent(new Pair(from, target), KIND_IMPORT);
                    }
                }
            }
        }

        List<DependencyEdge> edges = new ArrayList<>();
        for (Map.Entry<Pair, String> entry : kindByPair.entrySet()) {
            Pair pair = entry.getKey();
            edges.add(new DependencyEdge(pair.from() + "->" + pair.to(),
                    repositoryId, pair.from(), pair.to(), entry.getValue(), "python"));
        }
        edges.sort(java.util.Comparator.comparing(DependencyEdge::id));
        return edges;
    }

    private List<String> targetsOf(PythonImport imp, Map<String, List<String>> unitsByModule,
                                   Map<String, String> unitIdByQualifiedName) {
        if (imp.star() || imp.names().isEmpty()) {
            // import X / from X import * → 模块 X 的全部单元
            return unitsByModule.getOrDefault(imp.targetModule(), List.of());
        }
        List<String> targets = new ArrayList<>();
        for (String name : imp.names()) {
            String qualified = imp.targetModule().isEmpty()
                    ? name : imp.targetModule() + "." + name;
            String unitId = unitIdByQualifiedName.get(qualified);
            if (unitId != null) {
                targets.add(unitId);          // 单元（类/模块级函数）
            } else {
                targets.addAll(unitsByModule.getOrDefault(qualified, List.of()));  // 子模块
            }
        }
        return targets;
    }

    private PythonImport fromImport(Import_fromContext ctx, CommonTokenStream tokens, String modulePath) {
        int level = relativeLevel(ctx, tokens);
        String base = resolveBase(ctx.dotted_name(), level, modulePath);
        if (base == null) {
            return null;   // 相对导入超出包根，无法归一化
        }
        var targets = ctx.import_from_targets();
        if (targets == null) {
            return null;
        }
        if (targets.import_from_as_names() == null) {
            return new PythonImport(base, List.of(), true);   // from X import *
        }
        List<String> names = new ArrayList<>();
        for (var importFromAsName : targets.import_from_as_names().import_from_as_name()) {
            // import_from_as_name: name ('as' name)? —— 两个 name，name(0) 才是导入名，name(1) 是别名
            names.add(importFromAsName.name(0).getText());
        }
        return new PythonImport(base, names, false);
    }

    /** 相对导入的层级：数 import_from 里的前导 '.'（DOT 计 1，ELLIPSIS '...' 计 3）。
     *  from 与点号之间隔着 HIDDEN 的 WS token，必须跳过，否则第一个就 break 层级恒为 0。 */
    private int relativeLevel(Import_fromContext ctx, CommonTokenStream tokens) {
        int level = 0;
        for (int i = ctx.getStart().getTokenIndex() + 1; i <= ctx.getStop().getTokenIndex(); i++) {
            Token token = tokens.get(i);
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();
            if (type == PythonLexer.DOT) {
                level++;
            } else if (type == PythonLexer.ELLIPSIS) {
                level += 3;
            } else {
                break;   // 遇到 dotted_name 或 import 关键字即止
            }
        }
        return level;
    }

    /** 把 from 的模块折算成绝对模块路径；相对层级超过包根时返回 null（该 import 丢弃）。 */
    private String resolveBase(Dotted_nameContext dotted, int level, String modulePath) {
        if (level == 0) {
            return dotted == null ? "" : dotted.getText();
        }
        String parent = modulePath;
        for (int i = 0; i < level; i++) {
            parent = PythonModuleNames.packageName(parent);
            if (parent.isEmpty()) {
                return null;
            }
        }
        if (dotted == null) {
            return parent;
        }
        return parent.isEmpty() ? dotted.getText() : parent + "." + dotted.getText();
    }
}
