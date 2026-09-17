package com.codecompass;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 把 Spring Boot 4.1.1 + Jackson 3 的**实测行为**钉成契约。
 *
 * 存在的理由：Jackson 3 有多项默认值相对 Jackson 2 发生翻转，而这类变化是**静默的**
 * —— 不会编译报错，只会让运行期行为改变。此前这些结论来自社区整理而非实测，
 * 本测试把它们替换为可复现的证据，并保证将来升级 Spring Boot 时若行为再次变化会立刻失败。
 *
 * 直接受影响的是 T10（解析 LLM 返回的 JSON）：LLM 输出常带 markdown 围栏与结尾杂字符，
 * 若严格模式开启，必须用 `JsonMapper.rebuild()` 派生宽松实例，而不是改全局 Bean。
 *
 * 同时验证 AGENTS.md 的约定「使用 Spring Boot 自动配置的 JsonMapper Bean」确实成立。
 */
@SpringBootTest
class JacksonThreeDefaultsTest {

    @Autowired
    private JsonMapper jsonMapper;

    record IntHolder(int n) {
    }

    record IntegerHolder(Integer n) {
    }

    record Ordered(String status, String service, String version) {
    }

    @Test
    @DisplayName("Boot 4 自动配置的 JsonMapper Bean 可直接注入（AGENTS.md 约定成立）")
    void autoConfiguredJsonMapperIsInjectable() {
        assertThat(jsonMapper).isNotNull();
    }

    @Test
    @DisplayName("FAIL_ON_TRAILING_TOKENS 默认开启：JSON 后的多余字符会抛异常")
    void rejectsTrailingTokens() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"n\":1} trailing", IntHolder.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("FAIL_ON_UNKNOWN_PROPERTIES 默认关闭：多出的字段被忽略，不抛异常")
    void ignoresUnknownProperties() {
        assertThatCode(() -> jsonMapper.readValue("{\"n\":1,\"extra\":2}", IntHolder.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FAIL_ON_NULL_FOR_PRIMITIVES 默认开启：null 灌进 int 会抛异常")
    void rejectsNullForPrimitive() {
        assertThatThrownBy(() -> jsonMapper.readValue("{\"n\":null}", IntHolder.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("对照组：null 灌进包装类型 Integer 不抛异常（说明上一条针对的是基本类型）")
    void acceptsNullForBoxedType() {
        assertThatCode(() -> jsonMapper.readValue("{\"n\":null}", IntegerHolder.class))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("WRITE_DATES_AS_TIMESTAMPS 默认关闭：Instant 序列化为 ISO-8601 字符串")
    void writesInstantAsIso8601String() throws Exception {
        String json = jsonMapper.writeValueAsString(new InstantHolder(Instant.parse("2026-09-17T12:34:56Z")));

        assertThat(json).isEqualTo("{\"at\":\"2026-09-17T12:34:56Z\"}");
    }

    record InstantHolder(Instant at) {
    }

    @Test
    @DisplayName("字段顺序为声明顺序，并非字母序")
    void preservesDeclarationOrder() throws Exception {
        String json = jsonMapper.writeValueAsString(new Ordered("UP", "codecompass-backend", "0.0.1-SNAPSHOT"));

        assertThat(json).isEqualTo(
                "{\"status\":\"UP\",\"service\":\"codecompass-backend\",\"version\":\"0.0.1-SNAPSHOT\"}");
    }
}
