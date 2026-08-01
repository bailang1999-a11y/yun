package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class MemberCallbackUrlPolicyTest {
    @Test
    void acceptsHttpAndHttpsSyntax() {
        assertThat(MemberCallbackUrlPolicy.validateSyntax("https://callback.example.com/orders").getScheme())
            .isEqualTo("https");
        assertThat(MemberCallbackUrlPolicy.validateSyntax("http://callback.example.com/orders").getScheme())
            .isEqualTo("http");
    }

    @Test
    void rejectsCredentialsFragmentsAndNonHttpSchemes() {
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.validateSyntax("ftp://example.com/callback"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.validateSyntax("https://user@example.com/callback"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.validateSyntax("https://example.com/callback#secret"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLoopbackAndPrivateTargetsBeforeSending() {
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.validateConfiguration("http://localhost/callback"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.validateConfiguration("http://127.0.0.1/callback"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.requirePublicUri("http://127.0.0.1/callback"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
        assertThatThrownBy(() -> MemberCallbackUrlPolicy.requirePublicUri("http://10.0.0.1/callback"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("private");
    }

    @Test
    void pinsTheValidatedAddressesForTheActualHttpConnection() throws Exception {
        InetAddress publicAddress = InetAddress.getByName("93.184.216.34");
        MemberCallbackUrlPolicy.ResolvedTarget target = MemberCallbackUrlPolicy.resolvePublicTarget(
            "https://callback.example.com/orders", host -> new InetAddress[]{publicAddress}
        );
        MemberOrderCallbackClient.PinnedDnsResolver resolver =
            new MemberOrderCallbackClient.PinnedDnsResolver(target);

        assertThat(resolver.resolve("callback.example.com")).containsExactly(publicAddress);
        assertThatThrownBy(() -> resolver.resolve("other.example.com"))
            .isInstanceOf(java.net.UnknownHostException.class);
    }

    @Test
    void rejectsAHostnameWhenItsResolvedAddressIsPrivate() throws Exception {
        InetAddress privateAddress = InetAddress.getByName("169.254.169.254");

        assertThatThrownBy(() -> MemberCallbackUrlPolicy.resolvePublicTarget(
            "https://callback.example.com/orders", host -> new InetAddress[]{privateAddress}
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private");
    }
}
