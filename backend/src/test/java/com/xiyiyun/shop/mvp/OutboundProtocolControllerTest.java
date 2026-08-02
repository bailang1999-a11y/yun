package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OutboundProtocolControllerTest {
    private static final String APP_KEY = "outbound_test_key";
    private static final String SECRET = "outbound_test_secret";

    private OutboundProtocolController controller;
    private OutboundProtocolService service;

    @BeforeEach
    void setUp() {
        InMemoryShopRepository repository = new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
        repository.saveMemberCredential(90001L, new MemberApiCredentialRequest(
            true, APP_KEY, SECRET, false, List.of(), 1000
        ));
        service = new OutboundProtocolService(repository);
        Map<String, Boolean> protocols = new LinkedHashMap<>();
        OutboundProtocolService.PROTOCOL_IDS.forEach(id -> protocols.put(id, true));
        service.save(new OutboundProtocolSettingsRequest(
            true, "https://api.example.test", 100, 30, protocols, "ALL", List.of(), List.of(),
            List.of("CARD", "DIRECT"), "MEMBER_GROUP", BigDecimal.ZERO, false
        ));
        controller = new OutboundProtocolController(service);
    }

    @Test
    void allSevenBalanceContractsAcceptTheirNativeSignatures() {
        Map<String, Object> empty = Map.of();
        String kasushouTimestamp = String.valueOf(System.currentTimeMillis());
        Map<String, Object> kasushou = controller.kasushouUser(
            APP_KEY,
            kasushouTimestamp,
            KasushouSignatureUtil.sign(kasushouTimestamp, empty, SECRET),
            empty,
            request("/api/v1/user/info")
        );
        assertThat(kasushou.get("code")).isEqualTo(200);

        Map<String, Object> kakayunBody = signedKakayun();
        Map<String, Object> kakayun = controller.kakayun(kakayunBody, request("/dockapiv3/user/info"));
        assertThat(kakayun.get("code")).isEqualTo(1);

        Map<String, Object> fuluBody = signedFulu("merchant.balance.query", Map.of());
        Map<String, Object> fulu = controller.fulu(fuluBody, request("/api/rechargeapi/gateway"));
        assertThat(fulu.get("code")).isEqualTo(200);
        assertThat(fulu.get("sign")).isNotNull();

        Map<String, Object> fengBody = new LinkedHashMap<>();
        fengBody.put("projectCode", APP_KEY);
        fengBody.put("timestamp", String.valueOf(System.currentTimeMillis()));
        fengBody.put("sign", FengzhushouSignatureUtil.sign(fengBody, SECRET));
        Map<String, Object> feng = controller.fengzhushou(fengBody, request("/fzs-stdopen-api/api/v1/balance"));
        assertThat(feng.get("retcode")).isEqualTo(0);

        Map<String, Object> chengquanBody = new LinkedHashMap<>();
        chengquanBody.put("app_id", APP_KEY);
        chengquanBody.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        chengquanBody.put("sign", ChengquanSignatureUtil.sign(chengquanBody, SECRET));
        Map<String, Object> chengquan = controller.chengquan(chengquanBody, request("/user/balance/get"));
        assertThat(chengquan.get("code")).isEqualTo(7000);

        Map<String, String> fanchenForm = new LinkedHashMap<>();
        fanchenForm.put("userid", APP_KEY);
        fanchenForm.put("sign", FanchenSignatureUtil.sign(fanchenForm, List.of("userid"), SECRET));
        Map<String, Object> fanchen = controller.fanchen(fanchenForm, request("/fcsearchbalance.do"));
        assertThat(fanchen.get("resultno")).isEqualTo("1");

        Map<String, String> jingzhaoForm = new LinkedHashMap<>();
        jingzhaoForm.put("customer_id", APP_KEY);
        jingzhaoForm.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        jingzhaoForm.put("sign", JingzhaoSignatureUtil.sign(new LinkedHashMap<>(jingzhaoForm), SECRET));
        Map<String, Object> jingzhao = controller.jingzhao(jingzhaoForm, request("/api/customer"));
        assertThat(jingzhao.get("code")).isEqualTo("ok");
    }

    @Test
    void invalidSignatureUsesProtocolErrorShapeAndDoesNotReturnBalance() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, Object> response = controller.kasushouUser(
            APP_KEY, timestamp, "invalid", Map.of(), request("/api/v1/user/info")
        );

        assertThat(response.get("code")).isEqualTo(400);
        assertThat(response.get("msg")).isEqualTo("invalid signature");
    }

    @Test
    void perMinuteLimitRejectsTheSecondRequestWithNativeErrorShape() {
        Map<String, Boolean> protocols = new LinkedHashMap<>();
        OutboundProtocolService.PROTOCOL_IDS.forEach(id -> protocols.put(id, true));
        service.save(new OutboundProtocolSettingsRequest(
            true, "", 1, 30, protocols, "ALL", List.of(), List.of(),
            List.of("CARD", "DIRECT"), "MEMBER_GROUP", BigDecimal.ZERO, false
        ));

        Map<String, Object> first = signedKakayun();
        Map<String, Object> second = signedKakayun();

        assertThat(controller.kakayun(first, request("/dockapiv3/user/info")).get("code")).isEqualTo(1);
        Map<String, Object> rejected = controller.kakayun(second, request("/dockapiv3/user/info"));
        assertThat(rejected.get("code")).isEqualTo(0);
        assertThat(rejected.get("msg")).isEqualTo("rate limit exceeded");
    }

    @Test
    void cardAndDirectOrdersUseOneIdempotentMemberOrderFlow() {
        OutboundApiPrincipal principal = service.authorize(
            "KASUSHOU", APP_KEY, "/test/order-types", "127.0.0.1"
        );
        List<GoodsItem> goods = service.goods(principal, null, "");
        GoodsItem card = goods.stream().filter(item -> "CARD".equals(item.type().name())).findFirst().orElseThrow();
        GoodsItem direct = goods.stream().filter(item -> "DIRECT".equals(item.type().name())).findFirst().orElseThrow();

        String cardRequestId = "out-card-" + UUID.randomUUID();
        Map<String, Object> firstCard = signedKasushouBuy(card.id(), cardRequestId, "");
        Map<String, Object> firstResponse = controller.kasushouBuy(
            APP_KEY, text(firstCard, "timestamp"), text(firstCard, "sign"), body(firstCard), request("/api/v1/order/buy")
        );
        Map<String, Object> repeatedCard = signedKasushouBuy(card.id(), cardRequestId, "");
        Map<String, Object> repeatedResponse = controller.kasushouBuy(
            APP_KEY, text(repeatedCard, "timestamp"), text(repeatedCard, "sign"), body(repeatedCard), request("/api/v1/order/buy")
        );

        assertThat(firstResponse.get("code")).isEqualTo(200);
        assertThat(orderNo(firstResponse)).isEqualTo(orderNo(repeatedResponse));

        Map<String, Object> directRequest = signedKasushouBuy(
            direct.id(), "out-direct-" + UUID.randomUUID(), "13800138000"
        );
        Map<String, Object> directResponse = controller.kasushouBuy(
            APP_KEY, text(directRequest, "timestamp"), text(directRequest, "sign"), body(directRequest), request("/api/v1/order/buy")
        );
        assertThat(directResponse.get("code")).as(directResponse.toString()).isEqualTo(200);
    }

    @Test
    void allSevenProtocolsSubmitDirectOrdersWithTheirNativeFields() {
        Long goodsId = directGoodsId();
        String account = "13800138000";

        Map<String, Object> kasushouRequest = signedKasushouBuy(goodsId, requestId("kas"), account);
        assertThat(controller.kasushouBuy(
            APP_KEY, text(kasushouRequest, "timestamp"), text(kasushouRequest, "sign"), body(kasushouRequest), request("/api/v1/order/buy")
        ).get("code")).isEqualTo(200);

        Map<String, Object> kakayun = new LinkedHashMap<>();
        kakayun.put("userid", APP_KEY);
        kakayun.put("timestamp", Instant.now().getEpochSecond());
        kakayun.put("goodsid", goodsId);
        kakayun.put("buynum", 1);
        kakayun.put("usorderno", requestId("kky"));
        kakayun.put("attach", account);
        kakayun.put("sign", KakayunSignatureUtil.sign(kakayun, SECRET));
        assertThat(controller.kakayun(kakayun, request("/dockapiv3/order/create")).get("code")).isEqualTo(1);

        Map<String, Object> fulu = signedFulu("order.notify", Map.of(
            "product_id", goodsId, "customer_order_no", requestId("fulu"), "charge_account", account, "buy_num", 1
        ));
        assertThat(controller.fulu(fulu, request("/api/rechargeapi/gateway")).get("code")).isEqualTo(200);

        Map<String, Object> feng = new LinkedHashMap<>();
        feng.put("projectCode", APP_KEY);
        feng.put("timestamp", String.valueOf(System.currentTimeMillis()));
        feng.put("skuCode", goodsId);
        feng.put("channelOrderNo", requestId("feng"));
        feng.put("account", account);
        feng.put("num", 1);
        feng.put("sign", FengzhushouSignatureUtil.sign(feng, SECRET));
        assertThat(controller.fengzhushou(feng, request("/fzs-stdopen-api/api/v1/sendgoods")).get("retcode")).isEqualTo(0);

        Map<String, Object> chengquan = new LinkedHashMap<>();
        chengquan.put("app_id", APP_KEY);
        chengquan.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        chengquan.put("product_id", goodsId);
        chengquan.put("order_no", requestId("cheng"));
        chengquan.put("recharge_number", account);
        chengquan.put("amount", 1);
        chengquan.put("sign", ChengquanSignatureUtil.sign(chengquan, SECRET));
        assertThat(controller.chengquan(chengquan, request("/order/directCharge")).get("code")).isEqualTo(7000);

        Map<String, String> fanchen = new LinkedHashMap<>();
        fanchen.put("userid", APP_KEY);
        fanchen.put("productid", String.valueOf(goodsId));
        fanchen.put("num", "1");
        fanchen.put("areaid", "");
        fanchen.put("serverid", "");
        fanchen.put("account", account);
        fanchen.put("spordertime", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))));
        fanchen.put("sporderid", requestId("fan"));
        fanchen.put("sign", FanchenSignatureUtil.sign(
            new LinkedHashMap<>(fanchen),
            List.of("userid", "productid", "num", "areaid", "serverid", "account", "spordertime", "sporderid"),
            SECRET
        ));
        Map<String, Object> fanchenResponse = controller.fanchen(fanchen, request("/fcgameonlinepay.do"));
        assertThat(fanchenResponse.get("resultno")).isNotEqualTo("9999");
        assertThat(String.valueOf(fanchenResponse.get("orderid"))).isNotBlank();

        Map<String, String> jingzhao = new LinkedHashMap<>();
        jingzhao.put("customer_id", APP_KEY);
        jingzhao.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        jingzhao.put("product_id", String.valueOf(goodsId));
        jingzhao.put("quantity", "1");
        jingzhao.put("outer_order_id", requestId("jing"));
        jingzhao.put("recharge_account", account);
        Map<String, Object> jingzhaoSignFields = new LinkedHashMap<>(jingzhao);
        jingzhao.put("sign", JingzhaoSignatureUtil.sign(jingzhaoSignFields, SECRET));
        assertThat(controller.jingzhao(jingzhao, request("/api/buy")).get("code")).isEqualTo("ok");
    }

    private Map<String, Object> signedKakayun() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", APP_KEY);
        body.put("timestamp", Instant.now().getEpochSecond());
        body.put("sign", KakayunSignatureUtil.sign(body, SECRET));
        return body;
    }

    private Map<String, Object> signedKasushouBuy(Long goodsId, String requestId, String account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", goodsId);
        body.put("quantity", 1);
        body.put("external_orderno", requestId);
        body.put("mark", "contract test");
        if (!account.isBlank()) {
            body.put("attach", Map.of(
                "recharge_account", account,
                "mobile", account,
                "game_uid", account
            ));
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, Object> request = new LinkedHashMap<>(body);
        request.put("timestamp", timestamp);
        request.put("sign", KasushouSignatureUtil.sign(timestamp, body, SECRET));
        return request;
    }

    private Map<String, Object> signedFulu(String method, Map<String, Object> biz) {
        Map<String, String> signFields = new LinkedHashMap<>();
        signFields.put("app_key", APP_KEY);
        signFields.put("method", method);
        signFields.put("timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))));
        signFields.put("version", "1.0");
        signFields.put("format", "json");
        signFields.put("charset", "utf-8");
        signFields.put("sign_type", "md5");
        signFields.put("biz_content", writeJson(biz));
        Map<String, Object> body = new LinkedHashMap<>(signFields);
        body.put("sign", FuluSignatureUtil.requestSign(signFields, SECRET));
        return body;
    }

    private Long directGoodsId() {
        OutboundApiPrincipal principal = service.authorize("KASUSHOU", APP_KEY, "/test/direct-goods", "127.0.0.1");
        return service.goods(principal, null, "").stream()
            .filter(item -> "DIRECT".equals(item.type().name()))
            .map(GoodsItem::id)
            .findFirst()
            .orElseThrow();
    }

    private String requestId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>(request);
        body.remove("timestamp");
        body.remove("sign");
        return body;
    }

    @SuppressWarnings("unchecked")
    private String orderNo(Map<String, Object> response) {
        return String.valueOf(((Map<String, Object>) response.get("data")).get("ordersn"));
    }

    private String text(Map<String, Object> values, String key) {
        return String.valueOf(values.get(key));
    }
}
