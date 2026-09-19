package com.codecompass.service;

import java.io.IOException;
import java.net.HttpURLConnection;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * LLM 出站专用请求工厂：**显式禁止跟随重定向**。
 *
 * <p>动机：即便出站前已用 {@link LlmEndpointGuard} 校验过地址，一个 302 也能把请求
 * 引到内网（先连公网、再被跳到 {@code 169.254.169.254} 这类地址）。禁止跟随重定向后，
 * 3xx 会原样返回并由客户端按「响应不含 choices」报错，跳转目标永远不会被请求。
 *
 * <p>Spring 对非 GET 方法默认已经是 {@code setInstanceFollowRedirects(false)}，这里
 * 再显式设一次：一是让「不跟随」成为写下来的契约而不是依赖框架默认，二是防未来
 * 出现 GET 调用时悄悄恢复跟随。
 */
public class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
        super.prepareConnection(connection, httpMethod);
        connection.setInstanceFollowRedirects(false);
    }
}
