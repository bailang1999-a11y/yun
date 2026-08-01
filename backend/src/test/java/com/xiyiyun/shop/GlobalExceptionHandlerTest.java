package com.xiyiyun.shop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批次5 / B4：全局异常处理。
 *
 * <p>核心验收：未预期异常<b>不泄漏</b>任何堆栈 / 类名 / 内部消息，只回通用文案 + traceId；
 * 业务异常文案照常透出，响应体结构统一为 {@link ApiResponse}，三端前端解析不用改。
 */
class GlobalExceptionHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new BoomController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    void unexpectedExceptionLeaksNoStackOrInternalMessage() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/boom/unexpected"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        String body = response.getContentAsString();
        JsonNode json = MAPPER.readTree(body);

        assertThat(json.path("code").asInt()).isEqualTo(-1);
        assertThat(json.path("message").asText()).startsWith("服务器处理请求时出现异常");
        assertThat(json.path("data").isNull()).isTrue();

        // 不允许出现任何内部实现细节
        assertThat(body).doesNotContain("jdbc:mysql");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("NullPointerException");
        assertThat(body).doesNotContain("java.lang");
        assertThat(body).doesNotContain("com.xiyiyun");
        assertThat(body).doesNotContain("at ");

        // traceId 既在文案里也在响应头里，便于把用户截图对上服务端日志
        assertThat(json.path("message").asText()).containsPattern("追踪号 [0-9a-f]{16}");
        assertThat(response.getHeader("X-Trace-Id")).matches("[0-9a-f]{16}");
    }

    @Test
    void unexpectedExceptionWithSensitiveMessageIsNotEchoed() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/boom/sensitive"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain("jdbc:mysql://xiyiyun-mysql:3306");
        assertThat(response.getContentAsString()).doesNotContain("Table 'xiyiyun.orders' doesn't exist");
    }

    @Test
    void businessExceptionKeepsUserFacingMessageAndLegacyShape() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/boom/business"))
            .andReturn().getResponse();

        // 与既有控制器 try/catch 的行为保持一致：HTTP 200 + code=-1 + 中文文案
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(-1);
        assertThat(json.path("message").asText()).isEqualTo("账号或密码不正确");
    }

    @Test
    void technicalIllegalArgumentFallsBackToGenericMessage() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/boom/technical-illegal-argument"))
            .andReturn().getResponse();

        JsonNode json = MAPPER.readTree(response.getContentAsString());
        assertThat(json.path("message").asText()).isEqualTo(GlobalExceptionHandler.GENERIC_ERROR_MESSAGE);
        assertThat(response.getContentAsString()).doesNotContain("com.xiyiyun.shop.internal");
    }

    @Test
    void malformedJsonBodyReturnsFixedMessageWithoutParserDetails() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/boom/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not-json"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode json = MAPPER.readTree(response.getContentAsString());
        assertThat(json.path("code").asInt()).isEqualTo(-1);
        assertThat(json.path("message").asText()).isEqualTo(GlobalExceptionHandler.BAD_REQUEST_MESSAGE);
        assertThat(response.getContentAsString()).doesNotContain("JsonParseException");
        assertThat(response.getContentAsString()).doesNotContain("com.fasterxml");
    }

    @RestController
    @RequestMapping("/boom")
    static class BoomController {
        @GetMapping("/unexpected")
        String unexpected() {
            throw new NullPointerException("Cannot invoke \"com.xiyiyun.shop.mvp.OrderItem.status()\" because it is null");
        }

        @GetMapping("/sensitive")
        String sensitive() {
            throw new RuntimeException("jdbc:mysql://xiyiyun-mysql:3306/xiyiyun failed: Table 'xiyiyun.orders' doesn't exist");
        }

        @GetMapping("/business")
        String business() {
            throw new IllegalArgumentException("账号或密码不正确");
        }

        @GetMapping("/technical-illegal-argument")
        String technicalIllegalArgument() {
            throw new IllegalArgumentException("com.xiyiyun.shop.internal.Widget cannot be cast");
        }

        @PostMapping("/echo")
        String echo(@RequestBody EchoBody body) {
            return body.value();
        }
    }

    record EchoBody(String value) {}
}
