package com.xiyiyun.shop.mvp;

import com.alibaba.excel.EasyExcel;
import com.xiyiyun.shop.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminMvpController {
    private static final long MAX_UPLOAD_IMAGE_BYTES = 5L * 1024 * 1024;

    private final InMemoryShopRepository repository;
    private final Path uploadDir;

    public AdminMvpController(InMemoryShopRepository repository, @Value("${xiyiyun.upload.dir:uploads}") String uploadDir) {
        this.repository = repository;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/goods")
    public ApiResponse<List<GoodsItem>> goods(
        @RequestParam(required = false) Long categoryId,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String platform
    ) {
        return ApiResponse.ok(repository.listGoods(categoryId, search, platform, true));
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthSession<AdminProfile>> adminLogin(@RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest servletRequest) {
        try {
            return ApiResponse.ok(repository.loginAdmin(request, clientIp(servletRequest)));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/auth/sms/send")
    public ApiResponse<String> sendAdminLoginSms(@RequestBody SendSmsCodeRequest request, jakarta.servlet.http.HttpServletRequest servletRequest) {
        return safe(() -> repository.sendAdminLoginSmsCode(request, clientIp(servletRequest)));
    }

    @PostMapping("/auth/slider")
    public ApiResponse<String> adminSlider(@RequestBody(required = false) SendSmsCodeRequest request) {
        return ApiResponse.ok(repository.createSliderToken("admin"));
    }

    @GetMapping("/auth/captcha-config")
    public ApiResponse<CaptchaChallengeItem> adminCaptchaConfig() {
        return ApiResponse.ok(repository.captchaChallenge("admin"));
    }

    @GetMapping("/captcha-settings")
    public ApiResponse<CaptchaSettingItem> captchaSettings() {
        return ApiResponse.ok(repository.captchaSetting());
    }

    @PostMapping("/captcha-settings")
    public ApiResponse<CaptchaSettingItem> updateCaptchaSettings(@RequestBody CaptchaSettingRequest request) {
        return safe(() -> repository.updateCaptchaSetting(request));
    }

    @PostMapping("/captcha-settings/test")
    public ApiResponse<String> testCaptchaSettings(@RequestBody CaptchaSettingRequest request) {
        return safe(() -> repository.testCaptchaSetting(request));
    }

    @GetMapping("/auth/me")
    public ApiResponse<AdminProfile> adminMe(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findAdminByToken(token)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<String> adminLogout(@RequestHeader(value = "Authorization", required = false) String token) {
        repository.logoutAdmin(token);
        return ApiResponse.ok("ok");
    }

    @GetMapping("/staff")
    public ApiResponse<List<AdminStaffItem>> adminStaff() {
        return ApiResponse.ok(repository.listAdminStaff());
    }

    @PostMapping("/staff")
    public ApiResponse<AdminStaffItem> createAdminStaff(@RequestBody AdminStaffRequest request) {
        return safe(() -> repository.createAdminStaff(request));
    }

    @PostMapping("/staff/{id}")
    public ApiResponse<AdminStaffItem> updateAdminStaff(@PathVariable Long id, @RequestBody AdminStaffRequest request) {
        return safe(() -> repository.updateAdminStaff(id, request));
    }

    @PostMapping("/staff/{id}/delete")
    public ApiResponse<String> deleteAdminStaff(@PathVariable Long id) {
        return safeDeleted(() -> repository.deleteAdminStaff(id));
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping("/settings")
    public ApiResponse<SystemSettingItem> settings() {
        return ApiResponse.ok(repository.systemSetting());
    }

    @GetMapping("/sms-login-settings")
    public ApiResponse<SmsLoginSettingItem> smsLoginSettings() {
        return ApiResponse.ok(repository.smsLoginSetting());
    }

    @PostMapping("/sms-login-settings")
    public ApiResponse<SmsLoginSettingItem> updateSmsLoginSettings(@RequestBody SmsLoginSettingRequest request) {
        return safe(() -> repository.updateSmsLoginSetting(request));
    }

    @GetMapping("/payment-channels")
    public ApiResponse<List<PaymentChannelItem>> paymentChannels() {
        return ApiResponse.ok(repository.listPaymentChannels());
    }

    @PostMapping("/payment-channels")
    public ApiResponse<PaymentChannelItem> createPaymentChannel(@RequestBody PaymentChannelRequest request) {
        return safe(() -> repository.createPaymentChannel(request));
    }

    @PostMapping("/payment-channels/{id}")
    public ApiResponse<PaymentChannelItem> updatePaymentChannel(@PathVariable Long id, @RequestBody PaymentChannelRequest request) {
        return safe(() -> repository.updatePaymentChannel(id, request));
    }

    @PostMapping("/payment-channels/{id}/enable")
    public ApiResponse<PaymentChannelItem> enablePaymentChannel(@PathVariable Long id) {
        return safe(() -> repository.updatePaymentChannelStatus(id, true));
    }

    @PostMapping("/payment-channels/{id}/disable")
    public ApiResponse<PaymentChannelItem> disablePaymentChannel(@PathVariable Long id) {
        return safe(() -> repository.updatePaymentChannelStatus(id, false));
    }

    @PostMapping("/payment-channels/{id}/delete")
    public ApiResponse<String> deletePaymentChannel(@PathVariable Long id) {
        return safeDeleted(() -> repository.deletePaymentChannel(id));
    }

    @GetMapping("/price-templates")
    public ApiResponse<List<PriceTemplateItem>> priceTemplates() {
        return ApiResponse.ok(repository.listPriceTemplates());
    }

    @PostMapping("/price-templates")
    public ApiResponse<List<PriceTemplateItem>> savePriceTemplates(@RequestBody List<PriceTemplateItem> request) {
        return safe(() -> repository.savePriceTemplates(request));
    }

    @PostMapping("/settings")
    public ApiResponse<SystemSettingItem> updateSettings(@RequestBody UpdateSystemSettingRequest request) {
        return ApiResponse.ok(repository.updateSystemSetting(request));
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryItem>> categories() {
        return ApiResponse.ok(repository.listCategories());
    }

    @GetMapping("/card-kinds")
    public ApiResponse<List<CardKindItem>> cardKinds() {
        return ApiResponse.ok(repository.listCardKinds());
    }

    @GetMapping("/recharge-fields")
    public ApiResponse<List<RechargeFieldItem>> rechargeFields(@RequestParam(required = false) Boolean enabled) {
        return ApiResponse.ok(repository.listRechargeFields(enabled));
    }

    @PostMapping("/recharge-fields")
    public ApiResponse<RechargeFieldItem> createRechargeField(@RequestBody RechargeFieldRequest request) {
        return safe(() -> repository.createRechargeField(request));
    }

    @PostMapping("/recharge-fields/{id}")
    public ApiResponse<RechargeFieldItem> updateRechargeField(@PathVariable Long id, @RequestBody RechargeFieldRequest request) {
        return safe(() -> repository.updateRechargeField(id, request));
    }

    @PostMapping("/recharge-fields/{id}/enable")
    public ApiResponse<RechargeFieldItem> enableRechargeField(@PathVariable Long id) {
        return safe(() -> repository.updateRechargeFieldEnabled(id, true));
    }

    @PostMapping("/recharge-fields/{id}/disable")
    public ApiResponse<RechargeFieldItem> disableRechargeField(@PathVariable Long id) {
        return safe(() -> repository.updateRechargeFieldEnabled(id, false));
    }

    @PostMapping("/recharge-fields/{id}/delete")
    public ApiResponse<String> deleteRechargeField(@PathVariable Long id) {
        return safeDeleted(() -> repository.deleteRechargeField(id));
    }

    @PostMapping("/card-kinds")
    public ApiResponse<CardKindItem> createCardKind(@RequestBody CreateCardKindRequest request) {
        return safe(() -> repository.createCardKind(request));
    }

    @GetMapping("/card-kinds/{id}/cards")
    public ApiResponse<List<CardSecret>> cardKindCards(@PathVariable Long id) {
        return safe(() -> repository.listCardKindCards(id));
    }

    @PostMapping("/card-kinds/{id}/cards/import")
    public ApiResponse<CardImportResult> importCardKindCards(@PathVariable Long id, @RequestBody CardImportRequest request) {
        return safe(() -> repository.importCardKindCards(id, request));
    }

    @GetMapping("/user-groups")
    public ApiResponse<List<UserGroupItem>> userGroups() {
        return ApiResponse.ok(repository.listUserGroups());
    }

    @PostMapping("/user-groups")
    public ApiResponse<UserGroupItem> createUserGroup(@RequestBody CreateUserGroupRequest request) {
        return safe(() -> repository.createUserGroup(request));
    }

    @GetMapping("/users")
    public ApiResponse<List<UserItem>> users() {
        return ApiResponse.ok(repository.listUsers());
    }

    @PostMapping("/user-groups/{groupId}/rules")
    public ApiResponse<List<GroupRuleItem>> updateGroupRules(
        @PathVariable Long groupId,
        @RequestBody UpdateGroupRulesRequest request
    ) {
        return safe(() -> repository.updateGroupRules(groupId, request));
    }

    @PostMapping("/user-groups/{groupId}/order-permission")
    public ApiResponse<UserGroupItem> updateUserGroupOrderPermission(
        @PathVariable Long groupId,
        @RequestBody UpdateUserGroupOrderPermissionRequest request
    ) {
        return safe(() -> repository.updateUserGroupOrderPermission(groupId, request));
    }

    @PostMapping("/users/{userId}/group")
    public ApiResponse<UserItem> updateUserGroup(
        @PathVariable Long userId,
        @RequestBody UpdateUserGroupRequest request
    ) {
        return safe(() -> repository.updateUserGroup(userId, request));
    }

    @PostMapping("/users/{userId}/credentials")
    public ApiResponse<UserItem> updateUserCredentials(
        @PathVariable Long userId,
        @RequestBody AdminUserCredentialRequest request
    ) {
        return safe(() -> repository.updateUserCredentials(userId, request));
    }

    @PostMapping("/users/{userId}/funds")
    public ApiResponse<UserItem> adjustUserFunds(
        @PathVariable Long userId,
        @RequestBody UserFundAdjustRequest request
    ) {
        return safe(() -> repository.adjustUserFunds(userId, request));
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryItem> createCategory(@RequestBody CreateCategoryRequest request) {
        return safe(() -> repository.createCategory(request));
    }

    @PostMapping("/categories/{id}")
    public ApiResponse<CategoryItem> updateCategory(@PathVariable Long id, @RequestBody UpdateCategoryRequest request) {
        return safe(() -> repository.updateCategory(id, request));
    }

    @PostMapping("/categories/{id}/enable")
    public ApiResponse<CategoryItem> enableCategory(@PathVariable Long id) {
        return safe(() -> repository.updateCategoryStatus(id, true));
    }

    @PostMapping("/categories/{id}/disable")
    public ApiResponse<CategoryItem> disableCategory(@PathVariable Long id) {
        return safe(() -> repository.updateCategoryStatus(id, false));
    }

    @PostMapping("/categories/{id}/delete")
    public ApiResponse<String> deleteCategory(@PathVariable Long id) {
        return safeDeleted(() -> repository.deleteCategory(id));
    }

    @PostMapping("/goods")
    public ApiResponse<GoodsItem> createGoods(@RequestBody CreateGoodsRequest request) {
        return safe(() -> repository.createGoods(request));
    }

    @PostMapping("/goods/{id}")
    public ApiResponse<GoodsItem> updateGoods(@PathVariable Long id, @RequestBody CreateGoodsRequest request) {
        return safe(() -> repository.updateGoods(id, request));
    }

    @PostMapping("/goods/{id}/delete")
    public ApiResponse<String> deleteGoods(@PathVariable Long id) {
        return safeDeleted(() -> repository.deleteGoods(id));
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierItem>> suppliers() {
        return ApiResponse.ok(repository.listSuppliers());
    }

    @PostMapping("/suppliers")
    public ApiResponse<SupplierItem> createSupplier(@RequestBody CreateSupplierRequest request) {
        return safe(() -> repository.createSupplier(request));
    }

    @PostMapping("/suppliers/{id}")
    public ApiResponse<SupplierItem> updateSupplier(@PathVariable Long id, @RequestBody CreateSupplierRequest request) {
        return safe(() -> repository.updateSupplier(id, request));
    }

    @PostMapping("/suppliers/{id}/delete")
    public ApiResponse<String> deleteSupplier(@PathVariable Long id) {
        return safeDeleted(() -> repository.deleteSupplier(id));
    }

    @PostMapping("/suppliers/{id}/enable")
    public ApiResponse<SupplierItem> enableSupplier(@PathVariable Long id) {
        return safe(() -> repository.updateSupplierStatus(id, true));
    }

    @PostMapping("/suppliers/{id}/disable")
    public ApiResponse<SupplierItem> disableSupplier(@PathVariable Long id) {
        return safe(() -> repository.updateSupplierStatus(id, false));
    }

    @PostMapping("/suppliers/{id}/balance")
    public ApiResponse<SupplierItem> refreshSupplierBalance(@PathVariable Long id) {
        return safe(() -> repository.refreshSupplierBalance(id));
    }

    @PostMapping("/suppliers/{id}/test")
    public ApiResponse<SupplierItem> testSupplier(@PathVariable Long id) {
        return safe(() -> repository.testSupplierConnection(id));
    }

    @PostMapping("/suppliers/{id}/sync-goods")
    public ApiResponse<RemoteGoodsSyncResult> syncSupplierGoods(
        @PathVariable Long id,
        @RequestBody(required = false) SyncGoodsRequest request
    ) {
        return safe(() -> repository.syncRemoteGoods(id, request));
    }

    @GetMapping("/suppliers/{id}/remote-goods")
    public ApiResponse<RemoteGoodsSyncResult> remoteSupplierGoods(@PathVariable Long id) {
        try {
            return repository.latestRemoteGoods(id)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.fail("remote goods sync result not found"));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/suppliers/{id}/remote-goods/snapshot")
    public ApiResponse<GoodsIntegrationItem> remoteGoodsSnapshot(
        @PathVariable Long id,
        @RequestParam String supplierGoodsId
    ) {
        return safe(() -> repository.remoteGoodsSnapshot(id, supplierGoodsId));
    }

    @PostMapping("/source-connect/suppliers/{id}/remote-goods")
    public ApiResponse<RemoteGoodsSyncResult> sourceConnectRemoteGoods(
        @PathVariable Long id,
        @RequestBody(required = false) SyncGoodsRequest request
    ) {
        return safe(() -> repository.sourceConnectRemoteGoods(id, request));
    }

    @PostMapping("/source-connect/suppliers/{id}/clone")
    public ApiResponse<SourceCloneResult> cloneSourceGoods(
        @PathVariable Long id,
        @RequestBody SourceCloneRequest request
    ) {
        return safe(() -> repository.cloneSourceGoods(id, request));
    }

    @PostMapping("/source-connect/benefit-durations/repair")
    public ApiResponse<Integer> repairBenefitDurations() {
        return safe(repository::repairBenefitDurationsFromTitles);
    }

    @PostMapping(value = "/uploads/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.ok(saveUploadImage(file));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        } catch (IOException ex) {
            return ApiResponse.fail("图片上传失败");
        }
    }

    @PostMapping("/goods/{id}/cards/import")
    public ApiResponse<CardImportResult> importCards(@PathVariable Long id, @RequestBody CardImportRequest request) {
        return safe(() -> repository.importCards(id, request));
    }

    @GetMapping("/goods/{id}/channels")
    public ApiResponse<List<GoodsChannelItem>> goodsChannels(@PathVariable Long id) {
        return safe(() -> repository.listGoodsChannels(id));
    }

    @GetMapping("/goods-monitor")
    public ApiResponse<ProductMonitorOverview> goodsMonitor() {
        return ApiResponse.ok(repository.productMonitorOverview());
    }

    @GetMapping("/goods-monitor/logs")
    public ApiResponse<List<ProductMonitorLogItem>> goodsMonitorLogs() {
        return ApiResponse.ok(repository.listProductMonitorLogs());
    }

    @PostMapping("/goods-monitor/scan")
    public ApiResponse<List<ProductMonitorScanResult>> scanGoodsMonitor() {
        return ApiResponse.ok(repository.scanAllProductMonitorChannels(true));
    }

    @PostMapping("/goods-monitor/channels/{channelId}/scan")
    public ApiResponse<ProductMonitorScanResult> scanGoodsMonitorChannel(@PathVariable Long channelId) {
        ProductMonitorScanResult result = repository.scanProductMonitorChannel(channelId, true);
        if (result == null) {
            return ApiResponse.fail("monitor channel not found");
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/goods/{id}/channels")
    public ApiResponse<GoodsChannelItem> createGoodsChannel(
        @PathVariable Long id,
        @RequestBody CreateGoodsChannelRequest request
    ) {
        return safe(() -> repository.createGoodsChannel(id, request));
    }

    @PostMapping("/goods/{id}/channels/{channelId}/delete")
    public ApiResponse<String> deleteGoodsChannel(@PathVariable Long id, @PathVariable Long channelId) {
        return safeDeleted(() -> repository.deleteGoodsChannel(id, channelId));
    }

    @GetMapping("/goods/{id}/cards")
    public ApiResponse<List<CardSecret>> cards(@PathVariable Long id) {
        return ApiResponse.ok(repository.listCards(id));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderItem>> orders(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String goodsType
    ) {
        return ApiResponse.ok(repository.listOrders(search, status, goodsType));
    }

    @GetMapping("/orders/export")
    public void exportOrders(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String goodsType,
        HttpServletResponse response
    ) throws IOException {
        String filename = URLEncoder.encode("喜易云订单导出.xlsx", StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);

        EasyExcel.write(response.getOutputStream())
            .head(orderExportHead())
            .sheet("订单")
            .doWrite(orderExportRows(repository.listOrders(search, status, goodsType)));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderItem> orderDetail(@PathVariable String orderNo) {
        return repository.findOrder(orderNo)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("order not found"));
    }

    @PostMapping("/orders/{orderNo}/refresh-callback")
    public ApiResponse<OrderItem> refreshOrderCallback(@PathVariable String orderNo) {
        return safe(() -> repository.refreshOrderCallbackInfo(orderNo));
    }

    @PostMapping("/orders/refresh-unfinished")
    public ApiResponse<OrderRefreshResult> refreshUnfinishedOrders() {
        return safe(repository::refreshUnfinishedOrderStatuses);
    }

    @PostMapping("/orders/{orderNo}/complete-manual")
    public ApiResponse<OrderItem> completeManual(@PathVariable String orderNo) {
        return safe(() -> repository.completeManualOrder(orderNo));
    }

    @PostMapping("/orders/{orderNo}/retry")
    public ApiResponse<OrderItem> retry(@PathVariable String orderNo) {
        return safe(() -> repository.retryProcurement(orderNo));
    }

    @PostMapping("/orders/{orderNo}/retry-channel/{channelId}")
    public ApiResponse<OrderItem> retryWithChannel(@PathVariable String orderNo, @PathVariable Long channelId) {
        return safe(() -> repository.retryProcurementWithChannel(orderNo, channelId));
    }

    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<OrderItem> refund(@PathVariable String orderNo) {
        return safe(() -> repository.refundOrder(orderNo));
    }

    @PostMapping("/orders/{orderNo}/manual-success")
    public ApiResponse<OrderItem> manualSuccess(@PathVariable String orderNo) {
        return safe(() -> repository.markOrderSuccess(orderNo));
    }

    @PostMapping("/orders/{orderNo}/manual-failed")
    public ApiResponse<OrderItem> manualFailed(@PathVariable String orderNo) {
        return safe(() -> repository.markOrderFailed(orderNo));
    }

    @PostMapping("/orders/{orderNo}/delete")
    public ApiResponse<String> deleteOrder(@PathVariable String orderNo) {
        return safeDeleted(() -> repository.deleteOrder(orderNo));
    }

    @GetMapping("/payments")
    public ApiResponse<List<PaymentItem>> payments() {
        return ApiResponse.ok(repository.listPayments());
    }

    @GetMapping("/refunds")
    public ApiResponse<List<RefundItem>> refunds() {
        return ApiResponse.ok(repository.listRefunds());
    }

    @GetMapping("/sms-logs")
    public ApiResponse<List<SmsLogItem>> smsLogs() {
        return ApiResponse.ok(repository.listSmsLogs());
    }

    @GetMapping("/operation-logs")
    public ApiResponse<List<OperationLogItem>> operationLogs() {
        return ApiResponse.ok(repository.listOperationLogs());
    }

    @GetMapping("/member-api-credentials")
    public ApiResponse<List<MemberApiCredentialItem>> memberApiCredentials() {
        return ApiResponse.ok(repository.listMemberCredentials());
    }

    @GetMapping("/users/{userId}/member-api")
    public ApiResponse<MemberApiCredentialItem> userMemberApiCredential(@PathVariable Long userId) {
        return safe(() -> repository.memberCredentialForUser(userId));
    }

    @PostMapping("/users/{userId}/member-api")
    public ApiResponse<MemberApiCredentialItem> saveUserMemberApiCredential(
        @PathVariable Long userId,
        @RequestBody MemberApiCredentialRequest request
    ) {
        return safe(() -> repository.saveMemberCredential(userId, request));
    }

    @GetMapping("/open-api-logs")
    public ApiResponse<List<OpenApiLogItem>> openApiLogs() {
        return ApiResponse.ok(repository.listOpenApiLogs());
    }

    private List<List<String>> orderExportHead() {
        return List.of(
            List.of("订单编号"),
            List.of("来源"),
            List.of("商品ID"),
            List.of("商品名"),
            List.of("数量"),
            List.of("本地售价"),
            List.of("总金额"),
            List.of("会员账号"),
            List.of("创建时间"),
            List.of("支付时间"),
            List.of("订单状态"),
            List.of("发货方式"),
            List.of("上游状态/失败原因"),
            List.of("卡号/发货结果")
        );
    }

    private List<List<String>> orderExportRows(List<OrderItem> orders) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<String>> rows = new ArrayList<>();
        for (OrderItem order : orders) {
            rows.add(Arrays.asList(
                order.orderNo(),
                text(order.platform()),
                String.valueOf(order.goodsId()),
                text(order.goodsName()),
                String.valueOf(order.quantity()),
                money(order.unitPrice()),
                money(order.payAmount()),
                text(order.rechargeAccount()),
                order.createdAt() == null ? "" : order.createdAt().format(formatter),
                order.paidAt() == null ? "" : order.paidAt().format(formatter),
                String.valueOf(order.status()),
                String.valueOf(order.goodsType()),
                text(order.deliveryMessage()),
                String.join("\n", order.deliveryItems())
            ));
        }
        return rows;
    }

    private String money(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private UploadResult saveUploadImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_UPLOAD_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 5MB");
        }
        String contentType = text(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!List.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(contentType)) {
            throw new IllegalArgumentException("仅支持上传图片文件");
        }
        byte[] signature = firstBytes(file, 12);
        if (!matchesImageSignature(contentType, signature)) {
            throw new IllegalArgumentException("图片文件内容与格式不匹配");
        }
        String extension = uploadImageExtension(file.getOriginalFilename(), contentType);
        String datePath = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        Path targetDir = uploadDir.resolve("images").resolve(datePath).normalize();
        Files.createDirectories(targetDir);
        if (!targetDir.startsWith(uploadDir)) {
            throw new IllegalStateException("图片保存路径无效");
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;
        Path target = targetDir.resolve(filename).normalize();
        if (!target.startsWith(targetDir)) {
            throw new IllegalStateException("图片保存路径无效");
        }
        file.transferTo(target);
        return new UploadResult("/uploads/images/" + datePath + "/" + filename, filename, file.getSize(), contentType);
    }

    private byte[] firstBytes(MultipartFile file, int limit) throws IOException {
        byte[] bytes = file.getBytes();
        return Arrays.copyOf(bytes, Math.min(bytes.length, limit));
    }

    private boolean matchesImageSignature(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/jpeg" -> bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
            case "image/png" -> bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
            case "image/webp" -> bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50;
            case "image/gif" -> bytes.length >= 6
                && bytes[0] == 0x47
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x38
                && (bytes[4] == 0x37 || bytes[4] == 0x39)
                && bytes[5] == 0x61;
            default -> false;
        };
    }

    private String uploadImageExtension(String originalFilename, String contentType) {
        String filename = text(originalFilename).toLowerCase(Locale.ROOT);
        int dotIndex = filename.lastIndexOf('.');
        String extension = dotIndex >= 0 ? filename.substring(dotIndex) : "";
        if (List.of(".jpg", ".jpeg", ".png", ".webp", ".gif").contains(extension)) {
            return extension;
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> throw new IllegalArgumentException("仅支持 jpg、png、webp、gif 图片");
        };
    }

    private <T> ApiResponse<T> safe(ApiAction<T> action) {
        try {
            return ApiResponse.ok(action.run());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    private ApiResponse<String> safeDeleted(Runnable action) {
        try {
            action.run();
            return ApiResponse.ok("deleted");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface ApiAction<T> {
        T run();
    }
}
