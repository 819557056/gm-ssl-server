package cn.byzk.example.sslserver.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import org.apache.catalina.connector.Connector;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.net.SSLUtil;
import org.apache.tomcat.util.net.SSLUtilBase;
import org.apache.tomcat.util.net.jsse.JSSEImplementation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.util.ResourceUtils;

/**
 * 正式项目 GM/TLCP Tomcat 接入方式。
 *
 * <p>仅在 server.ssl-mode=GM 时生效，不再新增第二个 Connector，
 * 而是直接把 Spring Boot 当前管理的主 Connector 改造成 GM SSL Connector。</p>
 */
@Configuration
@Order(0)
@ConditionalOnProperty(prefix = "server", name = "ssl-mode", havingValue = "GM")
public class GmTomcatWebServerCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static int globalSessionTimeout = 28800;

    private final GmSSLConfig gmSSLConfig;

    static {
        KonaProviderRegistrar.register();
    }

    public GmTomcatWebServerCustomizer(GmSSLConfig gmSSLConfig) {
        this.gmSSLConfig = gmSSLConfig;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            try {
                configureGmConnector(connector, gmSSLConfig);
            } catch (Exception e) {
                throw new IllegalStateException("初始化 GM SSL Tomcat 主 Connector 失败", e);
            }
        });
    }

    private static void configureGmConnector(Connector connector, GmSSLConfig gmSSLConfig)
            throws CertificateException, KeyStoreException, IOException,
            NoSuchAlgorithmException, NoSuchProviderException {
        KonaProviderRegistrar.register();
        globalSessionTimeout = gmSSLConfig.getSessionTimeout();

        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", Boolean.toString(gmSSLConfig.isSslEnabled()));
        connector.setProperty("sslImplementationName", KonaSSLImpl.class.getName());

        SSLHostConfig sslConfig = new KonaSSLHostConfig(gmSSLConfig);
        SSLHostConfigCertificate certConfig = new SSLHostConfigCertificate(
                sslConfig, SSLHostConfigCertificate.Type.EC);

        sslConfig.setCertificateVerification(gmSSLConfig.getClientAuth());

        certConfig.setCertificateKeystoreProvider(gmSSLConfig.getEffectiveKeyStoreProvider());
        certConfig.setCertificateKeystoreType(gmSSLConfig.getKeyStoreType());
        certConfig.setCertificateKeystore(createKeyStore(
                gmSSLConfig.getKeyStoreType(),
                gmSSLConfig.getEffectiveKeyStoreProvider(),
                gmSSLConfig.getKeyStorePath(),
                gmSSLConfig.getKeyStorePassword().toCharArray()));
        certConfig.setCertificateKeystorePassword(gmSSLConfig.getKeyStorePassword());

        sslConfig.addCertificate(certConfig);
        sslConfig.setTrustStore(createKeyStore(
                gmSSLConfig.getTrustStoreType(),
                gmSSLConfig.getEffectiveTrustStoreProvider(),
                gmSSLConfig.getTrustStorePath(),
                gmSSLConfig.getTrustStorePassword().toCharArray()));

        connector.addSslHostConfig(sslConfig);
    }

    private static KeyStore createKeyStore(
            String storeType, String storeProvider, String storePath, char[] password)
            throws KeyStoreException, IOException, CertificateException,
            NoSuchAlgorithmException, NoSuchProviderException {
        KeyStore keyStore = KeyStore.getInstance(storeType, storeProvider);
        try (InputStream in = new FileInputStream(ResourceUtils.getFile(storePath))) {
            keyStore.load(in, password);
        }
        return keyStore;
    }

    public static class KonaSSLHostConfig extends SSLHostConfig {

        private static final long serialVersionUID = 3931709572625017292L;

        private final String sslProvider;
        private Set<String> protocols;
        private List<String> ciphersuites;

        public KonaSSLHostConfig(GmSSLConfig gmSSLConfig) {
            this.sslProvider = gmSSLConfig.getEffectiveProvider();
            setSslProtocol(gmSSLConfig.getContextProtocol());
            setTruststoreProvider(gmSSLConfig.getEffectiveTrustStoreProvider());
            setTruststoreType(gmSSLConfig.getTrustStoreType());
            setTruststorePassword(gmSSLConfig.getTrustStorePassword());
            setSessionTimeout(gmSSLConfig.getSessionTimeout());
        }

        public String getSslProvider() {
            return sslProvider;
        }

        @Override
        public Set<String> getProtocols() {
            if (protocols == null) {
                protocols = new HashSet<>(KonaSecurityConstants.GM_PROTOCOLS);
            }
            return protocols;
        }

        @Override
        public List<String> getJsseCipherNames() {
            if (ciphersuites == null) {
                ciphersuites = Collections.unmodifiableList(KonaSecurityConstants.GM_CIPHER_SUITES);
            }
            return ciphersuites;
        }
    }

    public static class KonaSSLImpl extends JSSEImplementation {

        @Override
        public SSLUtil getSSLUtil(SSLHostConfigCertificate certificate) {
            return new KonaSSLUtil(certificate, globalSessionTimeout);
        }
    }

    public static class KonaSSLUtil extends SSLUtilBase {

        private static final Log LOG = LogFactory.getLog(KonaSSLUtil.class);

        private Set<String> protocols;
        private Set<String> ciphersuites;
        private final int sessionTimeout;

        public KonaSSLUtil(SSLHostConfigCertificate certificate, int sessionTimeout) {
            super(certificate);
            this.sessionTimeout = sessionTimeout;
        }

        @Override
        public KeyManager[] getKeyManagers() throws Exception {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KonaSecurityConstants.KEY_MANAGER_ALGORITHM, sslProvider());
            kmf.init(certificate.getCertificateKeystore(),
                    certificate.getCertificateKeystorePassword().toCharArray());
            return kmf.getKeyManagers();
        }

        @Override
        public TrustManager[] getTrustManagers() throws Exception {
            KeyStore trustStore = sslHostConfig.getTruststore();
            if (trustStore == null) {
                return null;
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    KonaSecurityConstants.TRUST_MANAGER_ALGORITHM, sslProvider());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        }

        @Override
        protected Log getLog() {
            return LOG;
        }

        @Override
        protected Set<String> getImplementedProtocols() {
            if (protocols == null) {
                protocols = new HashSet<>(KonaSecurityConstants.GM_PROTOCOLS);
            }
            return protocols;
        }

        @Override
        protected Set<String> getImplementedCiphers() {
            if (ciphersuites == null) {
                ciphersuites = Collections.unmodifiableSet(new HashSet<>(KonaSecurityConstants.GM_CIPHER_SUITES));
            }
            return ciphersuites;
        }

        @Override
        protected boolean isTls13RenegAuthAvailable() {
            return false;
        }

        @Override
        public org.apache.tomcat.util.net.SSLContext createSSLContextInternal(List<String> negotiableProtocols)
                throws NoSuchAlgorithmException, NoSuchProviderException {
            return new KonaSSLContext(sslHostConfig.getSslProtocol(), sslProvider(), sessionTimeout);
        }

        private String sslProvider() {
            if (sslHostConfig instanceof KonaSSLHostConfig) {
                return ((KonaSSLHostConfig) sslHostConfig).getSslProvider();
            }
            return KonaSecurityConstants.PROVIDER_KONA;
        }
    }

    public static class KonaSSLContext implements org.apache.tomcat.util.net.SSLContext {

        private final SSLContext context;
        private KeyManager[] kms;
        private TrustManager[] tms;
        private final int sessionTimeout;

        public KonaSSLContext(String protocol, String provider, int sessionTimeout)
                throws NoSuchAlgorithmException, NoSuchProviderException {
            this.context = SSLContext.getInstance(protocol, provider);
            this.sessionTimeout = sessionTimeout;
        }

        @Override
        public void init(KeyManager[] kms, TrustManager[] tms, SecureRandom random) throws KeyManagementException {
            this.kms = kms;
            this.tms = tms;
            context.init(kms, tms, random);

            SSLSessionContext sessionContext = context.getServerSessionContext();
            if (sessionContext != null) {
                sessionContext.setSessionTimeout(sessionTimeout);
            }
        }

        @Override
        public void destroy() {
        }

        @Override
        public SSLSessionContext getServerSessionContext() {
            return context.getServerSessionContext();
        }

        @Override
        public SSLEngine createSSLEngine() {
            return context.createSSLEngine();
        }

        @Override
        public SSLServerSocketFactory getServerSocketFactory() {
            return context.getServerSocketFactory();
        }

        @Override
        public SSLParameters getSupportedSSLParameters() {
            return context.getSupportedSSLParameters();
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            X509Certificate[] result = null;
            if (kms != null) {
                for (KeyManager km : kms) {
                    if (km instanceof X509KeyManager) {
                        result = ((X509KeyManager) km).getCertificateChain(alias);
                        if (result != null) {
                            break;
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            Set<X509Certificate> certs = new HashSet<>();
            if (tms != null) {
                for (TrustManager tm : tms) {
                    if (tm instanceof X509TrustManager) {
                        X509Certificate[] accepted = ((X509TrustManager) tm).getAcceptedIssuers();
                        if (accepted != null) {
                            certs.addAll(Arrays.asList(accepted));
                        }
                    }
                }
            }
            return certs.toArray(new X509Certificate[0]);
        }
    }
}
