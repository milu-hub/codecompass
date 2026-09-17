package com.codecompass.analyzer;

import java.nio.file.Path;
import java.util.List;

import com.codecompass.repo.CodeUnitFileInfo;

/**
 * 一次分析的输入。
 *
 * <p>刻意做成对象而不是把参数摊在接口方法上：将来要加仓库元信息（如构建文件清单、
 * 提交 SHA）时不必改所有分析器的签名。
 *
 * <p><b>刻意不含 language</b>：调用方已按语言选中分析器，再传一遍就有了两个真相来源，
 * 不一致时无法判断以谁为准。分析器从自己的 {@link LanguageAnalyzer#language()} 取值。
 *
 * @param repositoryId  仓库标识，用于填充结果里各 DTO 的 repositoryId
 * @param repositoryRoot 工作区根目录，用于把 {@code relativePath} 解析成可读文件
 * @param files          T2 的输出
 */
public record AnalyzeRequest(
        String repositoryId,
        Path repositoryRoot,
        List<CodeUnitFileInfo> files) {

    public AnalyzeRequest {
        files = ModelSupport.immutableCopy(files);
    }
}
