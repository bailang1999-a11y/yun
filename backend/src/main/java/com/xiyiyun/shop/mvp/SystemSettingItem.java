package com.xiyiyun.shop.mvp;

import java.util.Map;

public record SystemSettingItem(
    String siteName,
    String logoUrl,
    String customerService,
    String companyName,
    String icpRecordNo,
    String policeRecordNo,
    String disclaimer,
    String paymentMode,
    boolean autoRefundEnabled,
    String smsProvider,
    boolean smsEnabled,
    int upstreamSyncSeconds,
    boolean autoShelfEnabled,
    boolean autoPriceEnabled,
    boolean registrationEnabled,
    String registrationType,
    Long defaultUserGroupId,
    Map<String, String> notificationReceivers
) {}
