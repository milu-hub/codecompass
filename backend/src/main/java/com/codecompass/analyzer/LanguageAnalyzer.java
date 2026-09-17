package com.codecompass.analyzer;

/**
 * 语言分析器。MVP 只有 Java / Spring 一个实现（T4）。
 *
 * <p><b>这是多语言架构的唯一扩展点</b>：新增一门语言 = 新增一个本接口的实现 + 在
 * {@code codecompass.scan.sources[]} 加一项配置。业务层只依赖本接口与语言中立 DTO，
 * 永远不 import 任何实现类 —— 由 {@link LanguageAnalyzerRegistry} 保证这一点。
 *
 * <p>本接口的方法名、参数与返回类型必须语言中立：不得出现 {@code analyzeJava}、
 * {@code CompilationUnit}、{@code Spoon} 之类语言特有的名字或类型。
 */
public interface LanguageAnalyzer {

    /** 负责的语言标签，取值与 {@code scan.sources[].language} 对应。同一语言只能有一个实现。 */
    String language();

    /**
     * 分析给定文件并产出语言中立结果。
     *
     * <p>同步阻塞；异步化与进度上报由调用方负责（T7）—— 分析器本身不该关心任务调度。
     *
     * <p>实现约定：单个文件解析失败时记入 {@link AnalyzeResult#failedFiles()} 并继续，
     * 不要因此抛出异常作废整次分析。
     */
    AnalyzeResult analyze(AnalyzeRequest request);
}
