package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SupplierHttpClientTest {
    private static final SupplierHttpProfile PROFILE = SupplierHttpProfile.json("TEST");
    private static final SupplierHttpRequest MUTATION = SupplierHttpRequest.mutation(
        java.net.URI.create("http://supplier.test/order"), "{}", Duration.ofSeconds(1), "create order"
    );

    @Test
    void retriesConnectionFailuresAndSucceedsOnThirdAttempt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{}");
        when(client.send(any(HttpRequest.class), bodyHandler()))
            .thenThrow(new ConnectException("connection refused"))
            .thenThrow(new HttpConnectTimeoutException("connect timed out"))
            .thenReturn(response);

        assertThat(new SupplierHttpClient(client).post(PROFILE, MUTATION)).isEqualTo("{}");
        verify(client, times(3)).send(any(HttpRequest.class), bodyHandler());
    }

    @Test
    void stopsAfterThreeConnectionFailures() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), bodyHandler()))
            .thenThrow(new ConnectException("connection refused"));

        assertThatThrownBy(() -> new SupplierHttpClient(client).post(PROFILE, MUTATION))
            .isInstanceOf(SupplierTransportException.class)
            .hasMessageContaining("connection refused");
        verify(client, times(3)).send(any(HttpRequest.class), bodyHandler());
    }

    @Test
    void doesNotRetryAfterConnectionHasBeenEstablishedAndReadTimesOut() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), bodyHandler()))
            .thenThrow(new HttpTimeoutException("response read timed out"));

        assertThatThrownBy(() -> new SupplierHttpClient(client).post(PROFILE, MUTATION))
            .isInstanceOf(SupplierTransportException.class)
            .hasMessageContaining("response read timed out");
        verify(client).send(any(HttpRequest.class), bodyHandler());
    }

    @Test
    void doesNotRetryHttpServerErrors() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = response(500, "temporary upstream error");
        when(client.send(any(HttpRequest.class), bodyHandler()))
            .thenReturn(response);

        assertThatThrownBy(() -> new SupplierHttpClient(client).post(PROFILE, MUTATION))
            .isInstanceOf(SupplierTransportException.class)
            .hasMessageContaining("HTTP 500");
        verify(client).send(any(HttpRequest.class), bodyHandler());
    }

    private static HttpResponse.BodyHandler<String> bodyHandler() {
        return org.mockito.ArgumentMatchers.any();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
