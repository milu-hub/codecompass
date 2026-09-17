package com.codecompass.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codecompass.analyzer.CodeUnitInfo;
import com.codecompass.analyzer.DependencyEdge;
import com.codecompass.analyzer.FieldInfo;
import com.codecompass.analyzer.MethodInfo;
import com.codecompass.service.AnalysisTaskSnapshot;

/**
 * 词法检索：类名 / 方法名 / 字段名 / 注解名 / 包名的 token 命中 + 锚点与依赖图。
 *
 * <p>两层策略：
 * <ol>
 *   <li><b>词法</b>：camelCase 拆分 + 小写，问题 token 与各类标识符 token 求交。
 *       类名命中 → 类范围；方法名命中 → 方法范围（验收的两条口径）。</li>
 *   <li><b>锚点</b>：用户选中的类 +10 强制入围，它的一跳出边依赖 +3 入围。
 *       这是中文问题的生命线 —— 中文 token 与英文标识符零交集。</li>
 * </ol>
 *
 * <p>content 从 T7 的源码快照按 1-based 闭区间截取：{@code lines[startLine-1 .. endLine-1]}。
 * 行号边界是 T10「行号准确率 ≥ 90%」的最后一毫米。
 */
public class LexicalCodeRetriever implements CodeRetriever {

    private static final Logger log = LoggerFactory.getLogger(LexicalCodeRetriever.class);

    private final RetrieveProperties properties;

    public LexicalCodeRetriever(RetrieveProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RetrievedSnippet> retrieve(String question,
                                           AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                           String anchorUnitId) {
        if (question == null || outcome == null || outcome.result() == null) {
            return List.of();
        }
        List<String> questionTokens = tokenize(question);
        RetrieveProperties.Weights weights = properties.getWeights();

        Map<String, CodeUnitInfo> unitsById = new LinkedHashMap<>();
        Map<String, Double> unitScores = new LinkedHashMap<>();
        Map<MethodRange, Double> methodScores = new LinkedHashMap<>();

        for (CodeUnitInfo unit : outcome.result().codeUnits()) {
            unitsById.put(unit.id(), unit);
            double score = weights.getClassName() * matches(questionTokens, unit.name())
                    + weights.getPackageName() * matches(questionTokens, unit.packageName());
            for (FieldInfo field : unit.fields()) {
                score += weights.getFieldOrAnnotation() * matches(questionTokens, field.name());
            }
            for (String annotation : unit.annotations()) {
                score += weights.getFieldOrAnnotation() * matches(questionTokens, annotation);
            }
            if (score > 0) {
                unitScores.put(unit.id(), score);
            }
        }

        for (MethodInfo method : outcome.result().methods()) {
            double score = weights.getMethodName() * matches(questionTokens, method.name());
            if (score > 0) {
                methodScores.merge(new MethodRange(method.codeUnitId(), method.startLine(),
                        method.endLine()), score, Double::sum);
                // 问题里的方法名往往意味着也要看它所在的类
                unitScores.merge(method.codeUnitId(), score * 0.5, Double::sum);
            }
        }

        if (anchorUnitId != null && !anchorUnitId.isBlank()) {
            unitScores.merge(anchorUnitId, weights.getAnchor(), Double::sum);
            List<DependencyEdge> edges = outcome.graph() == null ? List.of() : outcome.graph().edges();
            for (DependencyEdge edge : edges) {
                if (edge.fromCodeUnitId().equals(anchorUnitId)) {
                    unitScores.merge(edge.toCodeUnitId(), weights.getAnchorDependency(), Double::sum);
                }
            }
        }

        List<RetrievedSnippet> snippets = new ArrayList<>();
        for (Map.Entry<String, Double> entry : unitScores.entrySet()) {
            CodeUnitInfo unit = unitsById.get(entry.getKey());
            if (unit == null) {
                continue;
            }
            snippets.add(snippetFor(unit.filePath(), unit.language(), unit.startLine(),
                    unit.endLine(), entry.getValue(), outcome.sourceLines()));
        }
        for (Map.Entry<MethodRange, Double> entry : methodScores.entrySet()) {
            CodeUnitInfo unit = unitsById.get(entry.getKey().codeUnitId());
            if (unit == null) {
                continue;
            }
            snippets.add(snippetFor(unit.filePath(), unit.language(), entry.getKey().startLine(),
                    entry.getKey().endLine(), entry.getValue(), outcome.sourceLines()));
        }

        snippets.sort(Comparator.comparingDouble(RetrievedSnippet::score).reversed()
                .thenComparing(RetrievedSnippet::file)
                .thenComparingInt(RetrievedSnippet::startLine));

        int cap = Math.max(0, properties.getMaxSnippets());
        return List.copyOf(snippets.subList(0, Math.min(cap, snippets.size())));
    }

    private static RetrievedSnippet snippetFor(String file, String language,
                                               int startLine, int endLine, double score,
                                               Map<String, List<String>> sourceLines) {
        return new RetrievedSnippet(file, language, startLine, endLine,
                slice(sourceLines, file, startLine, endLine), score);
    }

    /** 1-based 闭区间 → 0-based 列表的切片。任何边界错误都意味着 T10 引用整体偏一行。 */
    private static String slice(Map<String, List<String>> sourceLines,
                                String file, int startLine, int endLine) {
        if (sourceLines == null) {
            return "";
        }
        List<String> lines = sourceLines.get(file);
        if (lines == null) {
            // 显式记录：file 口径与快照 key 不一致时，这里必须让人看见，而不是静默返回空
            log.warn("源码快照缺少文件 {}，content 置空（file 口径与 T2 relativePath 不一致？）", file);
            return "";
        }
        int from = startLine - 1;
        int to = Math.min(endLine, lines.size());
        if (from < 0 || from >= to) {
            return "";
        }
        return String.join("\n", lines.subList(from, to));
    }

    /** 命中计数：token 在名字的 token 里出现一次 +1；整个名字（不分词）等于 token 也 +1。 */
    private static double matches(List<String> questionTokens, String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        List<String> nameTokens = tokenize(name);
        String wholeName = name.toLowerCase(Locale.ROOT);
        double hits = 0;
        for (String token : questionTokens) {
            if (nameTokens.contains(token) || wholeName.equals(token)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * camelCase 拆分 + 非字母数字分隔 + 小写 + 去重。
     * 中文按整词保留 —— 它与代码标识符没有交集，所以锚点层才是中文问题的检索主力。
     */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String camelSplit = text
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        List<String> tokens = new ArrayList<>();
        for (String part : camelSplit.split("[^\\p{L}\\p{Nd}]+")) {
            String token = part.toLowerCase(Locale.ROOT);
            if (!token.isBlank() && !tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private record MethodRange(String codeUnitId, int startLine, int endLine) {
    }
}
