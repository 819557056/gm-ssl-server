/*
 * Copyright (C) 2023, 2024, THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

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

import lombok.Getter;
import lombok.Setter;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.net.SSLUtil;
import org.apache.tomcat.util.net.SSLUtilBase;
import org.apache.tomcat.util.net.jsse.JSSEImplementation;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.util.ResourceUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@Configuration
@Order(2)
public class TomcatServer {

    @Setter
    @Getter
    private static int globalSessionTimeout = 28800; // 默认8小时

    static {
        KonaProviderRegistrar.register();
    }

    public static void main(String[] args) {
//        System.setProperty("com.tencent.kona.ssl.debug", "all");
//        SpringApplication.run(TomcatServer.class, args);
        new SpringApplicationBuilder(GmSSLConfig.class)
                .child(TomcatServer.class)
                .run(args);
    }

    @RestController
    public static class ResponseController {

        @GetMapping("/tomcat")
        public String response() {
            return "This is a testing server on Tencent Kona SM Suite";
        }
    }

    @Bean
    public TomcatServletWebServerFactory webServerFactory(GmSSLConfig gmSSLConfig)
            throws CertificateException, KeyStoreException, IOException,
            NoSuchAlgorithmException, NoSuchProviderException {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory() {

            @Override
            protected void postProcessContext(Context context) {
                SecurityConstraint securityConstraint = new SecurityConstraint();
                securityConstraint.setUserConstraint("CONFIDENTIAL");
                SecurityCollection collection = new SecurityCollection();
                collection.addPattern("/*");
                securityConstraint.addCollection(collection);
                context.addConstraint(securityConstraint);
            }
        };
        tomcat.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
        return tomcat;
    }

    private Connector httpsConnector(GmSSLConfig gmSSLConfig)
            throws CertificateException, KeyStoreException, IOException,
            NoSuchAlgorithmException, NoSuchProviderException {
        KonaProviderRegistrar.register();

        // 设置 SSL Session 超时时间
        setGlobalSessionTimeout(gmSSLConfig.getSessionTimeout());
        
        Connector connector = new Connector(
                TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("https");
        connector.setProperty("SSLEnabled", Boolean.toString(gmSSLConfig.isSslEnabled()));
        connector.setProperty("sslImplementationName", KonaSSLImpl.class.getName());
        connector.setPort(gmSSLConfig.getPort());

        SSLHostConfig sslConfig = new KonaSSLHostConfig(gmSSLConfig);
        SSLHostConfigCertificate certConfig = new SSLHostConfigCertificate(
                sslConfig, SSLHostConfigCertificate.Type.EC);

        // 添加客户端证书验证配置
        /**
         * required：强制要求客户端提供证书，如果客户端没有证书或证书无效，连接会被拒绝
         * optional：客户端可以选择提供证书，如果提供了会进行验证，不提供也允许连接
         * none：不验证客户端证书（默认行为）
         */
        sslConfig.setCertificateVerification(gmSSLConfig.getClientAuth());  // 启用强制客户端证书验证

        certConfig.setCertificateKeystoreProvider(gmSSLConfig.getEffectiveKeyStoreProvider());
        certConfig.setCertificateKeystoreType(gmSSLConfig.getKeyStoreType());
        certConfig.setCertificateKeystore(createKeyStore(
                gmSSLConfig.getKeyStoreType(), gmSSLConfig.getEffectiveKeyStoreProvider(),
                gmSSLConfig.getKeyStorePath(),
                gmSSLConfig.getKeyStorePassword().toCharArray()));
        certConfig.setCertificateKeystorePassword(gmSSLConfig.getKeyStorePassword());
        sslConfig.addCertificate(certConfig);
        sslConfig.setTrustStore(createKeyStore(
                gmSSLConfig.getTrustStoreType(), gmSSLConfig.getEffectiveTrustStoreProvider(),
                gmSSLConfig.getTrustStorePath(),
                gmSSLConfig.getTrustStorePassword().toCharArray()));
        connector.addSslHostConfig(sslConfig);

        return connector;
    }

    private static KeyStore createKeyStore(
            String storeType, String storeProvider, String storePath, char[] password)
            throws KeyStoreException, IOException, CertificateException,
            NoSuchAlgorithmException, NoSuchProviderException {
        KeyStore keyStore = KeyStore.getInstance(storeType, storeProvider);
        try (InputStream in = new FileInputStream(
                ResourceUtils.getFile(storePath))) {
            keyStore.load(in, password);
        }

        return keyStore;
    }

    public static class KonaSSLHostConfig extends SSLHostConfig {

        private static final long serialVersionUID = 3931709572625017292L;

        private final String sslProvider;
        private Set<String> protocols;
        private List<String> ciphersuites;

        public KonaSSLHostConfig() {
            this.sslProvider = KonaSecurityConstants.PROVIDER_KONA;
        }

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
            return new KonaSSLUtil(certificate, getGlobalSessionTimeout());
        }
    }

    public static class KonaSSLUtil extends SSLUtilBase {

        private static final Log LOG = LogFactory.getLog(KonaSSLUtil.class);

        private Set<String> protocols;
        private Set<String> ciphersuites;
        private int sessionTimeout = 28800; // 默认8小时

        public KonaSSLUtil(SSLHostConfigCertificate certificate) {
            super(certificate);
        }

        public KonaSSLUtil(SSLHostConfigCertificate certificate,
                           boolean warnTls13) {
            super(certificate, warnTls13);
        }

        public KonaSSLUtil(SSLHostConfigCertificate certificate, int sessionTimeout) {
            super(certificate);
            this.sessionTimeout = sessionTimeout;
        }

        public KonaSSLUtil(SSLHostConfigCertificate certificate,
                           boolean warnTls13, int sessionTimeout) {
            super(certificate, warnTls13);
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
            if (trustStore != null) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        KonaSecurityConstants.TRUST_MANAGER_ALGORITHM, sslProvider());
                tmf.init(trustStore);
                return tmf.getTrustManagers();
            }

            return null;
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
                ciphersuites = Collections.unmodifiableSet(
                        new HashSet<>(KonaSecurityConstants.GM_CIPHER_SUITES));
            }

            return ciphersuites;
        }

        @Override
        protected boolean isTls13RenegAuthAvailable() {
            // TLS 1.3 does not support authentication after the initial handshake
            return false;
        }

        @Override
        public org.apache.tomcat.util.net.SSLContext createSSLContextInternal(
                List<String> negotiableProtocols)
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

    public static class KonaSSLContext
            implements org.apache.tomcat.util.net.SSLContext {

        private final SSLContext context;
        private KeyManager[] kms;
        private TrustManager[] tms;
        private int sessionTimeout = 28800; // 默认8小时

        public KonaSSLContext(String protocol)
                throws NoSuchAlgorithmException, NoSuchProviderException {
            this(protocol, KonaSecurityConstants.PROVIDER_KONA, 28800);
        }

        public KonaSSLContext(String protocol, int sessionTimeout)
                throws NoSuchAlgorithmException, NoSuchProviderException {
            this(protocol, KonaSecurityConstants.PROVIDER_KONA, sessionTimeout);
        }

        public KonaSSLContext(String protocol, String provider, int sessionTimeout)
                throws NoSuchAlgorithmException, NoSuchProviderException {
            context = SSLContext.getInstance(protocol, provider);
            this.sessionTimeout = sessionTimeout;
        }

        @Override
        public void init(KeyManager[] kms, TrustManager[] tms, SecureRandom random)
                throws KeyManagementException {
            this.kms = kms;
            this.tms = tms;
            context.init(kms, tms, random);
            
            // 设置 SSL Session 超时时间
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
                for (int i = 0; i < kms.length && result == null; i++) {
                    if (kms[i] instanceof X509KeyManager) {
                        result = ((X509KeyManager) kms[i]).getCertificateChain(alias);
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
