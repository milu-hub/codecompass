package com.codecompass.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出站 LLM 端点 SSRF 守卫。
 *
 * <p>规则（按用户拍板）：
 * <ul>
 *   <li>私有网段 / 环回 / link-local <b>默认拒绝</b>，可由 {@code allow-private-network} 放开
 *       （本地 Ollama、局域网 LLM 是合法用法）；</li>
 *   <li>云元数据网段 {@code 169.254.0.0/16} <b>硬拒且不可配置</b>；</li>
 *   <li>不做「只允许 https」，也不做 provider 白名单（会挡住自建反代）。</li>
 * </ul>
 *
 * 全部用 IP 字面量，避免单测依赖外网 DNS。
 */
class LlmEndpointGuardTest {

    private static final LlmEndpointGuard DENY_PRIVATE = new LlmEndpointGuard(false);
    private static final LlmEndpointGuard ALLOW_PRIVATE = new LlmEndpointGuard(true);

    private static void assertRejected(LlmEndpointGuard guard, String baseUrl, String fragment) {
        assertThatThrownBy(() -> guard.check(baseUrl))
                .as("应拒绝 %s", baseUrl)
                .isInstanceOf(LlmException.class)
                .hasMessageContaining(fragment);
    }

    private static void assertAllowed(LlmEndpointGuard guard, String baseUrl) {
        assertThatCode(() -> guard.check(baseUrl))
                .as("应允许 %s", baseUrl)
                .doesNotThrowAnyException();
    }

    // ---------- 默认拒绝：私有 / 环回 / link-local ----------

    @Test
    @DisplayName("默认拒绝环回（127.0.0.1 本机 LLM）")
    void rejectsLoopbackByDefault() {
        assertRejected(DENY_PRIVATE, "http://127.0.0.1:11434/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 A 类私有 10.0.0.0/8")
    void rejectsClassAPrivate() {
        assertRejected(DENY_PRIVATE, "http://10.0.0.5:8000/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 B 类私有 172.16.0.0/12")
    void rejectsClassBPrivate() {
        assertRejected(DENY_PRIVATE, "http://172.16.5.5/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 C 类私有 192.168.0.0/16")
    void rejectsClassCPrivate() {
        assertRejected(DENY_PRIVATE, "http://192.168.1.10/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 IPv6 环回 [::1]")
    void rejectsIpv6Loopback() {
        assertRejected(DENY_PRIVATE, "http://[::1]:11434/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 IPv6 唯一本地 fc00::/7（Java 的 siteLocal 不覆盖它）")
    void rejectsIpv6UniqueLocal() {
        assertRejected(DENY_PRIVATE, "http://[fd00::1]/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 IPv6 link-local fe80::/10")
    void rejectsIpv6LinkLocal() {
        assertRejected(DENY_PRIVATE, "http://[fe80::1]/v1", "私有/环回/链路本地");
    }

    @Test
    @DisplayName("默认拒绝 0.0.0.0（任意地址）")
    void rejectsAnyLocal() {
        assertRejected(DENY_PRIVATE, "http://0.0.0.0/v1", "私有/环回/链路本地");
    }

    // ---------- 开关放开：局域网 / 本机 LLM 是合法用法 ----------

    @Test
    @DisplayName("开关打开后允许本机与局域网（自建 Ollama / 反代）")
    void allowsPrivateWhenSwitchOn() {
        assertAllowed(ALLOW_PRIVATE, "http://127.0.0.1:11434/v1");
        assertAllowed(ALLOW_PRIVATE, "http://192.168.1.10:8000/v1");
        assertAllowed(ALLOW_PRIVATE, "http://[fd00::1]/v1");
        assertAllowed(ALLOW_PRIVATE, "http://[fe80::1]/v1");
    }

    // ---------- 云元数据：硬拒，开关也放不开 ----------

    @Test
    @DisplayName("云元数据 169.254.169.254 硬拒，开关打开也拒绝")
    void cloudMetadataIsHardDenied() {
        assertRejected(DENY_PRIVATE, "http://169.254.169.254/latest/meta-data", "云元数据");
        assertRejected(ALLOW_PRIVATE, "http://169.254.169.254/latest/meta-data", "云元数据");
    }

    @Test
    @DisplayName("整个 169.254.0.0/16 都硬拒")
    void entireLinkLocalV4RangeIsHardDenied() {
        assertRejected(ALLOW_PRIVATE, "http://169.254.1.1/v1", "云元数据");
    }

    // ---------- 公网地址：默认即允许 ----------

    @Test
    @DisplayName("公网地址默认允许（http 与 https 都放行，不做 https-only）")
    void allowsPublicAddresses() {
        assertAllowed(DENY_PRIVATE, "https://1.1.1.1/v1");
        assertAllowed(DENY_PRIVATE, "http://8.8.8.8/v1");
    }

    // ---------- 非法输入：fail closed ----------

    @Test
    @DisplayName("非 http/https 协议一律拒绝（file/ftp 不是 LLM 端点）")
    void rejectsNonHttpSchemes() {
        assertRejected(DENY_PRIVATE, "file:///etc/passwd", "仅支持 http/https");
        assertRejected(DENY_PRIVATE, "ftp://example.com/v1", "仅支持 http/https");
    }

    @Test
    @DisplayName("缺少主机名 / 非法 URL 一律拒绝")
    void rejectsMissingHost() {
        assertRejected(DENY_PRIVATE, "http://", "缺少主机名");
        assertRejected(DENY_PRIVATE, "not-a-url", "仅支持 http/https");
        assertRejected(DENY_PRIVATE, null, "仅支持 http/https");
    }

    @Test
    @DisplayName("解析不了的主机名 fail closed（宁可拒绝，不放行未验证目标）")
    void unresolvableHostIsRejected() {
        assertRejected(DENY_PRIVATE, "http://codecompass-no-such-host.invalid/v1", "无法解析");
    }
}
