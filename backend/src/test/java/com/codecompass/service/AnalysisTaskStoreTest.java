package com.codecompass.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存任务存储。
 *
 * <p>核心约束是**原子发布**：状态与结果必须一次可见。若先写 status=done 再写结果，
 * 轮询方会读到 done 却拿不到结果 —— 这个瞬时态只在并发下出现，单线程调试永远复现不了。
 * 因此把"状态"与"结果"合并进一个不可变快照（结果聚合在 outcome 字段里），CAS 整体替换。
 */
class AnalysisTaskStoreTest {

    private final AnalysisTaskStore store = new AnalysisTaskStore();

    @Test
    @DisplayName("create 返回 pending 快照，且 taskId 唯一")
    void createReturnsPendingSnapshot() {
        String first = store.create("https://github.com/a/b");
        String second = store.create("https://github.com/c/d");

        assertThat(first).isNotBlank().isNotEqualTo(second);
        AnalysisTaskSnapshot snapshot = store.find(first).orElseThrow();
        assertThat(snapshot.taskId()).isEqualTo(first);
        assertThat(snapshot.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_PENDING);
        assertThat(snapshot.url()).isEqualTo("https://github.com/a/b");
        assertThat(snapshot.progress()).isZero();
        assertThat(snapshot.language()).isNull();
        assertThat(snapshot.errorMessage()).isNull();
        assertThat(snapshot.outcome()).isNull();
        assertThat(snapshot.createdAt()).isEqualTo(snapshot.updatedAt());
    }

    @Test
    @DisplayName("未知 taskId 返回空 Optional")
    void findReturnsEmptyForUnknown() {
        assertThat(store.find("no-such-task")).isEmpty();
    }

    @Test
    @DisplayName("update 用 CAS 整体替换快照，读取方永远看到自洽的状态")
    void updateReplacesWholeSnapshot() {
        String taskId = store.create("https://github.com/a/b");

        store.update(taskId, snapshot -> snapshot.withProgress(
                AnalysisTaskSnapshot.STATUS_RUNNING, 40, "扫描中"));

        AnalysisTaskSnapshot updated = store.find(taskId).orElseThrow();
        assertThat(updated.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_RUNNING);
        assertThat(updated.progress()).isEqualTo(40);
        assertThat(updated.message()).isEqualTo("扫描中");
        assertThat(updated.updatedAt())
                .as("同一时钟刻度内两次 Instant.now() 可能相等，故只要求不早于创建时间")
                .isAfterOrEqualTo(updated.createdAt());
    }

    @Test
    @DisplayName("update 未知任务给出明确错误而不是静默忽略")
    void updateUnknownTaskFailsLoudly() {
        assertThatThrownBy(() -> store.update("no-such-task", snapshot -> snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-task");
    }

    @Test
    @DisplayName("outcome.sourceLines 被深拷贝：外部改动不影响已发布的快照")
    void sourceLinesAreDeeplyImmutable() {
        Map<String, List<String>> lines = new LinkedHashMap<>();
        lines.put("a.java", new ArrayList<>(List.of("line1")));
        AnalysisTaskSnapshot.AnalysisOutcome outcome =
                new AnalysisTaskSnapshot.AnalysisOutcome(null, null, Map.of(), lines);

        lines.get("a.java").add("line2");
        lines.put("b.java", List.of("line1"));

        assertThat(outcome.sourceLines().get("a.java")).containsExactly("line1");
        assertThat(outcome.sourceLines()).doesNotContainKey("b.java");
        assertThatThrownBy(() -> outcome.sourceLines().put("c.java", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("outcome 的 null 列表与 Map 全部规范化为空，roles 也是")
    void outcomeNullsBecomeEmpty() {
        AnalysisTaskSnapshot.AnalysisOutcome outcome =
                new AnalysisTaskSnapshot.AnalysisOutcome(null, null, null, null);

        assertThat(outcome.sourceLines()).isEmpty();
        assertThat(outcome.roles()).isEmpty();
    }

    @Test
    @DisplayName("done 的发布与结果的可见是同一个 CAS：期间没有只更新状态不更新结果的机会")
    void statusAndOutcomeArePublishedTogether() {
        String taskId = store.create("https://github.com/a/b");

        AnalysisTaskSnapshot.AnalysisOutcome outcome =
                new AnalysisTaskSnapshot.AnalysisOutcome(null, null, Map.of(), Map.of());
        store.update(taskId, snapshot -> snapshot.withDone(outcome, "java", "分析完成"));

        AnalysisTaskSnapshot done = store.find(taskId).orElseThrow();
        assertThat(done.status()).isEqualTo(AnalysisTaskSnapshot.STATUS_DONE);
        assertThat(done.outcome()).isSameAs(outcome);
        assertThat(done.language()).isEqualTo("java");
    }
}
