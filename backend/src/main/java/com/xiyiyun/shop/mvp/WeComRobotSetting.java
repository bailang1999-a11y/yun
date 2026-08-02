package com.xiyiyun.shop.mvp;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public record WeComRobotSetting(
    boolean enabled,
    String webhookUrl,
    List<WeComNotificationEvent> events
) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    public WeComRobotSetting {
        webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        events = events == null ? List.of() : events.stream().distinct().toList();
    }

    public static WeComRobotSetting disabled() {
        return new WeComRobotSetting(false, "", List.of());
    }

    public WeComRobotSetting validated() {
        if (StringUtils.hasText(webhookUrl) && !isOfficialWebhook(webhookUrl)) {
            throw new IllegalArgumentException("仅支持企业微信官方机器人 Webhook");
        }
        if (enabled && !StringUtils.hasText(webhookUrl)) {
            throw new IllegalArgumentException("启用机器人前请填写 Webhook");
        }
        if (enabled && events.isEmpty()) {
            throw new IllegalArgumentException("启用机器人前请至少选择一个通知事件");
        }
        return this;
    }

    public boolean accepts(WeComNotificationEvent event) {
        return enabled && event != null && events.contains(event);
    }

    public static boolean isOfficialWebhook(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"qyapi.weixin.qq.com".equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !"/cgi-bin/webhook/send".equals(uri.getPath())) {
                return false;
            }
            String query = uri.getRawQuery();
            if (query == null || !query.startsWith("key=") || query.indexOf('&') >= 0) {
                return false;
            }
            return KEY_PATTERN.matcher(query.substring(4)).matches();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
