package cn.byzk.example.sslserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TomcatServerTest {

    @Test
    void gmSslConfigDefaultsMissingProvidersToKona() {
        GmSSLConfig config = gmSslConfig(null, null, null);

        assertThat(config.getEffectiveProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(config.getEffectiveKeyStoreProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(config.getEffectiveTrustStoreProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
    }

    @Test
    void gmSslConfigUsesSpecificStoreProviderOverrides() {
        GmSSLConfig config = gmSslConfig(
                KonaSecurityConstants.PROVIDER_KONA_SSL,
                KonaSecurityConstants.PROVIDER_KONA_PKIX,
                KonaSecurityConstants.PROVIDER_KONA_PKIX);

        assertThat(config.getEffectiveProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA_SSL);
        assertThat(config.getEffectiveKeyStoreProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA_PKIX);
        assertThat(config.getEffectiveTrustStoreProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA_PKIX);
    }

    @Test
    void konaSslHostConfigCopiesConfiguredProvidersProtocolAndSessionTimeout() {
        GmSSLConfig config = gmSslConfig(
                KonaSecurityConstants.PROVIDER_KONA_SSL,
                KonaSecurityConstants.PROVIDER_KONA_PKIX,
                KonaSecurityConstants.PROVIDER_KONA_PKIX);
        ReflectionTestUtils.setField(config, "contextProtocol", "TLCPv1.1");
        ReflectionTestUtils.setField(config, "trustStoreType", "PKCS12");
        ReflectionTestUtils.setField(config, "trustStorePassword", "123456");
        ReflectionTestUtils.setField(config, "sessionTimeout", 600);

        TomcatServer.KonaSSLHostConfig hostConfig = new TomcatServer.KonaSSLHostConfig(config);

        assertThat(hostConfig.getSslProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA_SSL);
        assertThat(hostConfig.getSslProtocol()).isEqualTo("TLCPv1.1");
        assertThat(hostConfig.getTruststoreProvider()).isEqualTo(KonaSecurityConstants.PROVIDER_KONA_PKIX);
        assertThat(hostConfig.getTruststoreType()).isEqualTo("PKCS12");
        assertThat(hostConfig.getTruststorePassword()).isEqualTo("123456");
        assertThat(hostConfig.getSessionTimeout()).isEqualTo(600);
        assertThat(hostConfig.getProtocols()).containsExactlyInAnyOrderElementsOf(KonaSecurityConstants.GM_PROTOCOLS);
        assertThat(hostConfig.getJsseCipherNames()).containsExactlyElementsOf(KonaSecurityConstants.GM_CIPHER_SUITES);
    }

    @Test
    void konaSslUtilUsesConfiguredProviderForContextCreation() throws Exception {
        KonaProviderRegistrar.register();
        GmSSLConfig config = gmSslConfig(KonaSecurityConstants.PROVIDER_KONA_SSL, null, null);
        ReflectionTestUtils.setField(config, "contextProtocol", "TLCP");
        ReflectionTestUtils.setField(config, "trustStoreType", "PKCS12");
        ReflectionTestUtils.setField(config, "trustStorePassword", "123456");
        ReflectionTestUtils.setField(config, "sessionTimeout", 120);

        TomcatServer.KonaSSLHostConfig hostConfig = new TomcatServer.KonaSSLHostConfig(config);
        TomcatServer.KonaSSLUtil sslUtil = new TomcatServer.KonaSSLUtil(
                new org.apache.tomcat.util.net.SSLHostConfigCertificate(
                        hostConfig, org.apache.tomcat.util.net.SSLHostConfigCertificate.Type.EC),
                120);

        org.apache.tomcat.util.net.SSLContext sslContext = sslUtil.createSSLContextInternal(List.of());
        Object delegateContext = readField(sslContext, "context");

        assertThat(delegateContext).isInstanceOf(javax.net.ssl.SSLContext.class);
        assertThat(((javax.net.ssl.SSLContext) delegateContext).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA_SSL);
    }

    @Test
    void konaSslContextAppliesConfiguredSessionTimeout() throws Exception {
        KonaProviderRegistrar.register();
        TomcatServer.KonaSSLContext sslContext = new TomcatServer.KonaSSLContext(
                "TLCP",
                KonaSecurityConstants.PROVIDER_KONA,
                90);

        sslContext.init(null, null, null);

        assertThat(sslContext.getServerSessionContext().getSessionTimeout()).isEqualTo(90);
    }

    private static GmSSLConfig gmSslConfig(String provider, String keyStoreProvider, String trustStoreProvider) {
        GmSSLConfig config = new GmSSLConfig();
        ReflectionTestUtils.setField(config, "provider", provider);
        ReflectionTestUtils.setField(config, "keyStoreProvider", keyStoreProvider);
        ReflectionTestUtils.setField(config, "trustStoreProvider", trustStoreProvider);
        return config;
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
