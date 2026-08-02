package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
class WeComRobotClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    void sendMarkdown(String webhookUrl, String markdown) {
        if (!WeComRobotSetting.isOfficialWebhook(webhookUrl)) {
            throw new IllegalArgumentException("仅支持企业微信官方机器人 Webhook");
        }
        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("content", markdown == null ? "" : markdown)
            ));
        } catch (IOException ex) {
            throw new IllegalStateException("企业微信消息序列化失败", ex);
        }

        Timeout timeout = Timeout.ofSeconds(10);
        RequestConfig config = RequestConfig.custom()
            .setConnectionRequestTimeout(timeout)
            .setConnectTimeout(timeout)
            .setResponseTimeout(timeout)
            .setRedirectsEnabled(false)
            .build();
        try (CloseableHttpClient http = HttpClients.custom()
            .setDefaultRequestConfig(config)
            .disableAutomaticRetries()
            .disableRedirectHandling()
            .build()) {
            HttpPost request = new HttpPost(webhookUrl.trim());
            request.setEntity(new StringEntity(
                body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)
            ));
            http.execute(request, response -> {
                int status = response.getCode();
                String responseBody = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("企业微信返回 HTTP " + status);
                }
                JsonNode result = OBJECT_MAPPER.readTree(responseBody);
                int errorCode = result.path("errcode").asInt(-1);
                if (errorCode != 0) {
                    throw new IllegalStateException(
                        "企业微信返回 " + errorCode + "：" + result.path("errmsg").asText("发送失败")
                    );
                }
                return null;
            });
        } catch (IOException ex) {
            throw new IllegalStateException("企业微信发送失败：" + ex.getMessage(), ex);
        }
    }
}
