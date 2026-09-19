package com.codecompass.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.codecompass.service.LlmClient;
import com.codecompass.service.LlmClientFactory;
import com.codecompass.service.LlmConfig;
import com.codecompass.service.LlmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/llm/test 契约：成功/失败统一回 {@code {ok, message}}；
 * **响应里绝不能出现 apiKey**（每个用例都带哨兵 key 断言）。
 */
class LlmTestControllerTest {

    /** 哨兵 key：任何一条断言都在查它有没有泄漏。 */
    private static final String SECRET = "sk-sentinel-never-echo-12345";

    private LlmClientFactory factory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(LlmClientFactory.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LlmTestController(factory)).build();
    }

    private static String body(String baseUrl, String apiKey, String model) {
        return "{\"baseUrl\":\"" + baseUrl + "\",\"apiKey\":\"" + apiKey
                + "\",\"model\":\"" + model + "\"}";
    }

    @Test
    @DisplayName("连通成功：ok=true，且用请求体的配置构造客户端，响应不含 apiKey")
    void successReturnsOkAndNeverEchoesKey() throws Exception {
        LlmClient client = Mockito.mock(LlmClient.class);
        when(factory.create(any())).thenReturn(client);
        when(client.complete(anyString(), anyString())).thenReturn("你好");

        String response = mockMvc.perform(post("/api/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://api.deepseek.com/v1", SECRET, "deepseek-chat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SECRET);

        ArgumentCaptor<LlmConfig> captor = ArgumentCaptor.forClass(LlmConfig.class);
        verify(factory).create(captor.capture());
        assertThat(captor.getValue().baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(captor.getValue().apiKey()).isEqualTo(SECRET);
        assertThat(captor.getValue().model()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("key 无效：ok=false 带明确原因，响应仍不含 apiKey")
    void badKeyReturnsNotOkWithoutEchoingKey() throws Exception {
        LlmClient client = Mockito.mock(LlmClient.class);
        when(factory.create(any())).thenReturn(client);
        when(client.complete(anyString(), anyString()))
                .thenThrow(new LlmException("LLM 调用失败：HTTP 401"));

        String response = mockMvc.perform(post("/api/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://api.deepseek.com/v1", SECRET, "deepseek-chat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("401")))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SECRET);
    }

    @Test
    @DisplayName("字段不全：ok=false 且不发起任何外呼")
    void missingFieldsShortCircuitWithoutCallingLlm() throws Exception {
        String response = mockMvc.perform(post("/api/llm/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://api.deepseek.com/v1", "", "deepseek-chat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SECRET);
        verify(factory, never()).create(any());
    }

    @Test
    @DisplayName("请求体缺失（null body）也不 500")
    void nullBodyDoesNotBlowUp() throws Exception {
        mockMvc.perform(post("/api/llm/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
    }
}
