package com.codecompass.analyzer.python;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.xpath.XPath;

import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.FieldInfo;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.analyzer.python.parser.PythonLexer;
import com.codecompass.analyzer.python.parser.PythonParser;
import com.codecompass.analyzer.python.parser.PythonParser.AssignmentContext;
import com.codecompass.analyzer.python.parser.PythonParser.Class_defContext;
import com.codecompass.analyzer.python.parser.PythonParser.Compound_stmtContext;
import com.codecompass.analyzer.python.parser.PythonParser.DecoratorsContext;
import com.codecompass.analyzer.python.parser.PythonParser.File_inputContext;
import com.codecompass.analyzer.python.parser.PythonParser.Function_defContext;
import com.codecompass.analyzer.python.parser.PythonParser.Function_def_rawContext;
import com.codecompass.analyzer.python.parser.PythonParser.Named_expressionContext;
import com.codecompass.analyzer.python.parser.PythonParser.Simple_stmtsContext;
import com.codecompass.analyzer.python.parser.PythonParser.Star_targetsContext;
import com.codecompass.analyzer.python.parser.PythonParser.StatementContext;
import com.codecompass.repo.CodeUnitFileInfo;

/**
 * P3：从一棵干净的解析树里抽「类 / 模块级函数 / 方法 / 字段」（PYTHON_ANALYZER_PLAN.md §3.3 / §4.1）。
 *
 * <p><b>行号范围是本类的核心纪律</b>：块结束不能信 DEDENT token（它的行号是块之后下一行），
 * 必须逐 token 取范围内的最大行号（跳过 INDENT/DEDENT）——§4.1 明确这是最容易错的一条。
 *
 * <p>字段策略（与 Java 侧"类级成员"语义对齐）：
 * 1) 类级注解赋值 {@code x: int} 与普通赋值 {@code X = 1}；
 * 2) {@code __init__} 里的 {@code self.x = ...}（实例属性）。
 */
final class PythonStructureExtractor {

    /** 一个文件产出的结构。 */
    record FileUnits(List<CodeUnitInfo> units, List<MethodInfo> methods) {
    }

    FileUnits extract(String repositoryId, CodeUnitFileInfo file, PythonSourceParser.ParseOutcome outcome) {
        String modulePath = PythonModuleNames.modulePath(file.relativePath());
        List<CodeUnitInfo> units = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();

        File_inputContext root = (File_inputContext) outcome.tree();
        if (root.statements() == null) {
            return new FileUnits(units, methods);
        }
        for (StatementContext statement : root.statements().statement()) {
            Compound_stmtContext compound = statement.compound_stmt();
            if (compound == null) {
                continue;
            }
            if (compound.class_def() != null) {
                units.add(classUnit(repositoryId, file, modulePath, compound.class_def(), methods, outcome.tokens()));
            } else if (compound.function_def() != null) {
                units.add(functionUnit(repositoryId, file, modulePath, compound.function_def(), outcome.tokens()));
            }
        }
        return new FileUnits(units, methods);
    }

    private CodeUnitInfo classUnit(String repositoryId, CodeUnitFileInfo file, String modulePath,
                                   Class_defContext classDef, List<MethodInfo> methods, CommonTokenStream tokens) {
        String name = classDef.class_def_raw().name().getText();
        String qualifiedName = PythonModuleNames.qualifiedName(modulePath, name);
        String id = PythonModuleNames.unitId(repositoryId, file.relativePath(), qualifiedName);
        int start = classDef.getStart().getLine();
        int end = endLine(classDef, tokens);

        List<FieldInfo> fields = new ArrayList<>(classLevelFields(classDef));
        for (StatementContext statement : classBodyStatements(classDef)) {
            Compound_stmtContext compound = statement.compound_stmt();
            if (compound != null && compound.function_def() != null) {
                methods.add(method(compound.function_def(), id, tokens));
            }
        }
        fields.addAll(instanceFields(classDef, tokens));

        return new CodeUnitInfo(id, repositoryId, file.relativePath(), "python", "",
                modulePath, name, "class",
                decoratorNames(classDef.decorators()), fields, start, end);
    }

    private CodeUnitInfo functionUnit(String repositoryId, CodeUnitFileInfo file, String modulePath,
                                      Function_defContext function, CommonTokenStream tokens) {
        String name = function.function_def_raw().name().getText();
        String qualifiedName = PythonModuleNames.qualifiedName(modulePath, name);
        String id = PythonModuleNames.unitId(repositoryId, file.relativePath(), qualifiedName);
        return new CodeUnitInfo(id, repositoryId, file.relativePath(), "python", "",
                modulePath, name, "function",
                decoratorNames(function.decorators()), List.of(),
                function.getStart().getLine(), endLine(function, tokens));
    }

    private MethodInfo method(Function_defContext function, String ownerUnitId, CommonTokenStream tokens) {
        String name = function.function_def_raw().name().getText();
        return new MethodInfo(ownerUnitId + "#" + name, ownerUnitId, name,
                signature(function.function_def_raw(), tokens),
                decoratorNames(function.decorators()), function.getStart().getLine(), endLine(function, tokens));
    }

    /**
     * 参数原文：按 token 拼接（WS 在 HIDDEN 通道，getText() 会把空格丢掉），
     * 在逗号 / 冒号 / 等号后补一个空格，得到 {@code self, x: int, y: int} 这类可读签名。
     * 不依赖 token 字符偏移（那套索引在语法里不可靠，实测两头各差一位）。
     */
    private static String signature(Function_def_rawContext raw, CommonTokenStream tokens) {
        if (raw.params() == null) {
            return "";
        }
        int start = raw.params().getStart().getTokenIndex();
        int stop = raw.params().getStop().getTokenIndex();
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= stop; i++) {
            Token token = tokens.get(i);
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();
            if (type == PythonLexer.LPAR || type == PythonLexer.RPAR) {
                continue;
            }
            out.append(token.getText());
            if (type == PythonLexer.COMMA || type == PythonLexer.COLON || type == PythonLexer.EQUAL) {
                out.append(' ');
            }
        }
        return out.toString().trim();
    }

    /** 类体里直接的 statement（方法/类属性都在这里，不递归进嵌套块）。 */
    private List<StatementContext> classBodyStatements(Class_defContext classDef) {
        if (classDef.class_def_raw().block() == null
                || classDef.class_def_raw().block().statements() == null) {
            return List.of();
        }
        return classDef.class_def_raw().block().statements().statement();
    }

    /** 类级字段：注解赋值 x: T 与普通赋值 X = 1（只有"单一简单名"目标才算，跳过 a = b = c 的中间目标）。 */
    private List<FieldInfo> classLevelFields(Class_defContext classDef) {
        List<FieldInfo> fields = new ArrayList<>();
        for (StatementContext statement : classBodyStatements(classDef)) {
            Simple_stmtsContext simple = statement.simple_stmts();
            if (simple == null) {
                continue;
            }
            for (var simpleStmt : simple.simple_stmt()) {
                AssignmentContext assignment = simpleStmt.assignment();
                if (assignment == null) {
                    continue;
                }
                // x: int = ... —— 注解赋值的类型就是注解表达式
                if (assignment.name() != null) {
                    String type = assignment.expression() == null ? "" : assignment.expression().getText();
                    fields.add(new FieldInfo(assignment.name().getText(), type, List.of()));
                    continue;
                }
                // x = 1 —— 单一简单名目标
                String plainName = singleNameTarget(assignment.star_targets());
                if (plainName != null) {
                    fields.add(new FieldInfo(plainName, "", List.of()));
                }
            }
        }
        return fields;
    }

    /** __init__ 里的 self.x = ...（实例属性）。用 XPath 找该方法的全部 assignment，目标形如 self.NAME。 */
    private List<FieldInfo> instanceFields(Class_defContext classDef, CommonTokenStream tokens) {
        for (StatementContext statement : classBodyStatements(classDef)) {
            Compound_stmtContext compound = statement.compound_stmt();
            if (compound == null || compound.function_def() == null) {
                continue;
            }
            if (!"__init__".equals(compound.function_def().function_def_raw().name().getText())) {
                continue;
            }
            List<FieldInfo> fields = new ArrayList<>();
            // XPath 只需要一个 Parser 来取名表；用同一 token 流新建一个即可，不改变解析结果
            PythonParser parser = new PythonParser(tokens);
            for (var node : XPath.findAll(compound.function_def(), "//assignment", parser)) {
                AssignmentContext assignment = (AssignmentContext) node;
                String attrName = selfAttributeTarget(assignment.star_targets());
                if (attrName != null) {
                    fields.add(new FieldInfo(attrName, "", List.of()));
                }
            }
            return fields;
        }
        return List.of();
    }

    /** 单一简单名目标：只有一个 star_target，且它是 star_atom → name。 */
    private static String singleNameTarget(List<Star_targetsContext> targets) {
        if (targets == null || targets.size() != 1) {
            return null;
        }
        var starTargets = targets.get(0).star_target();
        if (starTargets.size() != 1 || starTargets.get(0).target_with_star_atom() == null) {
            return null;
        }
        var atom = starTargets.get(0).target_with_star_atom().star_atom();
        return atom == null || atom.name() == null ? null : atom.name().getText();
    }

    /** self.NAME 目标：只有一个 star_target，target_with_star_atom 形如 t_primary '.' name 且 t_primary 文本是 self。 */
    private static String selfAttributeTarget(List<Star_targetsContext> targets) {
        if (targets == null || targets.size() != 1) {
            return null;
        }
        var starTargets = targets.get(0).star_target();
        if (starTargets.size() != 1 || starTargets.get(0).target_with_star_atom() == null) {
            return null;
        }
        var target = starTargets.get(0).target_with_star_atom();
        if (target.t_primary() == null || target.name() == null) {
            return null;
        }
        return "self".equals(target.t_primary().getText()) ? target.name().getText() : null;
    }

    /** 装饰器名：@app.route("/x") → app.route，@dataclass → dataclass（截到第一个 '(' 为止）。 */
    private static List<String> decoratorNames(DecoratorsContext decorators) {
        if (decorators == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Named_expressionContext named : decorators.named_expression()) {
            String text = named.getText().trim();
            int paren = text.indexOf('(');
            names.add(paren < 0 ? text : text.substring(0, paren));
        }
        return names;
    }

    /**
     * 块的真实结束行：取单元 token 范围内的最大行号，跳过一切"非内容" token。
     * 不能信 getStop()——它是 DEDENT，行号落在块之后的下一行；这个语法还会把
     * 下一行的前导空白发成 WS（HIDDEN 通道）、把空行的换行发成 NEWLINE，
     * 三者都会把结束行顶到块之外（§4.1 的坑，实测 __init__ 的 end 会算成 11 而不是 9）。
     * 所以：跳过 HIDDEN 通道（WS/COMMENT/续行），再跳过 INDENT/DEDENT/NEWLINE。
     */
    private static int endLine(ParserRuleContext ctx, CommonTokenStream tokens) {
        int start = ctx.getStart().getTokenIndex();
        int stop = ctx.getStop().getTokenIndex();
        int end = ctx.getStart().getLine();
        for (int i = start; i <= stop; i++) {
            Token token = tokens.get(i);
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            int type = token.getType();
            if (type == PythonLexer.INDENT || type == PythonLexer.DEDENT || type == PythonLexer.NEWLINE) {
                continue;
            }
            end = Math.max(end, token.getLine());
        }
        return end;
    }
}
