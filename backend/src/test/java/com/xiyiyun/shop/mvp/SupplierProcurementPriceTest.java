package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupplierProcurementPriceTest {
    private static final ProcurementPrice PRICE = new ProcurementPrice(
        new BigDecimal("40.0000"),
        new BigDecimal("80.0000")
    );
    private static final GoodsChannelItem CHANNEL = new GoodsChannelItem(
        30001L, 10001L, 20001L, "测试供应商", "UP-100", 10, 30, "ENABLED", OffsetDateTime.now()
    );

    @Test
    void everyPriceGuardUsesProcurementCostInsteadOfTheUsersPayment() throws Exception {
        CapturingHttpClient fanchen = invoke(
            new FanchenSupplierAdapter(), "FANCHEN_RJ", "{\"resultno\":\"0\",\"orderid\":\"UP-1\"}"
        );
        assertThat(fanchen.request().body()).contains("checkprice=80.0000").doesNotContain("250.0000");

        CapturingHttpClient kakayun = invoke(
            new KakayunSupplierAdapter(), "KAKAYUN", "{\"code\":1,\"data\":{\"orderno\":\"UP-2\"}}"
        );
        assertThat(jsonBody(kakayun).path("maxmoney").decimalValue()).isEqualByComparingTo("80.0000");
        assertThat(kakayun.request().body()).doesNotContain("250.0000");

        CapturingHttpClient fulu = invoke(
            new FuluSupplierAdapter(), "FULU", "{\"code\":200,\"result\":\"{}\"}"
        );
        JsonNode fuluBody = jsonBody(fulu);
        JsonNode fuluBiz = SupplierJson.MAPPER.readTree(fuluBody.path("biz_content").asText());
        assertThat(fuluBiz.path("customer_price").decimalValue()).isEqualByComparingTo("80.0000");
        assertThat(fulu.request().body()).doesNotContain("250.0000");

        CapturingHttpClient fengzhushou = invoke(
            new FengzhushouSupplierAdapter(), "FENGZHUSHOU", "{\"retcode\":0,\"data\":{\"orderNo\":\"UP-3\"}}"
        );
        assertThat(jsonBody(fengzhushou).path("skuPrice").decimalValue()).isEqualByComparingTo("80.0000");
        assertThat(fengzhushou.request().body()).doesNotContain("250.0000");

        CapturingHttpClient jingzhao = invoke(
            new JingzhaoSupplierAdapter(), "JINGZHAO", "{\"code\":\"ok\",\"data\":{\"state\":100,\"order_id\":\"UP-4\"}}"
        );
        assertThat(jingzhao.request().body()).contains("safe_cost=80.0000").doesNotContain("250.0000");
    }

    private static CapturingHttpClient invoke(
        SupplierAdapter adapter,
        String platformType,
        String response
    ) {
        CapturingHttpClient http = new CapturingHttpClient(response);
        SupplierItem supplier = new SupplierItem(
            20001L, "测试供应商", platformType, "https://supplier.example.com", "app-key", "",
            "user-id", "app-id", "secret", "***", "", 30, new BigDecimal("9999"),
            "ENABLED", "", OffsetDateTime.now()
        );
        SupplierCallContext context = new SupplierCallContext(
            supplier, "secret", http, mock(SupplierGoodsMappingPort.class), "https://api.example.com/callback"
        );

        adapter.submitOrder(context, order(), CHANNEL, PRICE);
        return http;
    }

    private static JsonNode jsonBody(CapturingHttpClient http) throws Exception {
        return SupplierJson.MAPPER.readTree(http.request().body());
    }

    private static OrderItem order() {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "ORDER-PRICE-1", 90001L, "buyer", 10001L, "测试商品", GoodsType.DIRECT,
            "h5", "127.0.0.1", "本地", 2, new BigDecimal("125.0000"), new BigDecimal("250.0000"),
            OrderStatus.PAID, "13800138000", Map.of("sg", "13800138000"), "", "request-1",
            "PAY-1", "alipay", List.of(), List.of(), "", now, now, null, "", null
        );
    }

    private static final class CapturingHttpClient extends SupplierHttpClient {
        private final JsonNode response;
        private SupplierHttpRequest request;

        private CapturingHttpClient(String response) {
            try {
                this.response = SupplierJson.MAPPER.readTree(response);
            } catch (Exception ex) {
                throw new IllegalArgumentException(ex);
            }
        }

        @Override
        public JsonNode postJson(SupplierHttpProfile profile, SupplierHttpRequest request) {
            this.request = request;
            return response;
        }

        private SupplierHttpRequest request() {
            return request;
        }
    }
}
