package com.codecompass.analyzer.python;

import org.springframework.stereotype.Component;

import com.codecompass.analyzer.AnalyzeRequest;
import com.codecompass.analyzer.AnalyzeResult;
import com.codecompass.analyzer.LanguageAnalyzer;

/**
 * T12 要求 5 的落地：Python 的 stub 分析器。
 *
 * <p><b>它证明的是架构而非解析能力</b>：新增一个语言 = 只新增这一个类（被
 * {@code ObjectProvider} 自动发现进注册表），业务层一行不改。stub 诚实地返回空结果，
 * 不伪造任何数据 —— 真正的 Python 解析器是 MVP 之外的工作。
 *
 * <p>本类只 import 语言中立的 analyzer 根包类型；Python 特有的解析知识一行都不该出现在这里。
 */
@Component
public class PythonStubAnalyzer implements LanguageAnalyzer {

    @Override
    public String language() {
        return "python";
    }

    @Override
    public AnalyzeResult analyze(AnalyzeRequest request) {
        String repositoryId = request == null ? "" : request.repositoryId();
        return AnalyzeResult.empty(repositoryId, "python", "");
    }
}
