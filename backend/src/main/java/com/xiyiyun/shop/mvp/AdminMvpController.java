package com.xiyiyun.shop.mvp;

import com.alibaba.excel.EasyExcel;
import com.xiyiyun.shop.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminMvpController {
    private final InMemoryShopRepository repository;

    public AdminMvpController(InMemoryShopRepository repository) {
        this.repository = repository;
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
    public ApiResponse<AuthSession<AdminProfile>> adminLogin(@RequestBody LoginRequest request) {
        try {
            return ApiResponse.ok(repository.loginAdmin(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/auth/me")
    public ApiResponse<AdminProfile> adminMe(@RequestHeader(value = "Authorization", required = false) String token) {
        return repository.findAdminByToken(token)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail("unauthorized"));
    }

    @GetMapping("/settings")
    public ApiResponse<SystemSettingItem> settings() {
        return ApiResponse.ok(repository.systemSetting());
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

    @PostMapping("/card-kinds")
    public ApiResponse<CardKindItem> createCardKind(@RequestBody CreateCardKindRequest request) {
        try {
            return ApiResponse.ok(repository.createCardKind(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/card-kinds/{id}/cards")
    public ApiResponse<List<CardSecret>> cardKindCards(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.listCardKindCards(id));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/card-kinds/{id}/cards/import")
    public ApiResponse<CardImportResult> importCardKindCards(@PathVariable Long id, @RequestBody CardImportRequest request) {
        try {
            return ApiResponse.ok(repository.importCardKindCards(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/user-groups")
    public ApiResponse<List<UserGroupItem>> userGroups() {
        return ApiResponse.ok(repository.listUserGroups());
    }

    @PostMapping("/user-groups")
    public ApiResponse<UserGroupItem> createUserGroup(@RequestBody CreateUserGroupRequest request) {
        try {
            return ApiResponse.ok(repository.createUserGroup(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
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
        try {
            return ApiResponse.ok(repository.updateGroupRules(groupId, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/users/{userId}/group")
    public ApiResponse<UserItem> updateUserGroup(
        @PathVariable Long userId,
        @RequestBody UpdateUserGroupRequest request
    ) {
        try {
            return ApiResponse.ok(repository.updateUserGroup(userId, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/categories")
    public ApiResponse<CategoryItem> createCategory(@RequestBody CreateCategoryRequest request) {
        try {
            return ApiResponse.ok(repository.createCategory(request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/categories/{id}")
    public ApiResponse<CategoryItem> updateCategory(@PathVariable Long id, @RequestBody UpdateCategoryRequest request) {
        try {
            return ApiResponse.ok(repository.updateCategory(id, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/categories/{id}/enable")
    public ApiResponse<CategoryItem> enableCategory(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.updateCategoryStatus(id, true));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/categories/{id}/disable")
    public ApiResponse<CategoryItem> disableCategory(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.updateCategoryStatus(id, false));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/categories/{id}/delete")
    public ApiResponse<String> deleteCategory(@PathVariable Long id) {
        try {
            repository.deleteCategory(id);
            return ApiResponse.ok("deleted");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/goods")
    public ApiResponse<GoodsItem> createGoods(@RequestBody CreateGoodsRequest request) {
        try {
            return ApiResponse.ok(repository.createGoods(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/goods/{id}")
    public ApiResponse<GoodsItem> updateGoods(@PathVariable Long id, @RequestBody CreateGoodsRequest request) {
        try {
            return ApiResponse.ok(repository.updateGoods(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierItem>> suppliers() {
        return ApiResponse.ok(repository.listSuppliers());
    }

    @PostMapping("/suppliers")
    public ApiResponse<SupplierItem> createSupplier(@RequestBody CreateSupplierRequest request) {
        try {
            return ApiResponse.ok(repository.createSupplier(request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}")
    public ApiResponse<SupplierItem> updateSupplier(@PathVariable Long id, @RequestBody CreateSupplierRequest request) {
        try {
            return ApiResponse.ok(repository.updateSupplier(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/delete")
    public ApiResponse<String> deleteSupplier(@PathVariable Long id) {
        try {
            repository.deleteSupplier(id);
            return ApiResponse.ok("deleted");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/enable")
    public ApiResponse<SupplierItem> enableSupplier(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.updateSupplierStatus(id, true));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/disable")
    public ApiResponse<SupplierItem> disableSupplier(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.updateSupplierStatus(id, false));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/balance")
    public ApiResponse<SupplierItem> refreshSupplierBalance(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.refreshSupplierBalance(id));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/test")
    public ApiResponse<SupplierItem> testSupplier(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.testSupplierConnection(id));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/suppliers/{id}/sync-goods")
    public ApiResponse<RemoteGoodsSyncResult> syncSupplierGoods(
        @PathVariable Long id,
        @RequestBody(required = false) SyncGoodsRequest request
    ) {
        try {
            return ApiResponse.ok(repository.syncRemoteGoods(id, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
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

    @PostMapping("/goods/{id}/cards/import")
    public ApiResponse<CardImportResult> importCards(@PathVariable Long id, @RequestBody CardImportRequest request) {
        try {
            return ApiResponse.ok(repository.importCards(id, request));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @GetMapping("/goods/{id}/channels")
    public ApiResponse<List<GoodsChannelItem>> goodsChannels(@PathVariable Long id) {
        try {
            return ApiResponse.ok(repository.listGoodsChannels(id));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/goods/{id}/channels")
    public ApiResponse<GoodsChannelItem> createGoodsChannel(
        @PathVariable Long id,
        @RequestBody CreateGoodsChannelRequest request
    ) {
        try {
            return ApiResponse.ok(repository.createGoodsChannel(id, request));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/goods/{id}/channels/{channelId}/delete")
    public ApiResponse<String> deleteGoodsChannel(@PathVariable Long id, @PathVariable Long channelId) {
        try {
            repository.deleteGoodsChannel(id, channelId);
            return ApiResponse.ok("deleted");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
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

    @PostMapping("/orders/{orderNo}/complete-manual")
    public ApiResponse<OrderItem> completeManual(@PathVariable String orderNo) {
        try {
            return ApiResponse.ok(repository.completeManualOrder(orderNo));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/retry")
    public ApiResponse<OrderItem> retry(@PathVariable String orderNo) {
        try {
            return ApiResponse.ok(repository.retryProcurement(orderNo));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/retry-channel/{channelId}")
    public ApiResponse<OrderItem> retryWithChannel(@PathVariable String orderNo, @PathVariable Long channelId) {
        try {
            return ApiResponse.ok(repository.retryProcurementWithChannel(orderNo, channelId));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
    }

    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<OrderItem> refund(@PathVariable String orderNo) {
        try {
            return ApiResponse.ok(repository.refundOrder(orderNo));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ApiResponse.fail(ex.getMessage());
        }
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
}
