package cn.byzk.example.sslserver.config;

import java.security.Provider;
import java.security.Security;

import com.tencent.kona.KonaProvider;
import com.tencent.kona.crypto.KonaCryptoProvider;
import com.tencent.kona.pkix.KonaPKIXProvider;
import com.tencent.kona.ssl.KonaSSLProvider;

public final class KonaProviderRegistrar {

    private KonaProviderRegistrar() {
    }

    public static synchronized void register() {
        insertIfMissing(KonaSecurityConstants.PROVIDER_KONA, new KonaProvider(), 1);
        insertIfMissing(KonaSecurityConstants.PROVIDER_KONA_CRYPTO, new KonaCryptoProvider(), 2);
        insertIfMissing(KonaSecurityConstants.PROVIDER_KONA_PKIX, new KonaPKIXProvider(), 3);
        insertIfMissing(KonaSecurityConstants.PROVIDER_KONA_SSL, new KonaSSLProvider(), 4);
    }

    private static void insertIfMissing(String name, Provider provider, int position) {
        if (Security.getProvider(name) == null) {
            Security.insertProviderAt(provider, position);
        }
    }
}
