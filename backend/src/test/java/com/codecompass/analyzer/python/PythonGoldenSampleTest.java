package com.codecompass.analyzer.python;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.repo.CodeUnitFileInfo;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 黄金样本验收（PYTHON_ANALYZER_PLAN.md §5）。
 *
 * <p>三个样本：纯脚本 / Flask 小应用 / Django 小应用。走真实 Spring 上下文里的
 * {@link PythonAnalyzer}（真实 yml 配置），行号人工核对 —— 行号是产品的核心承诺（§4.1），
 * 所以这里**逐行断言**，不做"差不多"。
 *
 * <p>角色由 {@link PythonRoleAnnotator} 单独断言：角色是编排层的职责，分析器只产出结构与框架。
 */
@SpringBootTest
class PythonGoldenSampleTest {

    @Autowired
    private PythonAnalyzer analyzer;

    @Autowired
    private PythonRoleAnnotator roleAnnotator;

    @TempDir
    Path repoRoot;

    private AnalyzeResult analyze(List<String> paths) {
        List<CodeUnitFileInfo> files = paths.stream()
                .map(path -> new CodeUnitFileInfo(path, "", "", "python"))
                .toList();
        return analyzer.analyze(new AnalyzeRequest("repo", repoRoot, files));
    }

    private void write(String relativePath, String... lines) throws Exception {
        Path target = repoRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, String.join("\n", lines) + "\n");
    }

    private static CodeUnitInfo unit(AnalyzeResult result, String name) {
        return result.codeUnits().stream().filter(u -> u.name().equals(name)).findFirst().orElseThrow();
    }

    // ---------- 样本 1：纯脚本 ----------

    @Test
    @DisplayName("黄金样本 1：纯脚本 —— 两个模块级函数、main 为 entry、无依赖边")
    void pureScript() throws Exception {
        write("cli.py",
                "import sys",
                "",
                "def parse_args(argv):",
                "    return argv[1:]",
                "",
                "def main():",
                "    args = parse_args(sys.argv)",
                "    print(args)",
                "",
                "if __name__ == \"__main__\":",
                "    main()");

        AnalyzeResult result = analyze(List.of("cli.py"));

        assertThat(result.framework()).isEmpty();
        assertThat(result.failedFiles()).isEmpty();
        assertThat(result.dependencies()).as("import sys 是仓库外依赖，必须丢弃").isEmpty();

        CodeUnitInfo parseArgs = unit(result, "parse_args");
        assertThat(parseArgs.kind()).isEqualTo("function");
        assertThat(parseArgs.startLine()).isEqualTo(3);
        assertThat(parseArgs.endLine()).isEqualTo(4);

        CodeUnitInfo main = unit(result, "main");
        assertThat(main.kind()).isEqualTo("function");
        assertThat(main.startLine()).isEqualTo(6);
        assertThat(main.endLine()).isEqualTo(8);
        assertThat(roleAnnotator.annotate(result)).containsEntry(main.id(), "entry");
    }

    // ---------- 样本 2：Flask 小应用 ----------

    @Test
    @DisplayName("黄金样本 2：Flask —— dataclass 实体、路由控制器、一条仓内依赖边")
    void flaskApp() throws Exception {
        write("models.py",
                "from dataclasses import dataclass",
                "",
                "@dataclass",
                "class User:",
                "    name: str",
                "    email: str = \"\"");
        write("app.py",
                "from flask import Flask",
                "",
                "from models import User",
                "",
                "app = Flask(__name__)",
                "",
                "@app.route(\"/\")",
                "def index():",
                "    return f\"hello {User('a', 'b')}\"");

        AnalyzeResult result = analyze(List.of("models.py", "app.py"));

        assertThat(result.framework()).isEqualTo("flask");
        assertThat(result.failedFiles()).isEmpty();

        CodeUnitInfo user = unit(result, "User");
        assertThat(user.kind()).isEqualTo("class");
        assertThat(user.startLine()).as("装饰器行计入范围").isEqualTo(3);
        assertThat(user.endLine()).isEqualTo(6);
        assertThat(user.fields()).extracting(f -> f.name()).containsExactly("name", "email");

        CodeUnitInfo index = unit(result, "index");
        assertThat(index.kind()).isEqualTo("function");
        assertThat(index.startLine()).isEqualTo(7);
        assertThat(index.endLine()).isEqualTo(9);
        assertThat(index.annotations()).containsExactly("app.route");

        Map<String, String> roles = roleAnnotator.annotate(result);
        assertThat(roles).containsEntry(user.id(), "entity").containsEntry(index.id(), "controller");
        assertThat(result.dependencies()).extracting(e -> e.fromCodeUnitId() + ">" + e.toCodeUnitId())
                .containsExactly(index.id() + ">" + user.id());
    }

    // ---------- 样本 3：Django 小应用 ----------

    @Test
    @DisplayName("黄金样本 3：Django —— models.py 实体、api_view 控制器、相对导入成边")
    void djangoApp() throws Exception {
        write("myapp/models.py",
                "from django.db import models",
                "",
                "",
                "class User(models.Model):",
                "    name = models.CharField(max_length=100)");
        write("myapp/views.py",
                "from rest_framework.decorators import api_view",
                "from rest_framework.response import Response",
                "",
                "from .models import User",
                "",
                "",
                "@api_view([\"GET\"])",
                "def user_list(request):",
                "    return Response({\"name\": \"x\"})");

        AnalyzeResult result = analyze(List.of("myapp/models.py", "myapp/views.py"));

        assertThat(result.framework()).isEqualTo("django");
        assertThat(result.failedFiles()).isEmpty();

        CodeUnitInfo user = unit(result, "User");
        assertThat(user.kind()).isEqualTo("class");
        assertThat(user.startLine()).isEqualTo(4);
        assertThat(user.endLine()).isEqualTo(5);

        CodeUnitInfo userList = unit(result, "user_list");
        assertThat(userList.kind()).isEqualTo("function");
        assertThat(userList.startLine()).isEqualTo(7);
        assertThat(userList.endLine()).isEqualTo(9);
        assertThat(userList.annotations()).containsExactly("api_view");

        Map<String, String> roles = roleAnnotator.annotate(result);
        assertThat(roles).containsEntry(user.id(), "entity").containsEntry(userList.id(), "controller");
        // 相对导入 from .models import User → 精确连到 User
        assertThat(result.dependencies()).extracting(e -> e.fromCodeUnitId() + ">" + e.toCodeUnitId())
                .containsExactly(userList.id() + ">" + user.id());
    }
}
