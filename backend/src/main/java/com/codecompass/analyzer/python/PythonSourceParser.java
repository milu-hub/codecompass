package com.codecompass.analyzer.python;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;

import com.codecompass.analyzer.python.parser.PythonLexer;
import com.codecompass.analyzer.python.parser.PythonParser;

/**
 * P1 冒烟件（PYTHON_ANALYZER_PLAN.md）：把 ANTLR 藏在语言包内部。
 *
 * <p>语言中立模型与业务层永远不接触 {@code ParseTree} —— P3 的单元/方法/字段抽取建立在本类之上；
 * 本类只承诺两件事：现代 Python 3.14 语法能解析；错误能逐条带行号收上来（单文件失败不影响整次分析）。
 */
public final class PythonSourceParser {

    /** 一条语法问题。line / column 都是 1-based。 */
    public record SyntaxIssue(int line, int column, String message) {
    }

    /** 解析结果：总是有树（ANTLR 有错误恢复），问题列表为空表示干净解析。
     *  tokens 交给后续的抽取器算行号范围（P3 §4.1：块结束行必须逐 token 取，不能信 DEDENT）。 */
    public record ParseOutcome(ParseTree tree, CommonTokenStream tokens, List<SyntaxIssue> issues) {
    }

    public ParseOutcome parse(String source) {
        CollectingErrorListener errors = new CollectingErrorListener();
        PythonLexer lexer = new PythonLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PythonParser parser = new PythonParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        ParseTree tree = parser.file_input();
        return new ParseOutcome(tree, tokens, List.copyOf(errors.issues));
    }

    private static final class CollectingErrorListener extends BaseErrorListener {
        private final List<SyntaxIssue> issues = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                int line, int charPositionInLine, String message, RecognitionException exception) {
            issues.add(new SyntaxIssue(line, charPositionInLine + 1, message));
        }
    }
}
