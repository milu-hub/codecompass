package com.codecompass.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * 出站 LLM 端点的 SSRF 守卫：在真正发起 HTTP 之前，先把 {@code baseUrl} 解析成 IP 再判定。
 *
 * <p><b>规则</b>（用户拍板）：
 * <ul>
 *   <li>私有网段 / 环回 / link-local（IPv4 的 10/8、172.16/12、192.168/16、127/8，
 *       IPv6 的 ::1、fc00::/7、fe80::/10）<b>默认拒绝</b>，可由
 *       {@code codecompass.llm.allow-private-network} 放开 —— 本地 Ollama、局域网 LLM
 *       是合法用法，堵死会误伤；</li>
 *   <li>云元数据网段 {@code 169.254.0.0/16} <b>硬拒且不可配置</b>：这是凭据泄露面，
 *       没有「确实需要访问它」的正当场景；</li>
 *   <li>只用「解析后判 IP」这一条，不做 https-only、不做 provider 白名单
 *       （会挡住自建反代与内网部署）。</li>
 * </ul>
 *
 * <p><b>fail closed</b>：协议不合法、缺主机名、域名解析失败，一律拒绝 —— 宁可挡掉一个
 * 配置错误，也不放行一个没验证过的目标。
 *
 * <p><b>未覆盖</b>（已记入 PROGRESS「已知风险」）：DNS rebinding（解析与连接之间存在
 * TOCTOU 窗口；连接仍可能落到未校验的 IP）。
 */
public class LlmEndpointGuard {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final boolean allowPrivateNetwork;

    public LlmEndpointGuard(boolean allowPrivateNetwork) {
        this.allowPrivateNetwork = allowPrivateNetwork;
    }

    /** 校验通过则静默返回；不通过抛 {@link LlmException}（控制器映射为 502 / 测试端点回 ok=false）。 */
    public void check(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.trim();

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new LlmException("LLM 端点地址非法（缺少主机名或格式错误）：" + raw);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new LlmException("LLM 端点仅支持 http/https，实际："
                    + (scheme.isEmpty() ? "(无协议)" : scheme));
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LlmException("LLM 端点缺少主机名：" + raw);
        }
        String bareHost = stripBrackets(host);

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(bareHost);
        } catch (UnknownHostException e) {
            throw new LlmException("无法解析 LLM 端点主机：" + bareHost);
        }

        // 云元数据：硬拒，allow-private-network 也放不开
        for (InetAddress address : addresses) {
            if (isCloudMetadata(address)) {
                throw new LlmException("拒绝出站：地址 " + address.getHostAddress()
                        + " 属于云元数据网段（169.254.0.0/16），不可放开");
            }
        }

        if (!allowPrivateNetwork) {
            for (InetAddress address : addresses) {
                if (isLocalOrPrivate(address)) {
                    throw new LlmException("拒绝出站：地址 " + address.getHostAddress()
                            + " 属于私有/环回/链路本地网段。如确为局域网或本机 LLM，"
                            + "请开启 codecompass.llm.allow-private-network=true");
                }
            }
        }
    }

    /** 取 host 供出站日志用（只记 host，不记完整 URL、不记 key）。 */
    public static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl == null ? "" : baseUrl.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : stripBrackets(host);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static boolean isCloudMetadata(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254;
    }

    private static boolean isLocalOrPrivate(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        // IPv6 唯一本地地址 fc00::/7 —— Java 的 isSiteLocalAddress() 只覆盖已废弃的 fec0::/10
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /** URI.getHost() 对 IPv6 字面量返回带方括号的形式，InetAddress 不认方括号。 */
    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}
