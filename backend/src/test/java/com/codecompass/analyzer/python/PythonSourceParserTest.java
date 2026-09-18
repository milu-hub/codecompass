package com.codecompass.analyzer.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecompass.analyzer.python.PythonSourceParser.ParseOutcome;
import com.codecompass.analyzer.python.PythonSourceParser.SyntaxIssue;

/**
 * P1 验收：语法接入冒烟（PYTHON_ANALYZER_PLAN.md §6 任务 P1）。
 *
 * 只验收"解析器装上了"这件事：
 * 1) 现代 Python 3.14 语法（match / PEP 701 f-string / 装饰器 / async）干净解析；
 * 2) 缩进成块（INDENT/DEDENT 机制）工作正常；
 * 3) 语法错误逐条带上 1-based 行号收上来，且树仍在（错误恢复，为 §4.1 的行号纪律打底）。
 */
class PythonSourceParserTest {

    private final PythonSourceParser parser = new PythonSourceParser();

    @Test
    @DisplayName("现代 Python 3.14 语法干净解析：match / PEP 701 f-string / 装饰器 / async / 海象")
    void parsesModernPython() {
        String source = """
                import os
                from dataclasses import dataclass

                @dataclass
                class Point:
                    x: int
                    y: int

                async def handle(value: int) -> int:
                    match value:
                        case 0:
                            return 0
                        case _ if (n := value) > 0:
                            return f"got {point["x"]} in {len(os.listdir("."))} files"
                        case _:
                            return -1
                """;

        ParseOutcome outcome = parser.parse(source);

        assertThat(outcome.issues()).as("干净输入不应有任何语法问题：%s", outcome.issues()).isEmpty();
        assertThat(outcome.tree().getChildCount()).as("file_input: statements? EOF → 2 个直接子节点").isEqualTo(2);
        assertThat(outcome.tree().getChild(0).getChildCount())
                .as("import + import + decorated class + async def = 4 条顶层语句")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("缩进成块：嵌套函数与条件块能过 INDENT/DEDENT 机制")
    void handlesIndentationBlocks() {
        String source = """
                def outer():
                    if True:
                        while False:
                            pass
                        return
                    else:
                        raise ValueError("no")
                """;

        ParseOutcome outcome = parser.parse(source);

        assertThat(outcome.issues()).isEmpty();
        assertThat(outcome.tree().getChild(0).getChildCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("语法错误逐条带 1-based 行号收上来，且树仍然产出（错误恢复）")
    void collectsSyntaxErrorsWithLineNumbers() {
        String source = """
                def fine():
                    return 1

                def broken(:
                    return
                """;

        ParseOutcome outcome = parser.parse(source);

        assertThat(outcome.tree()).as("有错也要有树——ANTLR 自带错误恢复").isNotNull();
        List<SyntaxIssue> issues = outcome.issues();
        assertThat(issues).isNotEmpty();
        assertThat(issues.get(0).line()).as("broken 定义在第 4 行").isEqualTo(4);
        assertThat(issues.get(0).column()).as("列号 1-based").isGreaterThanOrEqualTo(1);
        assertThat(issues.get(0).message()).isNotBlank();
    }
}
