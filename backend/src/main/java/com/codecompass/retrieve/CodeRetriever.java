package com.codecompass.retrieve;

import java.util.List;

import com.codecompass.service.AnalysisTaskSnapshot;

/**
 * 语言中立的代码片段检索。
 *
 * <p>输入全部是中立类型（分析结果、依赖图、源码快照），对任何语言一视同仁。
 *
 * <p>MVP 只有词法实现；向量检索是 TASKBOOK 预留的方向（「不一定用向量数据库」），
 * 因此这里做成接口 —— 将来替换实现时业务层（T10）不动。
 */
public interface CodeRetriever {

    /**
     * @param question     用户问题（中英文均可）
     * @param outcome      一次分析的结果聚合（T7 的内存快照 —— 工作区已删，检索只在内存进行）
     * @param anchorUnitId 用户当前选中的类（§S7「点击某个类提问」）；可为 null。
     *                     有锚点时至少返回锚点自身 —— 中文问题与英文标识符零交集，
     *                     锚点层是中文提问的生命线
     */
    List<RetrievedSnippet> retrieve(String question,
                                    AnalysisTaskSnapshot.AnalysisOutcome outcome,
                                    String anchorUnitId);
}
