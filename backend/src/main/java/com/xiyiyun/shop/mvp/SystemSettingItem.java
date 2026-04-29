package com.xiyiyun.shop.mvp;

import java.util.Map;

public record SystemSettingItem(
    String siteName,
    String logoUrl,
    String customerService,
    String paymentMode,
    boolean autoRefundEnabled,
    String smsProvider,
    boolean smsEnabled,
    int upstreamSyncSeconds,
    boolean autoShelfEnabled,
    boolean autoPriceEnabled,
    Map<String, String> notificationReceivers
) {}
