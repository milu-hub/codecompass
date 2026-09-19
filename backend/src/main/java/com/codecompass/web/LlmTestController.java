package com.codecompass.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.service.LlmClient;
import com.codecompass.service.LlmClientFactory;
import com.codecompass.service.LlmConfig;
import com.codecompass.service.LlmException;
import com.codecompass.web.dto.LlmTestRequest;
import com.codecompass.web.dto.LlmTestResponse;

/**
 * 「测试连接」：拿用户当次填的配置发一次最小请求，验证 key 是否可用。
 *
 * <p><b>安全</b>：
 * <ul>
 *   <li>key 只在本方法栈内构造临时 {@link LlmConfig} 并交给工厂，<b>用完即弃</b>：
 *       不缓存、不落库、不记日志；</li>
 *   <li>响应只回 {@code {ok, message}}，message 取自异常信息（客户端构造的消息不含 key）；</li>
 *   <li>key 走请求体而不是 URL 参数。</li>
 * </ul>
 *
 * <p><b>统一返回 200</b>：这是「测试结果」而不是「请求失败」，前端只需读 {@code ok}，
 * 不必再区分 400/502 两套错误通道。
 *
 * <p>本端点用的是<b>用户自己的 key</b>，不消耗服务端额度，因此不需要限流。
 * baseUrl 由用户提供，服务端会按其发起出站请求 —— 这是「支持自定义 provider」的必然代价。
 */
@RestController
public class LlmTestController {

    /** 最小提示词：只要一次能连通的往返，不掺任何业务。 */
    private static final String PING_SYSTEM = "你是连通性测试助手，只回复一个词。";
    private static final String PING_USER = "hi";

    /** 回显模型答复的截断长度，避免把长文本塞进提示条。 */
    private static final int PREVIEW_LIMIT = 40;

    private final LlmClientFactory llmClientFactory;

    public LlmTestController(LlmClientFactory llmClientFactory) {
        this.llmClientFactory = llmClientFactory;
    }

    @PostMapping("/api/llm/test")
    public LlmTestResponse test(@RequestBody(required = false) LlmTestRequest request) {
        String baseUrl = request == null ? "" : trim(request.baseUrl());
        String apiKey = request == null ? "" : trim(request.apiKey());
        String model = request == null ? "" : trim(request.model());
        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            return new LlmTestResponse(false, "请填写完整的 Base URL / API Key / Model");
        }

        LlmConfig config = new LlmConfig("test", "custom", baseUrl, apiKey, model, false);
        try {
            LlmClient client = llmClientFactory.create(config);
            String reply = client.complete(PING_SYSTEM, PING_USER);
            return new LlmTestResponse(true, "连接成功：" + preview(reply));
        } catch (LlmException e) {
            // 客户端构造的消息形如「LLM 调用失败：HTTP 401」，不含 key
            return new LlmTestResponse(false, e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：任何意外都降级为失败结果，既不让 500 漏出去，也绝不回显请求体
            return new LlmTestResponse(false, "连接失败：" + e.getClass().getSimpleName());
        }
    }

    private static String preview(String reply) {
        String flat = reply == null ? "" : reply.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "模型返回空内容";
        }
        return flat.length() <= PREVIEW_LIMIT ? flat : flat.substring(0, PREVIEW_LIMIT) + "…";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
