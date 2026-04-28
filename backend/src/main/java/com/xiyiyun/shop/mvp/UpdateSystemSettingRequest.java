package com.xiyiyun.shop.mvp;

import java.util.Map;

public record UpdateSystemSettingRequest(
    String siteName,
    String logoUrl,
    String customerService,
    String paymentMode,
    Boolean autoRefundEnabled,
    String smsProvider,
    Boolean smsEnabled,
    Integer upstreamSyncSeconds,
    Boolean autoShelfEnabled,
    Boolean autoPriceEnabled,
    Map<String, String> notificationReceivers
) {}
