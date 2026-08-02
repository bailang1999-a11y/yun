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
    Map<String, String> notificationReceivers,
    WeComRobotSetting wecomRobot
) {
    public SystemSettingItem(
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
    ) {
        this(
            siteName, logoUrl, customerService, companyName, icpRecordNo, policeRecordNo, disclaimer,
            paymentMode, autoRefundEnabled, smsProvider, smsEnabled, upstreamSyncSeconds, autoShelfEnabled,
            autoPriceEnabled, registrationEnabled, registrationType, defaultUserGroupId, notificationReceivers,
            WeComRobotSetting.disabled()
        );
    }

    public SystemSettingItem {
        notificationReceivers = notificationReceivers == null ? Map.of() : Map.copyOf(notificationReceivers);
        wecomRobot = wecomRobot == null ? WeComRobotSetting.disabled() : wecomRobot;
    }

    public SystemSettingItem publicView() {
        return new SystemSettingItem(
            siteName, logoUrl, customerService, companyName, icpRecordNo, policeRecordNo, disclaimer,
            paymentMode, autoRefundEnabled, smsProvider, smsEnabled, upstreamSyncSeconds, autoShelfEnabled,
            autoPriceEnabled, registrationEnabled, registrationType, defaultUserGroupId, notificationReceivers,
            WeComRobotSetting.disabled()
        );
    }
}
