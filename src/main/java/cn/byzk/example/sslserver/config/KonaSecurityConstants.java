package cn.byzk.example.sslserver.config;

import java.util.List;
import java.util.Set;

public final class KonaSecurityConstants {

    public static final String PROVIDER_KONA = "Kona";
    public static final String PROVIDER_KONA_CRYPTO = "KonaCrypto";
    public static final String PROVIDER_KONA_PKIX = "KonaPKIX";
    public static final String PROVIDER_KONA_SSL = "KonaSSL";

    public static final String KEY_MANAGER_ALGORITHM = "NewSunX509";
    public static final String TRUST_MANAGER_ALGORITHM = "PKIX";

    public static final String PROTOCOL_TLCP_V1_1 = "TLCPv1.1";
    public static final String PROTOCOL_TLS_V1_3 = "TLSv1.3";

    public static final List<String> GM_CIPHER_SUITES = List.of(
            "TLCP_ECC_SM4_CBC_SM3",
            "TLCP_ECDHE_SM4_CBC_SM3"
    );

    public static final Set<String> GM_PROTOCOLS = Set.of(
            PROTOCOL_TLCP_V1_1,
            PROTOCOL_TLS_V1_3
    );

    private KonaSecurityConstants() {
    }

    public static String defaultProvider(String provider) {
        return hasText(provider) ? provider : PROVIDER_KONA;
    }

    public static String defaultProvider(String provider, String fallbackProvider) {
        return hasText(provider) ? provider : defaultProvider(fallbackProvider);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
