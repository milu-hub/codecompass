package com.codecompass.analyzer.python;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p><b>归属粒度（本轮修正）</b>：一条 import <b>只连到真正用到它的单元</b> —— 判据是
 * 该单元的**行区间内出现过这个 import 绑定的标识符**（NAME token，不含注释与字符串）。
 * 旧实现是"该文件的所有单元都连"，会把边数放大成 |文件内单元| × |目标单元|；
 * 真机实测 flask 147 单元产生 512 条边，其中 4 个单元就贡献 160 条，图成了不可读的毛球。
 * 现在宁可漏一条边，也不要几十条假边（与"行号宁可少不可错"同口径）。
 *
 * <p>两处**保留的粗粒度**，都是有意的：
 * <ul>
 *   <li>{@code from X import *}：导入哪些名字在语法上不可知 → 该文件的单元都连边；</li>
 *   <li>目标侧：类级图没有"模块"节点，{@code import X} / 解析不到名字时只能落到该模块的单元上。</li>
 * </ul>
 *
 * <p><b>已知召回损失</b>：模块级（不在任何类/函数内）的用法不属于任何单元，因此不连边 ——
 * 这是"模块不是单元"这一决策的必然代价，不是 bug。
 *
 * <p>仓库外的目标（第三方库）在索引里找不到，自然丢弃。
 */
final class PythonImportResolver {

    private static final String KIND_IMPORT = "import";

    /** 代码里用到某个 import 时会出现的一个标识符（带行号，用于按单元区间归属）。 */
    record NameRef(int line, String name) {
    }

    /**
     * 一条 import 的归一化描述。
     *
     * @param targetModule 目标模块（已是绝对模块路径）
     * @param names        from 导入的名字（用于解析目标单元）
     * @param star         {@code from X import *}
     * @param usageNames   代码里用到它时出现的标识符：{@code import a.b} → {@code a}；
     *                     {@code import a.b as x} → {@code x}；{@code from m import C as D} → {@code D}。
     *                     star 时为空（名字不可知）
     */
    record PythonImport(String targetModule, List<String> names, boolean star, List<String> usageNames) {
        PythonImport {
            names = List.copyOf(names);
            usageNames = List.copyOf(usageNames);
        }
    }

    /** 一个文件的扫描结果：import 描述 + 全文件 NAME 出现位置。 */
    record FileScan(List<PythonImport> imports, List<NameRef> names) {
        FileScan {
            imports = List.copyOf(imports);
            names = List.copyOf(names);
        }
    }

    private record Pair(String from, String to) {
    }

    /**
     * 收集一棵树里所有 import（含嵌套在类/函数里的），相对导入按当前模块折算成绝对；
     * 同时记下全文件的 NAME 出现位置，供 {@link #resolve} 按单元行区间判"用没用"。
     */
    FileScan collect(File_inputContext root, CommonTokenStream tokens, String modulePath) {
        List<PythonImport> imports = new ArrayList<>();
        PythonParser parser = new PythonParser(tokens);
        for (var node : XPath.findAll(root, "//import_stmt", parser)) {
            Import_stmtContext statement = (Import_stmtContext) node;
            if (statement.import_name() != null) {
                for (var dottedAsName : statement.import_name().dotted_as_names().dotted_as_name()) {
                    String module = dottedAsName.dotted_name().getText();
                    // 别名存在时用别名，否则用模块路径的根名（import a.b → 代码里出现的是 a）
                    String alias = dottedAsName.name() == null ? null : dottedAsName.name().getText();
                    String usage = alias != null ? alias : rootName(module);
                    imports.add(new PythonImport(module, List.of(), false, List.of(usage)));
                }
            } else {
                PythonImport fromImport = fromImport(statement.import_from(), tokens, modulePath);
                if (fromImport != null) {
                    imports.add(fromImport);
                }
            }
        }
        return new FileScan(imports, nameRefs(tokens));
    }

    /** 全文件的 NAME token（关键字在文法里是独立 token 类型，所以 NAME 就是标识符本身）。 */
    private static List<NameRef> nameRefs(CommonTokenStream tokens) {
        List<NameRef> refs = new ArrayList<>();
        for (Token token : tokens.getTokens()) {
            if (token.getType() == PythonLexer.NAME) {
                refs.add(new NameRef(token.getLine(), token.getText()));
            }
        }
        return refs;
    }

    /**
     * 把全部 import 描述落成边。索引只用 modulePath / 全限定名两类字符串。
     * 去重到 (from,to) 对、丢自环、按 id 排序（与 Java 侧同约定）。
     */
    List<DependencyEdge> resolve(String repositoryId, List<CodeUnitInfo> units,
                                 Map<String, FileScan> scansByFile) {
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
            FileScan scan = scansByFile.get(unit.filePath());
            if (scan == null) {
                continue;
            }
            Set<String> mentioned = namesWithin(scan.names(), unit.startLine(), unit.endLine());
            for (PythonImport imp : scan.imports()) {
                for (String target : targetsOf(imp, mentioned, unitsByModule, unitIdByQualifiedName)) {
                    if (unit.id().equals(target)) {
                        continue;   // 自环是依赖图里的噪声
                    }
                    kindByPair.putIfAbsent(new Pair(unit.id(), target), KIND_IMPORT);
                }
            }
        }

        List<DependencyEdge> edges = new ArrayList<>();
        for (Map.Entry<Pair, String> entry : kindByPair.entrySet()) {
            Pair pair = entry.getKey();
            edges.add(new DependencyEdge(pair.from() + "->" + pair.to(),
                    repositoryId, pair.from(), pair.to(), entry.getValue(), "python"));
        }
        edges.sort(Comparator.comparing(DependencyEdge::id));
        return edges;
    }

    /** 单元行区间内出现过的标识符集合。 */
    private static Set<String> namesWithin(List<NameRef> refs, int startLine, int endLine) {
        Set<String> mentioned = new HashSet<>();
        for (NameRef ref : refs) {
            if (ref.line() >= startLine && ref.line() <= endLine) {
                mentioned.add(ref.name());
            }
        }
        return mentioned;
    }

    /** 该单元的区间里出现过这个 import 绑定的标识符吗？星导入名字不可知 → 一律算用过。 */
    private static boolean appliesTo(PythonImport imp, Set<String> mentioned) {
        if (imp.star()) {
            return true;
        }
        return imp.usageNames().stream().anyMatch(mentioned::contains);
    }

    /**
     * 这条 import 对**这个单元**而言指向哪些目标。
     *
     * <p>过滤要细到**逐个名字**：{@code from X import Point, Unused} 是一条 import，
     * 单元只用了 {@code Point} 时必须只连 {@code Point} —— 整条判"用过"会把 {@code Unused} 也带上。
     */
    private List<String> targetsOf(PythonImport imp, Set<String> mentioned,
                                   Map<String, List<String>> unitsByModule,
                                   Map<String, String> unitIdByQualifiedName) {
        if (imp.star()) {
            // 星导入：导入了哪些名字不可知 → 只能落到模块全部单元
            return unitsByModule.getOrDefault(imp.targetModule(), List.of());
        }
        if (imp.names().isEmpty()) {
            // import X [as y]：用法按根名/别名判定；目标仍是模块全部单元（类级图无模块节点）
            return appliesTo(imp, mentioned)
                    ? unitsByModule.getOrDefault(imp.targetModule(), List.of())
                    : List.of();
        }
        List<String> targets = new ArrayList<>();
        for (int i = 0; i < imp.names().size(); i++) {
            if (!mentioned.contains(imp.usageNames().get(i))) {
                continue;   // 这个名字没被这个单元用到 —— 不连它
            }
            String qualified = imp.targetModule().isEmpty()
                    ? imp.names().get(i) : imp.targetModule() + "." + imp.names().get(i);
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
            return new PythonImport(base, List.of(), true, List.of());   // from X import *
        }
        List<String> names = new ArrayList<>();
        List<String> usageNames = new ArrayList<>();
        for (var importFromAsName : targets.import_from_as_names().import_from_as_name()) {
            // import_from_as_name: name ('as' name)? —— 两个 name，name(0) 才是导入名，name(1) 是别名
            String imported = importFromAsName.name(0).getText();
            String alias = importFromAsName.name().size() > 1
                    ? importFromAsName.name(1).getText() : null;
            names.add(imported);
            usageNames.add(alias != null ? alias : imported);
        }
        return new PythonImport(base, names, false, usageNames);
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

    /** {@code a.b.c} → {@code a}（import 语句在代码里以根名被引用）。 */
    private static String rootName(String dotted) {
        int dot = dotted.indexOf('.');
        return dot < 0 ? dotted : dotted.substring(0, dot);
    }
}
