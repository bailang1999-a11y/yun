package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InMemoryShopRepository {
    private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(15);

    private final Map<Long, CategoryItem> categories = new ConcurrentHashMap<>();
    private final Map<Long, GoodsItem> goods = new ConcurrentHashMap<>();
    private final Map<Long, CardSecret> cards = new ConcurrentHashMap<>();
    private final Map<String, OrderItem> orders = new ConcurrentHashMap<>();
    private final Map<String, PaymentItem> payments = new ConcurrentHashMap<>();
    private final Map<String, RefundItem> refunds = new ConcurrentHashMap<>();
    private final Map<Long, SmsLogItem> smsLogs = new ConcurrentHashMap<>();
    private final Map<Long, OperationLogItem> operationLogs = new ConcurrentHashMap<>();
    private final Map<Long, SupplierItem> suppliers = new ConcurrentHashMap<>();
    private final Map<Long, GoodsChannelItem> goodsChannels = new ConcurrentHashMap<>();
    private final Map<Long, UserGroupItem> userGroups = new ConcurrentHashMap<>();
    private final Map<Long, UserItem> users = new ConcurrentHashMap<>();
    private final Map<String, MemberApiCredentialItem> memberCredentials = new ConcurrentHashMap<>();
    private final Set<String> memberNonces = ConcurrentHashMap.newKeySet();
    private final Map<Long, OpenApiLogItem> openApiLogs = new ConcurrentHashMap<>();
    private final Map<String, GroupRuleItem> groupRules = new ConcurrentHashMap<>();
    private final Map<String, Long> userTokens = new ConcurrentHashMap<>();
    private final Map<String, AdminProfile> adminTokens = new ConcurrentHashMap<>();
    private volatile SystemSettingItem systemSetting = new SystemSettingItem(
        "喜易云",
        "",
        "工作日 09:00-23:00 在线客服",
        "MOCK",
        true,
        "TENCENT",
        false,
        30,
        true,
        false,
        Map.of("ops", "ops@example.com")
    );
    private final Set<String> viewedDeliveryOrders = ConcurrentHashMap.newKeySet();
    private final AtomicLong goodsId = new AtomicLong(10003);
    private final AtomicLong cardId = new AtomicLong(1);
    private final AtomicLong orderSeq = new AtomicLong(1);
    private final AtomicLong paymentSeq = new AtomicLong(1);
    private final AtomicLong refundSeq = new AtomicLong(1);
    private final AtomicLong smsLogId = new AtomicLong(1);
    private final AtomicLong operationLogId = new AtomicLong(1);
    private final AtomicLong categoryId = new AtomicLong(40000);
    private final AtomicLong supplierId = new AtomicLong(20002);
    private final AtomicLong channelId = new AtomicLong(30002);
    private final AtomicLong userId = new AtomicLong(90003);
    private final AtomicLong openApiLogId = new AtomicLong(1);
    private final OrderRealtimeBroadcaster realtimeBroadcaster;

    public InMemoryShopRepository(OrderRealtimeBroadcaster realtimeBroadcaster) {
        this.realtimeBroadcaster = realtimeBroadcaster;
        seedCategories();
        seedUserGroups();
        seedSuppliers();
        seedGoods();
        seedGoodsChannels();
        seedUsers();
        seedMemberCredentials();
        importCards(10001L, new CardImportRequest(List.of(
            "VIP-7D-ALPHA----8F2K",
            "VIP-7D-BRAVO----6P9Q",
            "VIP-7D-CHARLIE--3M7N",
            "VIP-7D-DELTA----1K5T"
        ), null));
    }

    public List<CategoryItem> listCategories() {
        return categories.values().stream()
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .toList();
    }

    public synchronized CategoryItem createCategory(CreateCategoryRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("category name is required");
        }
        Long parentId = request.parentId() == null ? 0L : request.parentId();
        if (parentId != 0L && !categories.containsKey(parentId)) {
            throw new IllegalArgumentException("parent category not found");
        }
        int level = categoryLevel(parentId);
        if (level >= 5) {
            throw new IllegalStateException("category depth cannot exceed 5");
        }
        Long id = categoryId.incrementAndGet();
        CategoryItem item = new CategoryItem(
            id,
            request.name().trim(),
            parentId,
            request.sort() == null ? (int) (id % 1000) : request.sort(),
            request.enabled() == null || request.enabled()
        );
        categories.put(id, item);
        return item;
    }

    public synchronized CategoryItem updateCategoryStatus(Long id, boolean enabled) {
        CategoryItem item = categories.get(id);
        if (item == null) {
            throw new IllegalArgumentException("category not found");
        }
        CategoryItem next = new CategoryItem(item.id(), item.name(), item.parentId(), item.sort(), enabled);
        categories.put(id, next);
        return next;
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, boolean admin) {
        return listGoods(categoryId, search, platform, null, admin);
    }

    public List<GoodsItem> listGoods(Long categoryId, String search, String platform, Long userGroupId, boolean admin) {
        String keyword = normalize(search);
        String normalizedPlatform = normalize(platform);
        Set<Long> categoryScope = categoryId == null ? Set.of() : categoryTreeIds(categoryId);
        List<GroupRuleItem> activeRules = admin ? List.of() : rulesForGroup(userGroupId == null ? 1L : userGroupId);
        return goods.values().stream()
            .filter(item -> admin || "ON_SALE".equals(item.status()))
            .filter(item -> categoryId == null || categoryScope.contains(item.categoryId()))
            .filter(item -> admin || !StringUtils.hasText(normalizedPlatform) || item.availablePlatforms().stream().map(this::normalize).anyMatch(normalizedPlatform::equals))
            .filter(item -> admin || allowedByGroupRules(item, normalizedPlatform, activeRules))
            .filter(item -> !StringUtils.hasText(keyword) || containsKeyword(item, keyword))
            .map(this::refreshStock)
            .sorted(Comparator.comparing(GoodsItem::id))
            .toList();
    }

    public Optional<GoodsItem> findGoods(Long id) {
        return Optional.ofNullable(goods.get(id)).map(this::refreshStock);
    }

    public SystemSettingItem systemSetting() {
        return systemSetting;
    }

    public synchronized SystemSettingItem updateSystemSetting(UpdateSystemSettingRequest request) {
        if (request == null) {
            return systemSetting;
        }
        systemSetting = new SystemSettingItem(
            defaultText(request.siteName(), systemSetting.siteName()),
            defaultText(request.logoUrl(), systemSetting.logoUrl()),
            defaultText(request.customerService(), systemSetting.customerService()),
            defaultText(request.paymentMode(), systemSetting.paymentMode()),
            request.autoRefundEnabled() == null ? systemSetting.autoRefundEnabled() : request.autoRefundEnabled(),
            defaultText(request.smsProvider(), systemSetting.smsProvider()),
            request.smsEnabled() == null ? systemSetting.smsEnabled() : request.smsEnabled(),
            Math.max(5, request.upstreamSyncSeconds() == null ? systemSetting.upstreamSyncSeconds() : request.upstreamSyncSeconds()),
            request.autoShelfEnabled() == null ? systemSetting.autoShelfEnabled() : request.autoShelfEnabled(),
            request.autoPriceEnabled() == null ? systemSetting.autoPriceEnabled() : request.autoPriceEnabled(),
            request.notificationReceivers() == null ? systemSetting.notificationReceivers() : Map.copyOf(request.notificationReceivers())
        );
        return systemSetting;
    }

    public synchronized AuthSession<UserItem> loginUser(LoginRequest request) {
        String account = normalize(request == null ? "" : request.account());
        if (!StringUtils.hasText(account)) {
            throw new IllegalArgumentException("account is required");
        }
        UserItem user = users.values().stream()
            .filter(item -> Objects.equals(normalize(item.mobile()), account) || Objects.equals(normalize(item.email()), account))
            .findFirst()
            .orElseGet(() -> createUserFromAccount(account));
        String token = "h5_" + UUID.randomUUID();
        userTokens.put(token, user.id());
        return new AuthSession<>(token, withGroupName(user));
    }

    public Optional<UserItem> findUserByToken(String token) {
        Long userId = userTokens.get(cleanBearerToken(token));
        return userId == null ? Optional.empty() : Optional.ofNullable(users.get(userId)).map(this::withGroupName);
    }

    public AuthSession<AdminProfile> loginAdmin(LoginRequest request) {
        String account = normalize(request == null ? "" : request.account());
        String password = request == null ? "" : request.password();
        if (!"admin".equals(account) || !"admin123".equals(password)) {
            throw new IllegalArgumentException("admin account or password is invalid");
        }
        AdminProfile profile = new AdminProfile(
            1L,
            "admin",
            "运营管理员",
            List.of("dashboard:read", "goods:manage", "orders:manage", "users:manage", "settings:manage")
        );
        String token = "admin_" + UUID.randomUUID();
        adminTokens.put(token, profile);
        return new AuthSession<>(token, profile);
    }

    public Optional<AdminProfile> findAdminByToken(String token) {
        return Optional.ofNullable(adminTokens.get(cleanBearerToken(token)));
    }

    public List<UserGroupItem> listUserGroups() {
        return userGroups.values().stream()
            .map(group -> new UserGroupItem(
                group.id(),
                group.name(),
                group.description(),
                group.defaultGroup(),
                (int) users.values().stream().filter(user -> Objects.equals(user.groupId(), group.id())).count(),
                group.status(),
                rulesForGroup(group.id())
            ))
            .sorted(Comparator.comparing(UserGroupItem::id))
            .toList();
    }

    public List<UserItem> listUsers() {
        return users.values().stream()
            .map(this::withGroupName)
            .sorted(Comparator.comparing(UserItem::id))
            .toList();
    }

    public synchronized List<GroupRuleItem> updateGroupRules(Long groupId, UpdateGroupRulesRequest request) {
        if (!userGroups.containsKey(groupId)) {
            throw new IllegalArgumentException("user group not found");
        }
        String ruleType = normalizeRuleType(request == null ? "" : request.ruleType());
        if (!"CATEGORY".equals(ruleType) && !"PLATFORM".equals(ruleType)) {
            throw new IllegalArgumentException("ruleType must be CATEGORY or PLATFORM");
        }

        groupRules.keySet().removeIf(key -> key.startsWith(groupId + ":" + ruleType + ":"));
        if (request != null && request.rules() != null) {
            for (GroupRulePatch patch : request.rules()) {
                String permission = normalizePermission(patch.permission());
                if ("NONE".equals(permission)) {
                    continue;
                }
                GroupRuleItem item = createRule(groupId, ruleType, patch, permission);
                groupRules.put(ruleKey(item), item);
            }
        }
        return rulesForGroup(groupId);
    }

    public synchronized UserItem updateUserGroup(Long userId, UpdateUserGroupRequest request) {
        UserItem user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        Long groupId = request == null || request.groupId() == null ? 1L : request.groupId();
        if (!userGroups.containsKey(groupId)) {
            throw new IllegalArgumentException("user group not found");
        }
        UserItem next = new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            groupId,
            groupName(groupId),
            user.balance(),
            user.status(),
            user.createdAt()
        );
        users.put(userId, next);
        return next;
    }

    public List<GoodsChannelItem> listGoodsChannels(Long targetGoodsId) {
        if (!goods.containsKey(targetGoodsId)) {
            throw new IllegalArgumentException("goods not found");
        }
        return goodsChannels.values().stream()
            .filter(item -> Objects.equals(item.goodsId(), targetGoodsId))
            .sorted(Comparator.comparing(GoodsChannelItem::priority).thenComparing(GoodsChannelItem::id))
            .toList();
    }

    public synchronized GoodsChannelItem createGoodsChannel(Long targetGoodsId, CreateGoodsChannelRequest request) {
        GoodsItem targetGoods = goods.get(targetGoodsId);
        if (targetGoods == null) {
            throw new IllegalArgumentException("goods not found");
        }
        if (targetGoods.type() != GoodsType.DIRECT) {
            throw new IllegalStateException("only direct goods can bind supplier channels");
        }
        if (request == null || request.supplierId() == null) {
            throw new IllegalArgumentException("supplierId is required");
        }
        SupplierItem supplier = requiredSupplier(request.supplierId());
        if (!StringUtils.hasText(request.supplierGoodsId())) {
            throw new IllegalArgumentException("supplierGoodsId is required");
        }
        Long id = channelId.incrementAndGet();
        GoodsChannelItem item = new GoodsChannelItem(
            id,
            targetGoodsId,
            supplier.id(),
            supplier.name(),
            request.supplierGoodsId().trim(),
            request.priority() == null ? 10 : request.priority(),
            request.timeoutSeconds() == null ? 30 : request.timeoutSeconds(),
            defaultText(request.status(), "ENABLED"),
            OffsetDateTime.now()
        );
        goodsChannels.put(id, item);
        return item;
    }

    public synchronized void deleteGoodsChannel(Long targetGoodsId, Long targetChannelId) {
        GoodsChannelItem item = goodsChannels.get(targetChannelId);
        if (item == null || !Objects.equals(item.goodsId(), targetGoodsId)) {
            throw new IllegalArgumentException("channel not found");
        }
        goodsChannels.remove(targetChannelId);
    }

    public List<SupplierItem> listSuppliers() {
        return suppliers.values().stream()
            .sorted(Comparator.comparing(SupplierItem::id))
            .toList();
    }

    public synchronized SupplierItem createSupplier(CreateSupplierRequest request) {
        if (request == null || !StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("supplier name is required");
        }
        Long id = supplierId.incrementAndGet();
        SupplierItem item = new SupplierItem(
            id,
            request.name().trim(),
            defaultText(request.baseUrl(), "https://supplier.example.com/api"),
            defaultText(request.appKey(), "demo-app-key"),
            mask(defaultText(request.appSecret(), "demo-secret")),
            request.balance() == null ? BigDecimal.valueOf(1000) : request.balance(),
            defaultText(request.status(), "ENABLED"),
            defaultText(request.remark(), ""),
            OffsetDateTime.now()
        );
        suppliers.put(id, item);
        return item;
    }

    public synchronized SupplierItem updateSupplierStatus(Long id, boolean enabled) {
        SupplierItem item = requiredSupplier(id);
        SupplierItem next = item.withStatus(enabled ? "ENABLED" : "DISABLED");
        suppliers.put(id, next);
        return next;
    }

    public synchronized SupplierItem refreshSupplierBalance(Long id) {
        SupplierItem item = requiredSupplier(id);
        BigDecimal nextBalance = item.balance().add(BigDecimal.valueOf(12.34));
        SupplierItem next = item.withBalance(nextBalance);
        suppliers.put(id, next);
        return next;
    }

    public SupplierItem testSupplierConnection(Long id) {
        SupplierItem item = requiredSupplier(id);
        if (!"ENABLED".equals(item.status())) {
            throw new IllegalStateException("supplier is disabled");
        }
        return item.withBalance(item.balance());
    }

    public List<OrderItem> listOrders() {
        return listOrders(null, null, null);
    }

    public List<OrderItem> listOrdersForUser(Long userId) {
        expireStaleUnpaidOrders();
        return orders.values().stream()
            .filter(order -> Objects.equals(order.userId(), userId))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
    }

    public List<OrderItem> listOrders(String search, String status, String goodsType) {
        expireStaleUnpaidOrders();
        String keyword = normalize(search);
        String normalizedStatus = normalize(status);
        String normalizedGoodsType = normalize(goodsType);

        return orders.values().stream()
            .filter(order -> !StringUtils.hasText(keyword) || containsOrderKeyword(order, keyword))
            .filter(order -> !StringUtils.hasText(normalizedStatus) || normalize(String.valueOf(order.status())).equals(normalizedStatus))
            .filter(order -> !StringUtils.hasText(normalizedGoodsType) || normalize(String.valueOf(order.goodsType())).equals(normalizedGoodsType))
            .sorted(Comparator.comparing(OrderItem::createdAt).reversed())
            .toList();
    }

    public Optional<OrderItem> findOrder(String orderNo) {
        OrderItem order = orders.get(orderNo);
        return Optional.ofNullable(order == null ? null : expireOrderIfNeeded(order, OffsetDateTime.now()));
    }

    public Optional<OrderItem> findOrderForUser(String orderNo, Long userId) {
        return findOrder(orderNo).filter(order -> Objects.equals(order.userId(), userId));
    }

    public Optional<PaymentItem> findPaymentForUser(String paymentNo, Long userId) {
        PaymentItem payment = payments.get(paymentNo);
        return Optional.ofNullable(payment == null || !Objects.equals(payment.userId(), userId) ? null : payment);
    }

    public List<PaymentItem> listPayments() {
        return payments.values().stream()
            .sorted(Comparator.comparing(PaymentItem::createdAt).reversed())
            .toList();
    }

    public List<RefundItem> listRefunds() {
        return refunds.values().stream()
            .sorted(Comparator.comparing(RefundItem::createdAt).reversed())
            .toList();
    }

    public List<SmsLogItem> listSmsLogs() {
        return smsLogs.values().stream()
            .sorted(Comparator.comparing(SmsLogItem::createdAt).reversed())
            .toList();
    }

    public List<OperationLogItem> listOperationLogs() {
        return operationLogs.values().stream()
            .sorted(Comparator.comparing(OperationLogItem::createdAt).reversed())
            .toList();
    }

    public List<OpenApiLogItem> listOpenApiLogs() {
        return openApiLogs.values().stream()
            .sorted(Comparator.comparing(OpenApiLogItem::createdAt).reversed())
            .toList();
    }

    public List<MemberApiCredentialItem> listMemberCredentials() {
        return memberCredentials.values().stream()
            .sorted(Comparator.comparing(MemberApiCredentialItem::id))
            .toList();
    }

    public UserItem authenticateMemberApi(String appKey, String timestamp, String nonce, String signature, String path) {
        MemberApiCredentialItem credential = memberCredentials.get(appKey);
        if (credential == null || !"ENABLED".equals(credential.status())) {
            appendOpenApiLog(null, appKey, path, "FAILED", "invalid app key");
            throw new IllegalArgumentException("invalid app key");
        }
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "missing signature headers");
            throw new IllegalArgumentException("missing signature headers");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "invalid timestamp");
            throw new IllegalArgumentException("invalid timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > 300) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "timestamp expired");
            throw new IllegalArgumentException("timestamp expired");
        }
        String nonceKey = appKey + ":" + nonce;
        if (!memberNonces.add(nonceKey)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "nonce replay");
            throw new IllegalArgumentException("nonce replay");
        }
        String payload = timestamp + "\n" + nonce + "\n" + path;
        if (!constantTimeEquals(hmacSha256(credential.appSecret(), payload), signature)) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "invalid signature");
            throw new IllegalArgumentException("invalid signature");
        }
        UserItem user = users.get(credential.userId());
        if (user == null) {
            appendOpenApiLog(credential.userId(), appKey, path, "FAILED", "user not found");
            throw new IllegalArgumentException("user not found");
        }
        appendOpenApiLog(user.id(), appKey, path, "SUCCESS", "ok");
        return withGroupName(user);
    }

    public synchronized OrderItem createMemberOrder(CreateOrderRequest request, Long userId) {
        OrderItem order = createOrder(request, userId);
        UserItem user = requiredUser(userId);
        if (user.balance().compareTo(order.payAmount()) < 0) {
            orders.remove(order.orderNo());
            throw new IllegalStateException("balance is insufficient");
        }
        users.put(userId, new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            user.balance().subtract(order.payAmount()),
            user.status(),
            user.createdAt()
        ));
        return payOrder(order.orderNo(), userId, new PayOrderRequest("balance", "member-api"));
    }

    public synchronized OrderItem handlePaymentCallback(String provider, PaymentCallbackRequest request) {
        String paymentNo = request == null ? "" : defaultText(request.paymentNo(), "");
        PaymentItem payment = payments.get(paymentNo);
        if (payment == null) {
            throw new IllegalArgumentException("payment not found");
        }
        OrderItem order = requiredOrder(payment.orderNo());
        if ("SUCCESS".equals(payment.status())) {
            appendOperation("PAYMENT_CALLBACK_IDEMPOTENT", "PAYMENT", payment.paymentNo(), provider + " duplicate callback ignored");
            return order;
        }
        if (!"SUCCESS".equalsIgnoreCase(defaultText(request.status(), ""))) {
            PaymentItem failed = new PaymentItem(
                payment.paymentNo(),
                payment.orderNo(),
                payment.userId(),
                payment.method(),
                payment.amount(),
                "FAILED",
                defaultText(request.channelTradeNo(), payment.channelTradeNo()),
                payment.createdAt(),
                null
            );
            payments.put(failed.paymentNo(), failed);
            appendOperation("PAYMENT_CALLBACK_FAILED", "PAYMENT", failed.paymentNo(), provider + " callback marked failed");
            return order;
        }
        OffsetDateTime paidAt = OffsetDateTime.now();
        PaymentItem paid = new PaymentItem(
            payment.paymentNo(),
            payment.orderNo(),
            payment.userId(),
            payment.method(),
            payment.amount(),
            "SUCCESS",
            defaultText(request.channelTradeNo(), payment.channelTradeNo()),
            payment.createdAt(),
            paidAt
        );
        payments.put(paid.paymentNo(), paid);
        appendOperation("PAYMENT_CALLBACK_SUCCESS", "PAYMENT", paid.paymentNo(), provider + " callback accepted");
        return dispatchPaidOrder(order.withPayment(paid.paymentNo(), paid.method()), paidAt);
    }

    public synchronized OrderItem completeManualOrder(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        if (order.status() != OrderStatus.WAITING_MANUAL) {
            throw new IllegalStateException("only waiting manual orders can be completed");
        }
        OrderItem next = order.withStatus(
            OrderStatus.DELIVERED,
            "管理员已确认人工充值完成",
            OffsetDateTime.now()
        );
        orders.put(orderNo, next);
        appendOperation("ORDER_COMPLETE_MANUAL", "ORDER", orderNo, "manual order completed");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem retryProcurement(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        if (order.goodsType() != GoodsType.DIRECT) {
            throw new IllegalStateException("only direct orders can be retried");
        }
        if (order.status() != OrderStatus.FAILED && order.status() != OrderStatus.PROCURING) {
            throw new IllegalStateException("only failed or procuring orders can be retried");
        }
        OrderItem next = procureWithFallback(order, "管理员手动重试");
        orders.put(orderNo, next);
        appendOperation("ORDER_RETRY", "ORDER", orderNo, "manual retry");
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem retryProcurementWithChannel(String orderNo, Long channelId) {
        OrderItem order = requiredOrder(orderNo);
        if (order.goodsType() != GoodsType.DIRECT) {
            throw new IllegalStateException("only direct orders can be retried");
        }
        if (order.status() != OrderStatus.FAILED && order.status() != OrderStatus.PROCURING) {
            throw new IllegalStateException("only failed or procuring orders can be retried");
        }
        GoodsChannelItem channel = goodsChannels.get(channelId);
        if (channel == null || !Objects.equals(channel.goodsId(), order.goodsId())) {
            throw new IllegalArgumentException("channel not found");
        }

        List<ChannelAttemptItem> attempts = new ArrayList<>(order.channelAttempts());
        SupplierItem supplier = suppliers.get(channel.supplierId());
        if (supplier == null) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商不存在"));
        } else if (!"ENABLED".equals(channel.status())) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：渠道已停用"));
        } else if (!"ENABLED".equals(supplier.status())) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商已停用"));
        } else if (supplier.balance().compareTo(order.payAmount()) < 0) {
            attempts.add(attempt(channel, "FAILED", "指定渠道失败：供应商余额不足"));
        } else {
            attempts.add(attempt(channel, "SUCCESS", "指定渠道采购成功，模拟充值已完成"));
            OrderItem delivered = order.withProcurementResult(
                OrderStatus.DELIVERED,
                List.of("直充成功：" + channel.supplierName() + " / " + channel.supplierGoodsId()),
                List.copyOf(attempts),
                "指定渠道重试成功：已通过 " + channel.supplierName() + " 完成充值",
                order.paidAt() == null ? OffsetDateTime.now() : order.paidAt(),
                OffsetDateTime.now()
            );
            orders.put(orderNo, delivered);
            appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel retry succeeded");
            publishOrder(delivered);
            return delivered;
        }

        OrderItem failed = order.withProcurementResult(
            OrderStatus.FAILED,
            List.of(),
            List.copyOf(attempts),
            "指定渠道重试失败：" + channel.supplierName() + " / " + channel.supplierGoodsId(),
            order.paidAt(),
            null
        );
        orders.put(orderNo, failed);
        appendOperation("ORDER_RETRY_CHANNEL", "ORDER", orderNo, "specific channel retry failed");
        publishOrder(failed);
        return failed;
    }

    public synchronized OrderItem refundOrder(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        if (order.status() == OrderStatus.REFUNDED) {
            return order;
        }
        if (order.status() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("order cannot be refunded");
        }
        createRefund(order, "管理员手动退款");
        OrderItem next = order.withStatus(
            OrderStatus.REFUNDED,
            "管理员已执行模拟退款",
            order.deliveredAt()
        );
        orders.put(orderNo, next);
        appendOperation("ORDER_REFUND", "ORDER", orderNo, "manual refund");
        publishOrder(next);
        return next;
    }

    public synchronized GoodsItem createGoods(CreateGoodsRequest request) {
        Long id = goodsId.incrementAndGet();
        Long categoryId = request.categoryId() == null ? 1L : request.categoryId();
        CategoryItem category = categories.get(categoryId);
        OffsetDateTime now = OffsetDateTime.now();
        GoodsType type = request.type() == null ? GoodsType.CARD : request.type();
        GoodsItem item = new GoodsItem(
            id,
            categoryId,
            category == null ? "未分类" : category.name(),
            firstText(request.goodsName(), request.name(), "新商品 " + id),
            firstText(request.name(), request.goodsName(), "新商品 " + id),
            defaultText(request.subTitle(), "MVP 内存商品"),
            defaultText(request.description(), "这是一个用于前端联调的内存商品。"),
            defaultText(request.coverUrl(), "https://images.unsplash.com/photo-1556742049-0cfed4f6a45d?auto=format&fit=crop&w=800&q=80"),
            normalizeImages(request.detailImages()),
            normalizeDetailBlocks(request.detailBlocks()),
            type,
            defaultText(request.platform(), "GENERAL"),
            request.price() == null ? BigDecimal.valueOf(9.90) : request.price(),
            request.originalPrice() == null ? BigDecimal.valueOf(19.90) : request.originalPrice(),
            request.maxBuy() == null ? 1 : Math.max(1, request.maxBuy()),
            Boolean.TRUE.equals(request.requireRechargeAccount()),
            normalizeTextList(request.accountTypes()),
            defaultText(request.priceTemplateId(), "retail-default"),
            defaultText(request.priceMode(), "FIXED"),
            request.priceCoefficient() == null ? BigDecimal.ONE : request.priceCoefficient(),
            request.priceFixedAdd() == null ? BigDecimal.ZERO : request.priceFixedAdd(),
            request.stock() == null ? 0 : request.stock(),
            0,
            defaultText(request.status(), "ON_SALE"),
            request.tags() == null ? List.of("new") : List.copyOf(request.tags()),
            now,
            now,
            normalizePlatforms(request.availablePlatforms()),
            normalizePlatforms(request.forbiddenPlatforms())
        );
        goods.put(id, item);
        return refreshStock(item);
    }

    public synchronized GoodsItem updateGoods(Long id, CreateGoodsRequest request) {
        GoodsItem current = goods.get(id);
        if (current == null) {
            throw new IllegalArgumentException("goods not found");
        }
        Long categoryId = request.categoryId() == null ? current.categoryId() : request.categoryId();
        CategoryItem category = categories.get(categoryId);
        GoodsType type = request.type() == null ? current.type() : request.type();
        GoodsItem next = new GoodsItem(
            current.id(),
            categoryId,
            category == null ? current.categoryName() : category.name(),
            firstText(request.goodsName(), request.name(), current.goodsName()),
            firstText(request.name(), request.goodsName(), current.name()),
            defaultText(request.subTitle(), current.subTitle()),
            defaultText(request.description(), current.description()),
            defaultText(request.coverUrl(), current.coverUrl()),
            request.detailImages() == null ? current.detailImages() : normalizeImages(request.detailImages()),
            request.detailBlocks() == null ? current.detailBlocks() : normalizeDetailBlocks(request.detailBlocks()),
            type,
            defaultText(request.platform(), current.platform()),
            request.price() == null ? current.price() : request.price(),
            request.originalPrice() == null ? current.originalPrice() : request.originalPrice(),
            request.maxBuy() == null ? current.maxBuy() : Math.max(1, request.maxBuy()),
            request.requireRechargeAccount() == null ? current.requireRechargeAccount() : request.requireRechargeAccount(),
            request.accountTypes() == null ? current.accountTypes() : normalizeTextList(request.accountTypes()),
            defaultText(request.priceTemplateId(), current.priceTemplateId()),
            defaultText(request.priceMode(), current.priceMode()),
            request.priceCoefficient() == null ? current.priceCoefficient() : request.priceCoefficient(),
            request.priceFixedAdd() == null ? current.priceFixedAdd() : request.priceFixedAdd(),
            request.stock() == null ? current.stock() : request.stock(),
            current.sales(),
            defaultText(request.status(), current.status()),
            request.tags() == null ? current.tags() : List.copyOf(request.tags()),
            current.createdAt(),
            OffsetDateTime.now(),
            request.availablePlatforms() == null ? current.availablePlatforms() : normalizePlatforms(request.availablePlatforms()),
            request.forbiddenPlatforms() == null ? current.forbiddenPlatforms() : normalizePlatforms(request.forbiddenPlatforms())
        );
        goods.put(id, next);
        return refreshStock(next);
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request) {
        return createOrder(request, 90001L);
    }

    public synchronized OrderItem createOrder(CreateOrderRequest request, Long userId) {
        UserItem user = users.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("user not found");
        }
        if (request.goodsId() == null) {
            throw new IllegalArgumentException("goodsId is required");
        }
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }

        GoodsItem item = goods.get(request.goodsId());
        if (item == null || !"ON_SALE".equals(item.status())) {
            throw new IllegalArgumentException("goods not found");
        }

        OffsetDateTime now = OffsetDateTime.now();
        String orderNo = nextOrderNo();
        OrderItem order = buildOrder(
            orderNo,
            user,
            item,
            quantity,
            request,
            List.of(),
            OrderStatus.UNPAID,
            "订单已创建，等待支付",
            now,
            null,
            null
        );
        orders.put(orderNo, order);
        publishOrder(order);
        return order;
    }

    public synchronized OrderItem payOrder(String orderNo) {
        return payOrder(orderNo, null, null);
    }

    public synchronized OrderItem payOrder(String orderNo, Long userId, PayOrderRequest request) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        if (order.status() != OrderStatus.UNPAID && order.status() != OrderStatus.CREATED) {
            return order;
        }

        OffsetDateTime paidAt = OffsetDateTime.now();
        String method = normalizePayMethod(request == null ? "" : request.payMethod());
        PaymentItem payment = createSuccessfulPayment(order, method, paidAt);
        appendOperation("PAYMENT_CREATE", "PAYMENT", payment.paymentNo(), "mock payment succeeded");
        return dispatchPaidOrder(order.withPayment(payment.paymentNo(), payment.method()), paidAt);
    }

    private OrderItem dispatchPaidOrder(OrderItem order, OffsetDateTime paidAt) {
        if (order.goodsType() == GoodsType.CARD) {
            return deliverCardsAfterPayment(order, paidAt);
        }
        if (order.goodsType() == GoodsType.DIRECT) {
            OrderItem paidOrder = order.withProcurementResult(
                OrderStatus.PROCURING,
                List.of(),
                List.of(),
                "支付成功，直充订单进入采购流程",
                paidAt,
                null
            );
            OrderItem next = procureWithFallback(paidOrder, "系统自动采购");
            orders.put(order.orderNo(), next);
            publishOrder(next);
            return next;
        }

        OrderItem next = order.withProcurementResult(
            OrderStatus.WAITING_MANUAL,
            List.of(),
            List.of(),
            "支付成功，订单等待人工处理",
            paidAt,
            null
        );
        orders.put(order.orderNo(), next);
        publishOrder(next);
        return next;
    }

    public synchronized OrderItem cancelOrder(String orderNo) {
        return cancelOrder(orderNo, null);
    }

    public synchronized OrderItem cancelOrder(String orderNo, Long userId) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        if (order.status() != OrderStatus.UNPAID && order.status() != OrderStatus.CREATED) {
            throw new IllegalStateException("only unpaid orders can be cancelled");
        }
        OrderItem next = order.withStatus(
            OrderStatus.CANCELLED,
            "订单已取消",
            order.deliveredAt()
        );
        orders.put(orderNo, next);
        publishOrder(next);
        return next;
    }

    private OrderItem deliverCardsAfterPayment(OrderItem order, OffsetDateTime paidAt) {
        List<CardSecret> available = cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), order.goodsId()))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .sorted(Comparator.comparing(CardSecret::id))
            .limit(order.quantity())
            .toList();
        if (available.size() < order.quantity()) {
            OrderItem failed = order.withProcurementResult(
                OrderStatus.FAILED,
                List.of(),
                List.of(),
                "支付成功，但卡密库存不足，等待退款或补货处理",
                paidAt,
                null
            );
            orders.put(order.orderNo(), failed);
            if (systemSetting.autoRefundEnabled() && order.paymentNo() != null) {
                createRefund(order, "卡密库存不足自动退款");
                failed = failed.withStatus(OrderStatus.REFUNDED, "卡密库存不足，系统已自动退款", null);
                orders.put(order.orderNo(), failed);
            }
            publishOrder(failed);
            return failed;
        }

        List<String> deliveryItems = new ArrayList<>();
        for (CardSecret card : available) {
            CardSecret used = card.delivered(order.orderNo());
            cards.put(used.id(), used);
            deliveryItems.add(used.content());
        }

        OrderItem next = order.withProcurementResult(
            OrderStatus.DELIVERED,
            List.copyOf(deliveryItems),
            List.of(),
            "支付成功，卡密已自动发货",
            paidAt,
            OffsetDateTime.now()
        );
        orders.put(order.orderNo(), next);
        publishOrder(next);
        return next;
    }

    private OrderItem procureWithFallback(OrderItem order, String trigger) {
        List<GoodsChannelItem> channels = listGoodsChannels(order.goodsId()).stream()
            .filter(channel -> "ENABLED".equals(channel.status()))
            .toList();
        List<ChannelAttemptItem> attempts = new ArrayList<>();

        if (channels.isEmpty()) {
            ChannelAttemptItem attempt = new ChannelAttemptItem(
                null,
                null,
                "-",
                "-",
                0,
                "FAILED",
                "没有可用上游渠道",
                OffsetDateTime.now()
            );
            return order.withProcurementResult(
                OrderStatus.FAILED,
                List.of(),
                List.of(attempt),
                trigger + "失败：没有可用上游渠道",
                order.paidAt(),
                null
            );
        }

        for (GoodsChannelItem channel : channels) {
            SupplierItem supplier = suppliers.get(channel.supplierId());
            if (supplier == null) {
                attempts.add(attempt(channel, "FAILED", "供应商不存在"));
                continue;
            }
            if (!"ENABLED".equals(supplier.status())) {
                attempts.add(attempt(channel, "FAILED", "供应商已停用"));
                continue;
            }
            if (supplier.balance().compareTo(order.payAmount()) < 0) {
                attempts.add(attempt(channel, "FAILED", "供应商余额不足"));
                continue;
            }

            attempts.add(attempt(channel, "SUCCESS", "采购成功，模拟充值已完成"));
            return order.withProcurementResult(
                OrderStatus.DELIVERED,
                List.of("直充成功：" + channel.supplierName() + " / " + channel.supplierGoodsId()),
                List.copyOf(attempts),
                trigger + "成功：已通过 " + channel.supplierName() + " 完成充值",
                order.paidAt(),
                OffsetDateTime.now()
            );
        }

        return order.withProcurementResult(
            OrderStatus.FAILED,
            List.of(),
            List.copyOf(attempts),
            trigger + "失败：所有渠道均不可用",
            order.paidAt(),
            null
        );
    }

    private ChannelAttemptItem attempt(GoodsChannelItem channel, String status, String message) {
        return new ChannelAttemptItem(
            channel.id(),
            channel.supplierId(),
            channel.supplierName(),
            channel.supplierGoodsId(),
            channel.priority(),
            status,
            message,
            OffsetDateTime.now()
        );
    }

    public DeliveryResult deliveryResult(String orderNo) {
        OrderItem order = requiredOrder(orderNo);
        return deliveryResult(orderNo, order.userId());
    }

    public DeliveryResult deliveryResult(String orderNo, Long userId) {
        OrderItem order = requiredOrder(orderNo);
        if (userId != null && !Objects.equals(order.userId(), userId)) {
            throw new IllegalArgumentException("order not found");
        }
        List<DeliveryCardItem> deliveredCards = cards.values().stream()
            .filter(card -> order.orderNo().equals(card.orderNo()))
            .sorted(Comparator.comparing(CardSecret::id))
            .map(card -> new DeliveryCardItem(
                card.cardNo(),
                card.secret(),
                "卡密已显示，请尽快使用。系统不会在日志中记录明文。"
            ))
            .toList();
        boolean viewedBefore = !deliveredCards.isEmpty() && !viewedDeliveryOrders.add(order.orderNo());
        return new DeliveryResult(
            order.orderNo(),
            order.status(),
            order.goodsType(),
            order.rechargeAccount(),
            order.deliveryItems(),
            order.deliveryMessage(),
            deliveredCards,
            viewedBefore
        );
    }

    private OrderItem requiredOrder(String orderNo) {
        OrderItem order = orders.get(orderNo);
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        return expireOrderIfNeeded(order, OffsetDateTime.now());
    }

    private void expireStaleUnpaidOrders() {
        OffsetDateTime now = OffsetDateTime.now();
        orders.values().forEach(order -> expireOrderIfNeeded(order, now));
    }

    private OrderItem expireOrderIfNeeded(OrderItem order, OffsetDateTime now) {
        if (order.status() != OrderStatus.UNPAID && order.status() != OrderStatus.CREATED) {
            return order;
        }
        if (order.createdAt() == null || order.createdAt().plus(PAYMENT_TIMEOUT).isAfter(now)) {
            return order;
        }
        OrderItem expired = order.withStatus(
            OrderStatus.CANCELLED,
            "订单支付超时，已自动取消",
            order.deliveredAt()
        );
        orders.put(order.orderNo(), expired);
        publishOrder(expired);
        return expired;
    }

    private void publishOrder(OrderItem order) {
        appendSmsLogIfNeeded(order);
        realtimeBroadcaster.publish(order);
    }

    private void appendSmsLogIfNeeded(OrderItem order) {
        if (!List.of(OrderStatus.DELIVERED, OrderStatus.FAILED, OrderStatus.REFUNDED).contains(order.status())) {
            return;
        }
        boolean exists = smsLogs.values().stream()
            .anyMatch(log -> Objects.equals(log.orderNo(), order.orderNo()) && Objects.equals(log.templateType(), String.valueOf(order.status())));
        if (exists) {
            return;
        }
        String mobile = order.rechargeAccount();
        if (!StringUtils.hasText(mobile) || !mobile.matches("1\\d{10}")) {
            mobile = order.buyerAccount();
        }
        boolean validMobile = StringUtils.hasText(mobile) && mobile.matches("1\\d{10}");
        String content = "订单" + order.orderNo() + "状态：" + order.status() + "，商品：" + order.goodsName();
        String status = systemSetting.smsEnabled() && validMobile ? "SENT" : "SKIPPED";
        String error = systemSetting.smsEnabled() ? (validMobile ? "" : "手机号不可用") : "短信未启用";
        Long id = smsLogId.getAndIncrement();
        smsLogs.put(id, new SmsLogItem(id, order.orderNo(), validMobile ? mobile : "", String.valueOf(order.status()), content, status, error, OffsetDateTime.now()));
    }

    private SupplierItem requiredSupplier(Long id) {
        SupplierItem item = suppliers.get(id);
        if (item == null) {
            throw new IllegalArgumentException("supplier not found");
        }
        return item;
    }

    private UserItem requiredUser(Long id) {
        UserItem item = users.get(id);
        if (item == null) {
            throw new IllegalArgumentException("user not found");
        }
        return item;
    }

    public synchronized CardImportResult importCards(Long targetGoodsId, CardImportRequest request) {
        if (!goods.containsKey(targetGoodsId)) {
            throw new IllegalArgumentException("goods not found");
        }
        List<String> lines = cardLines(request);
        int duplicateCount = 0;
        List<Integer> failedLines = new ArrayList<>();
        Set<String> seenInRequest = new LinkedHashSet<>();
        Set<String> existing = new LinkedHashSet<>(cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), targetGoodsId))
            .map(CardSecret::content)
            .toList());

        int lineNo = 0;
        int successCount = 0;
        for (String raw : lines) {
            lineNo++;
            String content = raw == null ? "" : raw.trim();
            if (!StringUtils.hasText(content)) {
                failedLines.add(lineNo);
                continue;
            }
            if (existing.contains(content) || !seenInRequest.add(content)) {
                duplicateCount++;
                continue;
            }
            Long id = cardId.getAndIncrement();
            CardSecret card = new CardSecret(
                id,
                targetGoodsId,
                "CARD-" + id,
                mask(content),
                content,
                mask(content),
                "AVAILABLE",
                null,
                OffsetDateTime.now(),
                null
            );
            cards.put(id, card);
            successCount++;
        }
        refreshGoodsStock(targetGoodsId);
        return new CardImportResult(targetGoodsId, lines.size(), successCount, duplicateCount, List.copyOf(failedLines));
    }

    public List<CardSecret> listCards(Long goodsId) {
        return cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), goodsId))
            .map(card -> new CardSecret(
                card.id(),
                card.goodsId(),
                card.cardNo(),
                card.preview(),
                card.preview(),
                card.preview(),
                card.status(),
                card.orderNo(),
                card.importedAt(),
                card.deliveredAt()
            ))
            .sorted(Comparator.comparing(CardSecret::id))
            .toList();
    }

    private OrderItem buildOrder(
        String orderNo,
        UserItem user,
        GoodsItem item,
        int quantity,
        CreateOrderRequest request,
        List<String> deliveryItems,
        OrderStatus status,
        String deliveryMessage,
        OffsetDateTime createdAt,
        OffsetDateTime paidAt,
        OffsetDateTime deliveredAt
    ) {
        return new OrderItem(
            orderNo,
            user.id(),
            firstText(user.mobile(), user.email(), user.nickname()),
            item.id(),
            item.goodsName(),
            item.type(),
            item.platform(),
            quantity,
            item.price(),
            item.price().multiply(BigDecimal.valueOf(quantity)),
            status,
            request.rechargeAccount(),
            request.buyerRemark(),
            request.requestId(),
            null,
            null,
            List.copyOf(deliveryItems),
            List.of(),
            deliveryMessage,
            createdAt,
            paidAt,
            deliveredAt
        );
    }

    private String nextOrderNo() {
        return "XY" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", orderSeq.getAndIncrement());
    }

    private String nextPaymentNo() {
        return "PAY" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", paymentSeq.getAndIncrement());
    }

    private String nextRefundNo() {
        return "RF" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + String.format("%04d", refundSeq.getAndIncrement());
    }

    private PaymentItem createSuccessfulPayment(OrderItem order, String method, OffsetDateTime paidAt) {
        String paymentNo = nextPaymentNo();
        PaymentItem payment = new PaymentItem(
            paymentNo,
            order.orderNo(),
            order.userId(),
            method,
            order.payAmount(),
            "SUCCESS",
            "MOCK-" + paymentNo,
            paidAt,
            paidAt
        );
        payments.put(paymentNo, payment);
        return payment;
    }

    private RefundItem createRefund(OrderItem order, String reason) {
        Optional<RefundItem> existing = refunds.values().stream()
            .filter(refund -> Objects.equals(refund.orderNo(), order.orderNo()))
            .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        OffsetDateTime now = OffsetDateTime.now();
        RefundItem refund = new RefundItem(
            nextRefundNo(),
            order.orderNo(),
            order.paymentNo(),
            order.userId(),
            order.payAmount(),
            "SUCCESS",
            reason,
            now,
            now
        );
        refunds.put(refund.refundNo(), refund);
        appendOperation("REFUND_CREATE", "REFUND", refund.refundNo(), reason);
        return refund;
    }

    private void appendOperation(String action, String resourceType, String resourceId, String remark) {
        Long id = operationLogId.getAndIncrement();
        operationLogs.put(id, new OperationLogItem(
            id,
            "system",
            action,
            resourceType,
            resourceId,
            remark,
            OffsetDateTime.now()
        ));
    }

    private String normalizePayMethod(String value) {
        String normalized = normalize(value);
        if ("alipay".equals(normalized) || "wechat".equals(normalized) || "balance".equals(normalized)) {
            return normalized;
        }
        return "wechat";
    }

    private String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("signature calculation failed");
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            defaultText(actual, "").toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private void appendOpenApiLog(Long userId, String appKey, String path, String status, String message) {
        Long id = openApiLogId.getAndIncrement();
        openApiLogs.put(id, new OpenApiLogItem(
            id,
            userId,
            defaultText(appKey, ""),
            defaultText(path, ""),
            status,
            message,
            OffsetDateTime.now()
        ));
    }

    private GoodsItem refreshStock(GoodsItem item) {
        if (item.type() != GoodsType.CARD) {
            return item;
        }
        return item.withStock(availableCardCount(item.id()));
    }

    private void refreshGoodsStock(Long targetGoodsId) {
        GoodsItem item = goods.get(targetGoodsId);
        if (item != null && item.type() == GoodsType.CARD) {
            goods.put(item.id(), item.withStock(availableCardCount(item.id())));
        }
    }

    private int availableCardCount(Long targetGoodsId) {
        return (int) cards.values().stream()
            .filter(card -> Objects.equals(card.goodsId(), targetGoodsId))
            .filter(card -> "AVAILABLE".equals(card.status()))
            .count();
    }

    private boolean containsKeyword(GoodsItem item, String keyword) {
        return normalize(item.goodsName()).contains(keyword)
            || normalize(item.name()).contains(keyword)
            || normalize(item.subTitle()).contains(keyword)
            || normalize(item.description()).contains(keyword)
            || normalize(item.platform()).contains(keyword);
    }

    private Set<Long> categoryTreeIds(Long rootId) {
        Set<Long> result = new LinkedHashSet<>();
        collectCategoryTreeIds(rootId, result);
        return result;
    }

    private void collectCategoryTreeIds(Long parentId, Set<Long> result) {
        if (!result.add(parentId)) {
            return;
        }
        categories.values().stream()
            .filter(category -> Objects.equals(category.parentId(), parentId))
            .sorted(Comparator.comparing(CategoryItem::sort).thenComparing(CategoryItem::id))
            .forEach(category -> collectCategoryTreeIds(category.id(), result));
    }

    private boolean containsOrderKeyword(OrderItem order, String keyword) {
        return normalize(order.orderNo()).contains(keyword)
            || normalize(order.goodsName()).contains(keyword)
            || normalize(order.platform()).contains(keyword)
            || normalize(order.rechargeAccount()).contains(keyword)
            || normalize(order.requestId()).contains(keyword)
            || normalize(order.deliveryMessage()).contains(keyword);
    }

    private List<String> cardLines(CardImportRequest request) {
        List<String> result = new ArrayList<>();
        if (request != null && request.cards() != null) {
            result.addAll(request.cards());
        }
        if (request != null && StringUtils.hasText(request.text())) {
            result.addAll(request.text().lines().toList());
        }
        return result;
    }

    private String mask(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normalizePlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return List.of("private");
        }
        List<String> normalized = platforms.stream()
            .map(this::normalize)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        return normalized.isEmpty() ? List.of("private") : normalized;
    }

    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private List<GoodsDetailBlock> normalizeDetailBlocks(List<GoodsDetailBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
            .filter(Objects::nonNull)
            .map(block -> {
                String type = defaultText(block.type(), StringUtils.hasText(block.imageUrl()) ? "image" : "text").trim();
                String imageUrl = block.imageUrl() == null ? "" : block.imageUrl().trim();
                String text = block.text() == null ? "" : block.text().trim();
                return new GoodsDetailBlock(type, imageUrl, text);
            })
            .filter(block -> StringUtils.hasText(block.imageUrl()) || StringUtils.hasText(block.text()))
            .toList();
    }

    private List<String> normalizeTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .map(value -> value == null ? "" : value.trim())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String normalizeRuleType(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if ("CATEGORY".equals(normalized) || "PLATFORM".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizePermission(String value) {
        String normalized = normalize(value).toUpperCase(Locale.ROOT);
        if ("ALLOW".equals(normalized) || "DENY".equals(normalized)) {
            return normalized;
        }
        return "NONE";
    }

    private List<GroupRuleItem> rulesForGroup(Long groupId) {
        return groupRules.values().stream()
            .filter(rule -> Objects.equals(rule.groupId(), groupId))
            .sorted(Comparator.comparing(GroupRuleItem::ruleType)
                .thenComparing(rule -> defaultText(rule.targetName(), rule.targetCode())))
            .toList();
    }

    private GroupRuleItem createRule(Long groupId, String ruleType, GroupRulePatch patch, String permission) {
        if ("CATEGORY".equals(ruleType)) {
            Long targetId = patch.targetId();
            CategoryItem category = targetId == null ? null : categories.get(targetId);
            if (category == null) {
                throw new IllegalArgumentException("category rule target not found");
            }
            return new GroupRuleItem(groupId, ruleType, targetId, null, category.name(), permission);
        }

        String targetCode = normalize(patch.targetCode());
        if (!List.of("h5", "pc", "miniapp").contains(targetCode)) {
            throw new IllegalArgumentException("platform rule target not found");
        }
        return new GroupRuleItem(groupId, ruleType, null, targetCode, platformName(targetCode), permission);
    }

    private String ruleKey(GroupRuleItem rule) {
        String target = "CATEGORY".equals(rule.ruleType()) ? String.valueOf(rule.targetId()) : rule.targetCode();
        return rule.groupId() + ":" + rule.ruleType() + ":" + target;
    }

    private boolean allowedByGroupRules(GoodsItem item, String normalizedPlatform, List<GroupRuleItem> rules) {
        if (rules.isEmpty()) {
            return true;
        }
        boolean denied = rules.stream()
            .filter(rule -> "DENY".equals(rule.permission()))
            .anyMatch(rule -> ruleMatches(rule, item, normalizedPlatform));
        if (denied) {
            return false;
        }

        List<GroupRuleItem> allowRules = rules.stream()
            .filter(rule -> "ALLOW".equals(rule.permission()))
            .toList();
        return allowRules.isEmpty() || allowRules.stream().anyMatch(rule -> ruleMatches(rule, item, normalizedPlatform));
    }

    private boolean ruleMatches(GroupRuleItem rule, GoodsItem item, String normalizedPlatform) {
        if ("CATEGORY".equals(rule.ruleType())) {
            return rule.targetId() != null && categoryTreeIds(rule.targetId()).contains(item.categoryId());
        }
        if ("PLATFORM".equals(rule.ruleType())) {
            return StringUtils.hasText(normalizedPlatform) && Objects.equals(normalize(rule.targetCode()), normalizedPlatform);
        }
        return false;
    }

    private UserItem withGroupName(UserItem user) {
        return new UserItem(
            user.id(),
            user.avatar(),
            user.mobile(),
            user.email(),
            user.nickname(),
            user.groupId(),
            groupName(user.groupId()),
            user.balance(),
            user.status(),
            user.createdAt()
        );
    }

    private UserItem createUserFromAccount(String account) {
        Long id = userId.incrementAndGet();
        boolean email = account.contains("@");
        UserItem user = new UserItem(
            id,
            "",
            email ? "" : account,
            email ? account : "",
            email ? account.substring(0, account.indexOf("@")) : account,
            1L,
            groupName(1L),
            BigDecimal.ZERO,
            "NORMAL",
            OffsetDateTime.now()
        );
        users.put(id, user);
        return user;
    }

    private String cleanBearerToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        String trimmed = token.trim();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    private String groupName(Long groupId) {
        UserGroupItem group = userGroups.get(groupId);
        return group == null ? "未分组" : group.name();
    }

    private String platformName(String code) {
        return switch (normalize(code)) {
            case "h5" -> "移动 H5";
            case "pc" -> "PC 网页";
            case "miniapp" -> "微信小程序";
            default -> code;
        };
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        if (StringUtils.hasText(second)) {
            return second;
        }
        return fallback;
    }

    private void seedCategories() {
        categories.put(1L, new CategoryItem(1L, "会员权益", 0L, 10, true));
        categories.put(11L, new CategoryItem(11L, "视频平台", 1L, 11, true));
        categories.put(111L, new CategoryItem(111L, "月卡专区", 11L, 12, true));
        categories.put(1111L, new CategoryItem(1111L, "自动发卡", 111L, 13, true));
        categories.put(11111L, new CategoryItem(11111L, "会员周卡", 1111L, 14, true));
        categories.put(2L, new CategoryItem(2L, "游戏直充", 0L, 20, true));
        categories.put(22L, new CategoryItem(22L, "手游充值", 2L, 21, true));
        categories.put(222L, new CategoryItem(222L, "点券直充", 22L, 22, true));
        categories.put(2222L, new CategoryItem(2222L, "API 秒充", 222L, 23, true));
        categories.put(22222L, new CategoryItem(22222L, "热门大区", 2222L, 24, true));
        categories.put(3L, new CategoryItem(3L, "人工代办", 0L, 30, true));
        categories.put(33L, new CategoryItem(33L, "海外账号", 3L, 31, true));
        categories.put(333L, new CategoryItem(333L, "人工处理", 33L, 32, true));
    }

    private void seedUserGroups() {
        userGroups.put(1L, new UserGroupItem(1L, "默认会员", "注册后自动归入的基础用户组", true, 0, "ENABLED", List.of()));
        userGroups.put(2L, new UserGroupItem(2L, "渠道 VIP", "仅开放 H5，屏蔽人工代充类目", false, 0, "ENABLED", List.of()));
        userGroups.put(3L, new UserGroupItem(3L, "受限会员", "风控观察组，限制游戏直充和 PC 端购买", false, 0, "ENABLED", List.of()));

        groupRules.put("2:CATEGORY:333", new GroupRuleItem(2L, "CATEGORY", 333L, null, "人工处理", "DENY"));
        groupRules.put("2:PLATFORM:pc", new GroupRuleItem(2L, "PLATFORM", null, "pc", "PC 网页", "DENY"));
        groupRules.put("3:CATEGORY:2", new GroupRuleItem(3L, "CATEGORY", 2L, null, "游戏直充", "DENY"));
        groupRules.put("3:PLATFORM:pc", new GroupRuleItem(3L, "PLATFORM", null, "pc", "PC 网页", "DENY"));
    }

    private void seedUsers() {
        OffsetDateTime now = OffsetDateTime.now();
        users.put(90001L, new UserItem(
            90001L,
            "",
            "13800000001",
            "alpha@example.com",
            "Alpha 买家",
            1L,
            groupName(1L),
            BigDecimal.valueOf(128.66),
            "NORMAL",
            now.minusDays(8)
        ));
        users.put(90002L, new UserItem(
            90002L,
            "",
            "13800000002",
            "vip@example.com",
            "渠道 VIP",
            2L,
            groupName(2L),
            BigDecimal.valueOf(888.00),
            "NORMAL",
            now.minusDays(3)
        ));
        users.put(90003L, new UserItem(
            90003L,
            "",
            "13800000003",
            "risk@example.com",
            "受限会员",
            3L,
            groupName(3L),
            BigDecimal.valueOf(12.30),
            "FROZEN",
            now.minusDays(1)
        ));
    }

    private void seedMemberCredentials() {
        memberCredentials.put("demo_app_key", new MemberApiCredentialItem(
            1L,
            90002L,
            "demo_app_key",
            "demo_app_secret",
            "ENABLED",
            List.of(),
            1000,
            OffsetDateTime.now(),
            null
        ));
    }

    private void seedSuppliers() {
        OffsetDateTime now = OffsetDateTime.now();
        suppliers.put(20001L, new SupplierItem(
            20001L,
            "星河直充",
            "https://api.starcharge.example",
            "star-demo-key",
            "star****cret",
            BigDecimal.valueOf(2688.50),
            "ENABLED",
            "默认优先供应商，支持余额查询和模拟连接测试",
            now
        ));
        suppliers.put(20002L, new SupplierItem(
            20002L,
            "云桥货源",
            "https://api.bridge.example",
            "bridge-demo-key",
            "brid****cret",
            BigDecimal.valueOf(188.20),
            "ENABLED",
            "低余额示例，用于仪表盘告警",
            now
        ));
    }

    private void seedGoodsChannels() {
        goodsChannels.put(30001L, new GoodsChannelItem(
            30001L,
            10002L,
            20001L,
            "星河直充",
            "STAR-GAME-60",
            10,
            30,
            "ENABLED",
            OffsetDateTime.now()
        ));
        goodsChannels.put(30002L, new GoodsChannelItem(
            30002L,
            10002L,
            20002L,
            "云桥货源",
            "BRIDGE-GAME-60",
            20,
            45,
            "ENABLED",
            OffsetDateTime.now()
        ));
    }

    private int categoryLevel(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return 0;
        }
        CategoryItem parent = categories.get(parentId);
        if (parent == null) {
            return 0;
        }
        return categoryLevel(parent.parentId()) + 1;
    }

    private void seedGoods() {
        OffsetDateTime now = OffsetDateTime.now();
        goods.put(10001L, new GoodsItem(
            10001L,
            11111L,
            "会员周卡",
            "视频会员周卡",
            "视频会员周卡",
            "自动发卡，模拟支付后立即展示卡密",
            "适合前端联调自动发货链路的 CARD 商品。",
            "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1522869635100-9f4c5e86aa37?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "下单完成后自动出卡，订单详情页可查看卡密与使用说明。")),
            GoodsType.CARD,
            "VIDEO",
            BigDecimal.valueOf(6.90),
            BigDecimal.valueOf(12.00),
            5,
            false,
            List.of(),
            "retail-default",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0,
            128,
            "ON_SALE",
            List.of("auto-delivery", "card"),
            now,
            now,
            List.of("douyin", "taobao", "private"),
            List.of("pdd")
        ));
        goods.put(10002L, new GoodsItem(
            10002L,
            22222L,
            "热门大区",
            "游戏点券 60 枚",
            "游戏点券 60 枚",
            "直充商品，创建后进入采购中",
            "用于验证 DIRECT 商品的下单、采购中状态和充值账号字段。",
            "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1542751371-adc38448a05e?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "直充商品会按渠道优先级自动采购，失败后自动切换备用渠道。")),
            GoodsType.DIRECT,
            "GAME",
            BigDecimal.valueOf(5.80),
            BigDecimal.valueOf(6.00),
            1,
            true,
            List.of("mobile", "game_uid"),
            "member-standard",
            "DYNAMIC",
            BigDecimal.valueOf(1.08),
            BigDecimal.valueOf(0.20),
            999,
            245,
            "ON_SALE",
            List.of("direct", "recharge"),
            now,
            now,
            List.of("taobao", "pdd", "xianyu"),
            List.of()
        ));
        goods.put(10003L, new GoodsItem(
            10003L,
            333L,
            "人工处理",
            "资料人工代办服务",
            "资料人工代办服务",
            "人工处理商品，创建后等待客服处理",
            "用于验证 MANUAL 商品的待人工状态。",
            "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=800&q=80",
            List.of("https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80"),
            List.of(new GoodsDetailBlock("image", "https://images.unsplash.com/photo-1551836022-d5d88e9218df?auto=format&fit=crop&w=1200&q=80", ""), new GoodsDetailBlock("text", "", "代充订单由后台人工确认完成，适合需要客服处理的服务商品。")),
            GoodsType.MANUAL,
            "SERVICE",
            BigDecimal.valueOf(19.90),
            BigDecimal.valueOf(29.90),
            1,
            true,
            List.of("mobile", "wechat"),
            "manual-service",
            "FIXED",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            50,
            32,
            "ON_SALE",
            List.of("manual", "service"),
            now,
            now,
            List.of("xiaohongshu", "private"),
            List.of("douyin")
        ));
    }
}
