package cn.byzk.example.sslserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyStore;
import java.security.Security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

class KonaProviderRegistrarTest {

    @Test
    void registerAddsAllKonaProvidersAndCoreServices() throws Exception {
        KonaProviderRegistrar.register();

        assertThat(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA)).isNotNull();
        assertThat(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA_CRYPTO)).isNotNull();
        assertThat(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA_PKIX)).isNotNull();
        assertThat(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA_SSL)).isNotNull();

        assertThat(KeyStore.getInstance("PKCS12", KonaSecurityConstants.PROVIDER_KONA).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(KeyManagerFactory.getInstance(
                KonaSecurityConstants.KEY_MANAGER_ALGORITHM,
                KonaSecurityConstants.PROVIDER_KONA).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(TrustManagerFactory.getInstance(
                KonaSecurityConstants.TRUST_MANAGER_ALGORITHM,
                KonaSecurityConstants.PROVIDER_KONA).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(SSLContext.getInstance("TLCP", KonaSecurityConstants.PROVIDER_KONA).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA);
        assertThat(SSLContext.getInstance("TLCP", KonaSecurityConstants.PROVIDER_KONA_SSL).getProvider().getName())
                .isEqualTo(KonaSecurityConstants.PROVIDER_KONA_SSL);
    }

    @Test
    void registerCanBeCalledRepeatedly() {
        KonaProviderRegistrar.register();
        int konaPosition = Security.insertProviderAt(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA), 1);

        KonaProviderRegistrar.register();

        assertThat(konaPosition).isEqualTo(-1);
        assertThat(Security.getProvider(KonaSecurityConstants.PROVIDER_KONA)).isNotNull();
    }
}
