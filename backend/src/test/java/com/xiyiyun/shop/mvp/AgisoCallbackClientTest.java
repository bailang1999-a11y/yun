package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgisoCallbackClientTest {
    @Test
    void acceptsTheOfficialHttpCallbackButRejectsOtherPlainHttpHosts() {
        SupplierHttpClient http = mock(SupplierHttpClient.class);
        when(http.post(any(), any())).thenReturn("{\"code\":200}");
        AgisoCallbackClient client = new AgisoCallbackClient(http);

        client.post(
            "http://cb.acpr.agiso.com/SupplierOrderNotify/test",
            Map.of("orderNo", "test-order"),
            30
        );

        verify(http).post(any(), argThat(request ->
            "http".equals(request.uri().getScheme())
                && "cb.acpr.agiso.com".equals(request.uri().getHost())
        ));
        assertThatThrownBy(() -> client.post(
            "http://example.com/SupplierOrderNotify/test",
            Map.of("orderNo", "test-order"),
            30
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("callbackUrl is not allowed");
    }

    @Test
    void acceptsTheOfficialPriceNotificationHost() {
        SupplierHttpClient http = mock(SupplierHttpClient.class);
        when(http.post(any(), any())).thenReturn("{\"code\":200}");
        AgisoCallbackClient client = new AgisoCallbackClient(http);

        client.post(
            "http://cb-acpr.agiso.com/SupplierProductNotify/test-guid",
            Map.of("productNo", "10004"),
            30
        );

        verify(http).post(any(), argThat(request ->
            "http".equals(request.uri().getScheme())
                && "cb-acpr.agiso.com".equals(request.uri().getHost())
                && "/SupplierProductNotify/test-guid".equals(request.uri().getPath())
        ));
    }

    @Test
    void acceptsBothOfficialTestCallbackPaths() {
        AgisoCallbackClient client = new AgisoCallbackClient(mock(SupplierHttpClient.class));

        client.validateCallbackUrl(
            "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback"
        );
        client.validateCallbackUrl(
            "https://mai.91kami.com/AldsSupplierTest/test/CreatePurchaseCallback"
        );
    }

    @Test
    void acceptsTheJsonEncodedResponseUsedBy91Kami() {
        SupplierHttpClient http = mock(SupplierHttpClient.class);
        when(http.post(any(), any())).thenReturn("\"{\\\"code\\\":200,\\\"message\\\":\\\"请求成功\\\"}\"");
        AgisoCallbackClient client = new AgisoCallbackClient(http);

        client.post(
            "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback",
            Map.of("orderNo", "test-order"),
            30
        );
    }

    @Test
    void preservesThe91KamiRejectionReasonForRetryDiagnostics() {
        SupplierHttpClient http = mock(SupplierHttpClient.class);
        when(http.post(any(), any())).thenReturn("\"{\\\"code\\\":408,\\\"message\\\":\\\"时间戳过期\\\"}\"");
        AgisoCallbackClient client = new AgisoCallbackClient(http);

        assertThatThrownBy(() -> client.post(
            "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback",
            Map.of("orderNo", "test-order"),
            30
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("agiso callback rejected: code=408 message=时间戳过期");
    }

    @Test
    void rejectsEmptyAndNonObjectResponses() {
        SupplierHttpClient http = mock(SupplierHttpClient.class);
        AgisoCallbackClient client = new AgisoCallbackClient(http);

        when(http.post(any(), any())).thenReturn("");
        assertThatThrownBy(() -> client.post(
            "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback",
            Map.of("orderNo", "test-order"),
            30
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("agiso callback returned invalid JSON object");

        when(http.post(any(), any())).thenReturn("\"200\"");
        assertThatThrownBy(() -> client.post(
            "https://mai.91kami.com/AldsSupplierTest/test/CreateRechargeCallback",
            Map.of("orderNo", "test-order"),
            30
        )).isInstanceOf(IllegalStateException.class)
            .hasMessage("agiso callback returned invalid JSON object");
    }
}
