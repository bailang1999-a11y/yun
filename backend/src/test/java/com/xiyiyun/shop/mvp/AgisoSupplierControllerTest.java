package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgisoSupplierControllerTest {
    private static final String APP_ID = "2026073033612141753";
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String MEMBER_SECRET = "member-interface-secret";
    private static final String APP_KEY = "member_90001";
    private static final String CALLBACK_URL =
        "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgisoSupplierController controller;
    private AgisoCallbackClient callbackClient;
    private AgisoOrderCallbackService callbackService;
    private AgisoPriceSubscriptionService priceSubscriptionService;
    private InMemoryShopRepository repository;
    private OutboundProtocolService service;
    private MockMvc mockMvc;
    private Long directGoodsId;
    private Long cardGoodsId;
    private GoodsItem directGoods;

    @BeforeEach
    void setUp() {
        repository = new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
        repository.saveMemberCredential(90001L, new MemberApiCredentialRequest(
            true, APP_KEY, MEMBER_SECRET, false, List.of(), 1000
        ));
        service = new OutboundProtocolService(repository);
        Map<String, Boolean> protocols = new LinkedHashMap<>();
        OutboundProtocolService.PROTOCOL_IDS.forEach(id -> protocols.put(id, true));
        service.save(new OutboundProtocolSettingsRequest(
            true, "https://api.example.test", 100, 30, protocols, "ALL", List.of(), List.of(),
            List.of("CARD", "DIRECT"), "MEMBER_GROUP", BigDecimal.ZERO, false
        ));
        callbackClient = mock(AgisoCallbackClient.class);
        callbackService = mock(AgisoOrderCallbackService.class);
        priceSubscriptionService = mock(AgisoPriceSubscriptionService.class);
        controller = new AgisoSupplierController(
            service, callbackClient, callbackService, priceSubscriptionService, true, APP_ID, APP_SECRET,
            25L, 1L
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        OutboundApiPrincipal principal = service.authorizeCredential(APP_KEY, "/test/goods", "127.0.0.1");
        List<GoodsItem> goods = service.goods(principal, null, "");
        directGoods = goods.stream().filter(item -> item.type() == GoodsType.DIRECT).findFirst().orElseThrow();
        directGoodsId = directGoods.id();
        cardGoodsId = goods.stream().filter(item -> item.type() == GoodsType.CARD).map(GoodsItem::id).findFirst().orElseThrow();
    }

    @Test
    void exposesConfiguredAppIdAndNativeProductContracts() {
        Map<String, Object> app = controller.appId();
        assertThat(app.get("code")).isEqualTo(200);
        assertThat(data(app).get("appId")).isEqualTo(Long.parseLong(APP_ID));

        Map<String, Object> listRequest = signed(Map.of(
            "productType", 1,
            "pageIndex", 1,
            "pageSize", 20
        ));
        Map<String, Object> products = controller.products(listRequest, request("/agisoAcprSupplierApi/product/getList"));
        assertThat(products.get("code")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) data(products).get("items");
        assertThat(items).isNotEmpty().allSatisfy(item -> {
            assertThat(item.get("productType")).isEqualTo(1);
            assertThat(item).containsKey("disableSaleChannels");
            Object disabledChannels = item.get("disableSaleChannels");
            assertThat(disabledChannels == null || disabledChannels instanceof String).isTrue();
        });
        assertThat(json(products)).contains("\"disableSaleChannels\":null");
        assertThat(AgisoSupplierController.disableSaleChannels(null)).isNull();
        assertThat(AgisoSupplierController.disableSaleChannels(List.of())).isNull();
        assertThat(AgisoSupplierController.disableSaleChannels(List.of("DOUYIN", "TAOBAO")))
            .isEqualTo("DOUYIN,TAOBAO");

        Map<String, Object> template = controller.productTemplate(
            signed(Map.of("productNo", String.valueOf(cardGoodsId))),
            request("/agisoAcprSupplierApi/product/getTemplate")
        );
        assertThat(template.get("code")).isEqualTo(200);
        assertThat(data(template).get("productNo")).isEqualTo(String.valueOf(cardGoodsId));
        assertThat(data(template).get("apiType")).isEqualTo(1);
        assertThat(data(template).get("productType")).isEqualTo(2);
        assertThat(data(template).get("productCost")).isInstanceOf(BigDecimal.class);

        Map<String, Object> directTemplate = controller.productTemplate(
            signed(Map.of("productNo", String.valueOf(directGoodsId))),
            request("/agisoAcprSupplierApi/product/getTemplate")
        );
        assertThat(directTemplate.get("code")).isEqualTo(200);
        assertThat(data(directTemplate).get("apiType")).isEqualTo(2);
        assertThat(data(directTemplate).get("productType")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attach = (List<Map<String, Object>>) data(directTemplate).get("attach");
        assertThat(attach).singleElement().satisfies(field -> {
            assertThat(field).containsEntry("name", "account").containsEntry("inputCheck", 1);
            assertThat(field.get("title")).asString().contains("任填一项");
        });
    }

    @Test
    void acceptsExtraSignedParametersButRejectsTamperingAndExpiredTimestamps() {
        Map<String, Object> accepted = signed(Map.of(
            "productNo", String.valueOf(directGoodsId),
            "futureField", "supported",
            "emptyField", ""
        ));
        assertThat(controller.productTemplate(
            accepted, request("/agisoAcprSupplierApi/product/getTemplate")
        ).get("code")).isEqualTo(200);

        accepted.put("futureField", "tampered");
        assertThat(controller.productTemplate(
            accepted, request("/agisoAcprSupplierApi/product/getTemplate")
        ).get("code")).isEqualTo(401);

        long now = Instant.now().getEpochSecond();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, now - 599)).isTrue();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, now + 599)).isTrue();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, now - 600)).isFalse();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, now + 600)).isFalse();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, Long.MIN_VALUE)).isFalse();
        assertThat(AgisoSupplierController.withinTimestampTolerance(now, Long.MAX_VALUE)).isFalse();

        Map<String, Object> expired = signedAt(
            Map.of("productNo", String.valueOf(directGoodsId)),
            Instant.now().minusSeconds(600).getEpochSecond()
        );
        assertThat(controller.productTemplate(
            expired, request("/agisoAcprSupplierApi/product/getTemplate")
        ).get("code")).isEqualTo(408);
    }

    @Test
    void subscribesAndCancelsPriceNotificationsWithTheDocumentedResponseContract() throws Exception {
        String supplierAccountGuid = "2fe15520c6ac45ff83a270aaef71a873";
        Map<String, Object> payload = signed(Map.of(
            "productNo", String.valueOf(directGoodsId),
            "supplierAccountGuid", supplierAccountGuid
        ));

        mockMvc.perform(post("/agisoAcprSupplierApi/product/subscribePriceNotify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("接口调用成功"))
            .andExpect(jsonPath("$.data").value(nullValue()));

        verify(priceSubscriptionService).subscribe(
            any(OutboundApiPrincipal.class), eq(supplierAccountGuid), eq(directGoods)
        );

        mockMvc.perform(post("/agisoAcprSupplierApi/product/cancelSubscribePriceNotify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(payload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("接口调用成功"))
            .andExpect(jsonPath("$.data").value(nullValue()));

        verify(priceSubscriptionService).cancel(90001L, supplierAccountGuid, directGoodsId);
    }

    @Test
    void priceSubscriptionRejectsInvalidSignaturesMissingGuidsAndUnknownProducts() {
        String supplierAccountGuid = "2fe15520c6ac45ff83a270aaef71a873";
        Map<String, Object> invalidSignature = signed(Map.of(
            "productNo", String.valueOf(directGoodsId),
            "supplierAccountGuid", supplierAccountGuid
        ));
        invalidSignature.put("supplierAccountGuid", "tampered");

        Map<String, Object> rejected = controller.subscribePriceNotify(
            invalidSignature,
            request("/agisoAcprSupplierApi/product/subscribePriceNotify")
        );
        assertThat(rejected).containsEntry("code", 401);
        verifyNoInteractions(priceSubscriptionService);

        Map<String, Object> missingGuid = controller.subscribePriceNotify(
            signed(Map.of("productNo", String.valueOf(directGoodsId))),
            request("/agisoAcprSupplierApi/product/subscribePriceNotify")
        );
        assertThat(missingGuid).containsEntry("code", 500)
            .containsEntry("message", "supplierAccountGuid is required");

        Map<String, Object> unknownProduct = controller.subscribePriceNotify(
            signed(Map.of(
                "productNo", String.valueOf(Long.MAX_VALUE),
                "supplierAccountGuid", supplierAccountGuid
            )),
            request("/agisoAcprSupplierApi/product/subscribePriceNotify")
        );
        assertThat(unknownProduct).containsEntry("code", 1100)
            .containsEntry("message", "失败原因:商品不存在")
            .containsEntry("data", null);
        verifyNoInteractions(priceSubscriptionService);
    }

    @Test
    void priceSubscriptionCancellationDoesNotRequireTheProductToExist() {
        String supplierAccountGuid = "2fe15520c6ac45ff83a270aaef71a873";

        Map<String, Object> response = controller.cancelSubscribePriceNotify(
            signed(Map.of(
                "productNo", String.valueOf(Long.MAX_VALUE),
                "supplierAccountGuid", supplierAccountGuid
            )),
            request("/agisoAcprSupplierApi/product/cancelSubscribePriceNotify")
        );

        assertThat(response).containsEntry("code", 200)
            .containsEntry("message", "接口调用成功")
            .containsEntry("data", null);
        verify(priceSubscriptionService).cancel(90001L, supplierAccountGuid, Long.MAX_VALUE);
    }

    @Test
    void rejectsOrdersThatExceedTheBuyerMaximumCost() {
        Map<String, Object> payload = orderPayload(directGoodsId, "0", "", "cost-" + UUID.randomUUID());

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).isEqualTo(1220);
    }

    @Test
    void validMaxAmountIsPassedIntoAtomicOrderCreation() {
        OutboundProtocolService orderService = spy(service);
        AgisoSupplierController priceController = controller(orderService, 25L);
        String externalOrderNo = "max-amount-" + UUID.randomUUID();

        Map<String, Object> response = priceController.createRecharge(
            signed(orderPayload(directGoodsId, "999999.1234", CALLBACK_URL, externalOrderNo)),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response).containsEntry("code", 200);
        verify(orderService).createOrder(
            any(OutboundApiPrincipal.class),
            eq(directGoodsId),
            eq(1),
            any(),
            any(),
            eq(externalOrderNo),
            anyMap(),
            any(),
            eq(new BigDecimal("999999.1234"))
        );
    }

    @Test
    void blankMaxAmountCreatesTheOrderWithNoExternalPrice() {
        OutboundProtocolService orderService = spy(service);
        AgisoSupplierController priceController = controller(orderService, 25L);
        String externalOrderNo = "blank-max-" + UUID.randomUUID();

        Map<String, Object> response = priceController.createRecharge(
            signed(orderPayload(directGoodsId, "", CALLBACK_URL, externalOrderNo)),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response).containsEntry("code", 200);
        verify(orderService).createOrder(
            any(OutboundApiPrincipal.class),
            eq(directGoodsId),
            eq(1),
            any(),
            any(),
            eq(externalOrderNo),
            anyMap(),
            any(),
            eq((BigDecimal) null)
        );
    }

    @Test
    void maxAmountOutsideDatabasePrecisionIsRejectedBeforeOrderCreation() {
        OutboundProtocolService orderService = spy(service);
        AgisoSupplierController priceController = controller(orderService, 25L);

        Map<String, Object> response = priceController.createRecharge(
            signed(orderPayload(directGoodsId, "1E+100", CALLBACK_URL, "oversized-max-" + UUID.randomUUID())),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response).containsEntry("code", 500)
            .containsEntry("message", "maxAmount is invalid");
        verify(orderService, never()).createOrder(
            any(OutboundApiPrincipal.class),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyMap(),
            any(),
            any()
        );
    }

    @Test
    void returnsThe91KamiProductMissingContractForAnUnknownProduct() {
        Map<String, Object> response = controller.createRecharge(
            signed(Map.of(
                "orderNo", "missing-" + UUID.randomUUID(),
                "productNo", String.valueOf(Long.MAX_VALUE),
                "buyNum", 1,
                "maxAmount", "",
                "callbackUrl", "",
                "attach", "{\"sg\":\"16666666666\"}"
            )),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).isEqualTo(1100);
        assertThat(response.get("message")).isEqualTo("失败原因:商品不存在");
        assertThat(response.get("data")).isNull();
        assertThat(json(response))
            .isEqualTo("{\"code\":1100,\"message\":\"失败原因:商品不存在\",\"data\":null}");
    }

    @Test
    void exposesTheLegacyAnd91KamiCancelPathsWithTheSameResponseContract() throws Exception {
        for (String path : List.of(
            "/agisoAcprSupplierApi/order/cancel",
            "/agisoAcprSupplierApi/order/cancelOrder"
        )) {
            String externalOrderNo = "cancel-" + UUID.randomUUID();
            Map<String, Object> created = controller.createRecharge(
                signed(orderPayload(directGoodsId, "999999", CALLBACK_URL, externalOrderNo)),
                request("/agisoAcprSupplierApi/order/createRecharge")
            );
            assertThat(created.get("code")).as(created.toString()).isEqualTo(200);

            mockMvc.perform(post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(signed(Map.of("orderNo", externalOrderNo)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("接口调用成功"))
                .andExpect(jsonPath("$.data.orderNo").value(externalOrderNo))
                .andExpect(jsonPath("$.data.cancelStatus").value(30))
                .andExpect(jsonPath("$.data.refundAmount").isNumber())
                .andExpect(jsonPath("$.data.refuseReason").isNotEmpty())
                .andExpect(jsonPath("$.data.refuseProof").value(""));
        }
    }

    @Test
    void directOrdersReuseTheExistingIdempotentMemberOrderFlow() {
        String externalOrderNo = "direct-" + UUID.randomUUID();
        Map<String, Object> payload = orderPayload(directGoodsId, "999999", CALLBACK_URL, externalOrderNo);

        Map<String, Object> first = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );
        Map<String, Object> repeated = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(first.get("code")).as(first.toString()).isEqualTo(200);
        assertThat(data(first).get("orderNo")).isEqualTo(externalOrderNo);
        assertThat(data(repeated).get("outTradeNo")).isEqualTo(data(first).get("outTradeNo"));
    }

    @Test
    void acceptsTheDocumentedTopLevelRechargeAccount() {
        Map<String, Object> payload = orderPayload(
            directGoodsId, "999999", CALLBACK_URL, "account-" + UUID.randomUUID()
        );
        payload.remove("attach");
        payload.put("account", "13800138000");

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).as(response.toString()).isEqualTo(200);
    }

    @Test
    void acceptsLegacyDuplicatedRechargeFieldsAndClassifiesTheSharedValue() {
        Map<String, Object> payload = orderPayload(
            directGoodsId, "999999", CALLBACK_URL, "legacy-fields-" + UUID.randomUUID()
        );
        payload.put("attach", json(Map.of("mobile", "player-123", "game_uid", "player-123")));

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).as(response.toString()).isEqualTo(200);
        OrderItem created = repository.findOrder(String.valueOf(data(response).get("outTradeNo")))
            .orElseThrow();
        assertThat(created.rechargeAccount()).isEqualTo("player-123");
        assertThat(created.rechargeFields()).containsExactlyEntriesOf(Map.of("game_uid", "player-123"));
    }

    @Test
    void acceptsAndClassifiesTheGenericAlternativeRechargeAccount() {
        Map<String, Object> payload = orderPayload(
            directGoodsId, "999999", CALLBACK_URL, "generic-fields-" + UUID.randomUUID()
        );
        payload.put("attach", json(Map.of("account", "player-456")));

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).as(response.toString()).isEqualTo(200);
        OrderItem created = repository.findOrder(String.valueOf(data(response).get("outTradeNo")))
            .orElseThrow();
        assertThat(created.rechargeFields()).containsExactlyEntriesOf(Map.of("game_uid", "player-456"));
    }

    @Test
    void failedOrderAuditIncludesTheExternalOrderContext() {
        String externalOrderNo = "invalid-account-" + UUID.randomUUID();
        Map<String, Object> payload = orderPayload(directGoodsId, "0", CALLBACK_URL, externalOrderNo);

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).isEqualTo(1220);
        assertThat(repository.listOpenApiLogs()).anySatisfy(log -> assertThat(log.message())
            .contains("productNo=" + directGoodsId)
            .contains("orderNo=" + externalOrderNo)
            .contains("reason="));
    }

    @Test
    void directOrdersRegisterTheSuppliedCallbackAndReturnProcessing() {
        String callbackUrl = "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback";
        String externalOrderNo = "async-direct-" + UUID.randomUUID();
        OrderItem processing = order(externalOrderNo, GoodsType.DIRECT, OrderStatus.PROCURING, "");
        OutboundProtocolService asyncService = serviceReturning(processing, processing);
        AgisoSupplierController asyncController = controller(asyncService, 3L);

        Map<String, Object> async = asyncController.createRecharge(
            signed(orderPayload(directGoodsId, "999999", callbackUrl, externalOrderNo)),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );
        assertThat(async.get("code")).as(async.toString()).isEqualTo(200);
        assertThat(data(async).get("orderStatus")).isEqualTo(10);
        String requestId = String.valueOf(data(async).get("orderNo"));
        verify(callbackService).prepare(90001L, requestId, callbackUrl, GoodsType.DIRECT);
        verify(callbackService).bind(eq(90001L), eq(requestId), any(OrderItem.class));
        verify(asyncService, never()).findOrder(any(OutboundApiPrincipal.class), any(String.class), any(String.class));
    }

    @Test
    void immediatelyDeliveredDirectOrderStillReturnsProcessingBeforeTheWorkerCallback() {
        String callbackUrl = "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback";
        String externalOrderNo = "async-delivered-" + UUID.randomUUID();
        OrderItem delivered = order(externalOrderNo, GoodsType.DIRECT, OrderStatus.DELIVERED, "充值成功");
        OutboundProtocolService asyncService = serviceReturning(delivered, delivered);
        AgisoSupplierController asyncController = controller(asyncService, 3L);

        Map<String, Object> response = asyncController.createRecharge(
            signed(orderPayload(directGoodsId, "999999", callbackUrl, externalOrderNo)),
            request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response).containsEntry("code", 200);
        assertThat(data(response)).containsEntry("orderStatus", 10);
        verify(callbackService).prepare(90001L, externalOrderNo, callbackUrl, GoodsType.DIRECT);
        verify(callbackService).bind(90001L, externalOrderNo, delivered);
        verify(asyncService, never()).findOrder(any(OutboundApiPrincipal.class), any(String.class), any(String.class));
    }

    @Test
    void createdCardOrderCanBeQueriedByTheExternalOrderNumber() {
        String externalOrderNo = "query-card-" + UUID.randomUUID();
        Map<String, Object> created = controller.createPurchase(
            signed(orderPayload(cardGoodsId, "999999", CALLBACK_URL, externalOrderNo)),
            request("/agisoAcprSupplierApi/order/createPurchase")
        );

        Map<String, Object> queried = controller.order(
            signed(Map.of("orderNo", externalOrderNo)),
            request("/agisoAcprSupplierApi/order/get")
        );

        assertThat(queried.get("code")).as(queried.toString()).isEqualTo(200);
        assertThat(data(created)).containsEntry("orderStatus", 20);
        assertThat(data(queried)).containsEntry("orderNo", externalOrderNo)
            .containsEntry("outTradeNo", data(created).get("outTradeNo"))
            .containsEntry("orderStatus", 20);
        assertThat(data(queried).get("cards")).isInstanceOf(String.class).asString().isNotBlank();
        verifyNoInteractions(callbackService);
    }

    @Test
    void asyncDirectOrderRejectsMissingCallbackBeforeCreatingTheOrder() {
        String directOrderNo = "missing-callback-" + UUID.randomUUID();
        OutboundProtocolService orderService = spy(service);
        AgisoSupplierController asyncController = controller(orderService, 100L);
        Map<String, Object> missing = orderPayload(directGoodsId, "999999", CALLBACK_URL, directOrderNo);
        missing.remove("callbackUrl");

        Map<String, Object> directResponse = asyncController.createRecharge(
            signed(missing), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(directResponse).containsEntry("code", 9999)
            .containsEntry("message", "async product requires callbackUrl");
        verify(orderService, never()).createOrder(
            any(OutboundApiPrincipal.class), any(Long.class), any(Integer.class), any(String.class),
            any(String.class), any(String.class), anyMap(), any(String.class), nullable(BigDecimal.class)
        );
        verifyNoInteractions(callbackService);
    }

    @Test
    void blankCallbackWaitsForTheSynchronousFailureWithoutCreatingACallbackTask() {
        String cardOrderNo = "blank-callback-" + UUID.randomUUID();
        OrderItem processing = order(cardOrderNo, GoodsType.CARD, OrderStatus.PROCURING, "");
        OrderItem failed = order(cardOrderNo, GoodsType.CARD, OrderStatus.FAILED, "上游明确失败");
        OutboundProtocolService synchronousService = serviceReturning(processing, failed);
        AgisoSupplierController synchronousController = controller(synchronousService, 100L);
        Map<String, Object> blank = orderPayload(cardGoodsId, "999999", "   ", cardOrderNo);

        Map<String, Object> cardResponse = synchronousController.createPurchase(
            signed(blank), request("/agisoAcprSupplierApi/order/createPurchase")
        );

        assertThat(cardResponse).containsEntry("code", 200);
        assertThat(data(cardResponse)).containsEntry("orderStatus", 30)
            .containsEntry("failCode", 1)
            .containsEntry("failReason", "上游明确失败");
        verifyNoInteractions(callbackService);
    }

    @Test
    void synchronousCardTimeoutReturnsAProtocolErrorInsteadOfUnsupportedProcessing() {
        String externalOrderNo = "sync-timeout-" + UUID.randomUUID();
        OrderItem processing = order(externalOrderNo, GoodsType.CARD, OrderStatus.PROCURING, "等待上游确认");
        OutboundProtocolService synchronousService = serviceReturning(processing, processing);
        AgisoSupplierController synchronousController = controller(synchronousService, 3L);
        Map<String, Object> payload = orderPayload(cardGoodsId, "999999", "", externalOrderNo);

        Map<String, Object> response = synchronousController.createPurchase(
            signed(payload), request("/agisoAcprSupplierApi/order/createPurchase")
        );

        assertThat(response).containsEntry("code", 9999)
            .containsEntry("message", "synchronous product did not reach a final state within the wait limit");
        verifyNoInteractions(callbackService);
    }

    @Test
    void failedAsyncOrderDiscardsItsPreparedCallbackTask() {
        String externalOrderNo = "failed-async-" + UUID.randomUUID();
        String callbackUrl = "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback";
        repository.adjustUserFunds(90001L, new UserFundAdjustRequest(
            "balance", "decrease", new BigDecimal("128.66"), "callback failure test"
        ));
        Map<String, Object> payload = orderPayload(directGoodsId, "999999", callbackUrl, externalOrderNo);

        Map<String, Object> response = controller.createRecharge(
            signed(payload), request("/agisoAcprSupplierApi/order/createRecharge")
        );

        assertThat(response.get("code")).isNotEqualTo(200);
        verify(callbackService).prepare(90001L, externalOrderNo, callbackUrl, GoodsType.DIRECT);
        verify(callbackService).discard(eq(90001L), eq(externalOrderNo), any());
    }

    private Map<String, Object> orderPayload(Long goodsId, String maxAmount, String callbackUrl, String orderNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderNo", orderNo);
        payload.put("productNo", String.valueOf(goodsId));
        payload.put("buyNum", 1);
        payload.put("maxAmount", maxAmount);
        payload.put("callbackUrl", callbackUrl);
        if (goodsId.equals(directGoodsId)) {
            String code = directGoods.accountTypes() == null || directGoods.accountTypes().isEmpty()
                ? "account"
                : directGoods.accountTypes().get(0);
            payload.put("attach", json(Map.of(code, "13800138000", "account", "13800138000")));
        } else {
            payload.put("attach", "");
        }
        return payload;
    }

    private AgisoSupplierController controller(OutboundProtocolService protocolService, long timeoutMillis) {
        return new AgisoSupplierController(
            protocolService, callbackClient, callbackService, priceSubscriptionService, true, APP_ID, APP_SECRET,
            timeoutMillis, 1L
        );
    }

    private OutboundProtocolService serviceReturning(OrderItem created, OrderItem refreshed) {
        OutboundProtocolService orderService = spy(service);
        doReturn(created).when(orderService).createOrder(
            any(OutboundApiPrincipal.class), any(Long.class), any(Integer.class), any(String.class),
            any(String.class), any(String.class), anyMap(), any(String.class), nullable(BigDecimal.class)
        );
        doReturn(refreshed).when(orderService).findOrder(
            any(OutboundApiPrincipal.class), eq(created.orderNo()), eq("")
        );
        return orderService;
    }

    private OrderItem order(String requestId, GoodsType type, OrderStatus status, String deliveryMessage) {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "xiyi-" + requestId,
            90001L,
            "tester",
            type == GoodsType.DIRECT ? directGoodsId : cardGoodsId,
            "测试商品",
            type,
            "api",
            "127.0.0.1",
            "",
            1,
            BigDecimal.ONE,
            BigDecimal.ONE,
            status,
            type == GoodsType.DIRECT ? "13800138000" : "",
            Map.of(),
            "",
            requestId,
            "PAY-" + requestId,
            "balance",
            type == GoodsType.CARD && status == OrderStatus.DELIVERED ? List.of("1|card-secret") : List.of(),
            List.of(),
            deliveryMessage,
            now,
            now,
            status == OrderStatus.DELIVERED ? now : null
        );
    }

    private Map<String, Object> signed(Map<String, ?> values) {
        return signedAt(values, Instant.now().getEpochSecond());
    }

    private Map<String, Object> signedAt(Map<String, ?> values, long timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", "90001");
        payload.put("timestamp", timestamp);
        payload.put("version", "1.0");
        payload.putAll(values);
        payload.put("sign", AgisoSignatureUtil.sign(payload, APP_SECRET + MEMBER_SECRET));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        return (Map<String, Object>) response.get("data");
    }

    private String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
