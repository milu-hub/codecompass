package com.codecompass.repo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CloneResult 是 T1 的对外契约（SCHEMA.md 定义 3 字段）。
 * 关键约定：失败时不得给出一个半成品 localPath，否则会诱导调用方去用一个不完整的仓库。
 */
class CloneResultTest {

    @Test
    @DisplayName("成功结果带 localPath，且 errorMessage 为空")
    void successCarriesPathWithoutError() {
        CloneResult result = CloneResult.ok("/tmp/codecompass/repo-1");

        assertThat(result.success()).isTrue();
        assertThat(result.localPath()).isEqualTo("/tmp/codecompass/repo-1");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("失败结果带 errorMessage，且 localPath 必须为 null")
    void failureCarriesMessageWithoutPath() {
        CloneResult result = CloneResult.fail("克隆超时（60s）");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("克隆超时（60s）");
        assertThat(result.localPath())
                .as("失败时不能返回半成品路径，否则调用方可能去用一个不完整的仓库")
                .isNull();
    }
}
