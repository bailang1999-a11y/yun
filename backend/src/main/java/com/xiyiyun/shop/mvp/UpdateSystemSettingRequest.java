package com.xiyiyun.shop.mvp;

import java.util.Map;

public record UpdateSystemSettingRequest(
    String siteName,
    String logoUrl,
    String customerService,
    String companyName,
    String icpRecordNo,
    String policeRecordNo,
    String disclaimer,
    String paymentMode,
    Boolean autoRefundEnabled,
    String smsProvider,
    Boolean smsEnabled,
    Integer upstreamSyncSeconds,
    Boolean autoShelfEnabled,
    Boolean autoPriceEnabled,
    Boolean registrationEnabled,
    String registrationType,
    Long defaultUserGroupId,
    Map<String, String> notificationReceivers,
    WeComRobotSetting wecomRobot
) {
    public UpdateSystemSettingRequest(
        String siteName,
        String logoUrl,
        String customerService,
        String companyName,
        String icpRecordNo,
        String policeRecordNo,
        String disclaimer,
        String paymentMode,
        Boolean autoRefundEnabled,
        String smsProvider,
        Boolean smsEnabled,
        Integer upstreamSyncSeconds,
        Boolean autoShelfEnabled,
        Boolean autoPriceEnabled,
        Boolean registrationEnabled,
        String registrationType,
        Long defaultUserGroupId,
        Map<String, String> notificationReceivers
    ) {
        this(
            siteName, logoUrl, customerService, companyName, icpRecordNo, policeRecordNo, disclaimer,
            paymentMode, autoRefundEnabled, smsProvider, smsEnabled, upstreamSyncSeconds, autoShelfEnabled,
            autoPriceEnabled, registrationEnabled, registrationType, defaultUserGroupId, notificationReceivers,
            null
        );
    }
}
