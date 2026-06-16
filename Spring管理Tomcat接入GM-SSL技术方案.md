# Spring 管理的 Tomcat 接入 GM SSL 技术方案

## 1. 背景

当前 `ssl-server` demo 已经可以在 OpenJDK 17 下通过 Tencent Kona 组件完成 GM SSL/TLCP 通信。现有 demo 中 GM SSL 的 Tomcat 配置主要集中在：

- `GmSSLConfig`
- `KonaProviderRegistrar`
- `KonaSecurityConstants`
- `TomcatServer`

其中 `TomcatServer` 里存在一个测试入口：

```java
new SpringApplicationBuilder(GmSSLConfig.class)
        .child(TomcatServer.class)
        .run(args);
```

这个写法适合 demo 验证，但不适合直接迁入正式项目。正式项目通常已经由 Spring Boot 启动了内嵌 Tomcat，如果再单独通过 `SpringApplicationBuilder(...).child(...)` 启动一个 Tomcat 配置上下文，会造成启动链路不统一、Bean 生命周期不清晰、端口和 Connector 管理不集中等问题。

正式项目建议采用：

```java
WebServerFactoryCustomizer<TomcatServletWebServerFactory>
```

把 GM SSL Connector 注入到 Spring Boot 当前正在管理的内嵌 Tomcat 中，而不是额外启动一个新的 Spring/Tomcat 上下文。

---

## 2. 当前 demo 的关键逻辑

### 2.1 Provider 注册

OpenJDK 17 默认不支持 TLCP/国密套件，需要在应用启动早期注册 Kona Provider：

```java
KonaProviderRegistrar.register();
```

注册顺序：

```text
1. Kona
2. KonaCrypto
3. KonaPKIX
4. KonaSSL
```

这部分代码可以直接迁移到正式项目。

---

### 2.2 GM SSL 常量

当前项目使用的关键常量是：

```java
public static final String KEY_MANAGER_ALGORITHM = "NewSunX509";
public static final String TRUST_MANAGER_ALGORITHM = "PKIX";

public static final String PROTOCOL_TLCP_V1_1 = "TLCPv1.1";
public static final String PROTOCOL_TLS_V1_3 = "TLSv1.3";

public static final List<String> GM_CIPHER_SUITES = List.of(
        "TLCP_ECC_SM4_CBC_SM3",
        "TLCP_ECDHE_SM4_CBC_SM3"
);
```

注意：正式项目不要使用旧格式 cipher suite 名称，例如：

```text
ECC-SM2-SM4-CBC-SM3
ECC-SM2-WITH-SM4-SM3
ECDHE-SM2-WITH-SM4-SM3
```

OpenJDK 17 + Kona 当前 demo 跑通的是：

```text
TLCP_ECC_SM4_CBC_SM3
TLCP_ECDHE_SM4_CBC_SM3
```

---

### 2.3 当前 demo 的 Tomcat 接入点

当前 demo 的接入点是：

```java
@Bean
public TomcatServletWebServerFactory webServerFactory(GmSSLConfig gmSSLConfig) {
    TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
    tomcat.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
    return tomcat;
}
```

这个方式会创建一个 `TomcatServletWebServerFactory` Bean。对于纯 demo 项目可以使用；但正式项目中，如果已有其他自动配置或业务配置也在定制 Tomcat，直接声明新的 `TomcatServletWebServerFactory` Bean 可能覆盖或干扰 Spring Boot 原有的 Tomcat 工厂配置。

---

## 3. 正式项目推荐方案

正式项目推荐保留 Spring Boot 默认创建的 Tomcat 工厂，然后通过 `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 增量添加 GM SSL Connector。

推荐结构：

```text
Spring Boot 主应用
    |
    |-- KonaProviderRegistrar.register()
    |
    |-- GmSSLConfig 读取 server.gm-ssl.* 配置
    |
    |-- GmTomcatWebServerCustomizer
            |
            |-- factory.addAdditionalTomcatConnectors(gmHttpsConnector)
            |
            |-- gmHttpsConnector 使用 KonaSSLImpl
            |
            |-- KonaSSLImpl 创建 KonaSSLUtil
            |
            |-- KonaSSLUtil 创建 KonaSSLContext
            |
            |-- KonaSSLContext 内部使用 SSLContext.getInstance(protocol, "Kona")
```

---

## 4. 正式项目代码示例

### 4.1 主启动类

正式项目主启动类里应尽早注册 Kona Provider：

```java
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        KonaProviderRegistrar.register();
        SpringApplication.run(Application.class, args);
    }
}
```

也可以在 GM SSL 配置类中使用 `static` 代码块兜底：

```java
static {
    KonaProviderRegistrar.register();
}
```

---

### 4.2 推荐新增配置类

建议在正式项目中新增配置类，例如：

```java
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

@Configuration
@Order(0)
@ConditionalOnProperty(prefix = "server.gm-ssl", name = "enabled", havingValue = "true")
public class GmTomcatWebServerCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static int globalSessionTimeout = 28800;

    private final GmSSLConfig gmSSLConfig;

    public GmTomcatWebServerCustomizer(GmSSLConfig gmSSLConfig) {
        this.gmSSLConfig = gmSSLConfig;
    }

    static {
        KonaProviderRegistrar.register();
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        try {
            factory.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
        } catch (Exception e) {
            throw new IllegalStateException("初始化 GM SSL Tomcat Connector 失败", e);
        }
    }

    private Connector httpsConnector(GmSSLConfig gmSSLConfig)
            throws CertificateException, KeyStoreException, IOException,
            NoSuchAlgorithmException, NoSuchProviderException {
        KonaProviderRegistrar.register();

        globalSessionTimeout = gmSSLConfig.getSessionTimeout();

        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(gmSSLConfig.getPort());
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
        return connector;
    }

    private static KeyStore createKeyStore(
            String storeType,
            String storeProvider,
            String storePath,
            char[] password)
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
                ciphersuites = Collections.unmodifiableSet(
                        new HashSet<>(KonaSecurityConstants.GM_CIPHER_SUITES));
            }
            return ciphersuites;
        }

        @Override
        protected boolean isTls13RenegAuthAvailable() {
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
        public void init(KeyManager[] kms, TrustManager[] tms, SecureRandom random)
                throws KeyManagementException {
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
```

---

## 5. 与当前 demo 的差异

### 5.1 demo 写法

当前 demo 中存在：

```java
new SpringApplicationBuilder(GmSSLConfig.class)
        .child(TomcatServer.class)
        .run(args);
```

这相当于为了测试 GM SSL 单独启动了一套配置上下文。

### 5.2 正式项目写法

正式项目中不要新增这个 `main` 方法，也不要通过 `child(TomcatServer.class)` 再启动一套上下文。

应改成：

```java
@Configuration
public class GmTomcatWebServerCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
    }
}
```

这样 GM SSL Connector 会被写入当前 Spring Boot 管理的 Tomcat。

---

## 6. 配置示例

正式项目可以继续使用当前 demo 的配置结构：

```yaml
server:
  port: 7777
  gm-ssl:
    ssl-gm-port: 8888
    enabled: true
    provider: Kona
    trust-store-provider: Kona
    trust-store-type: PKCS12
    trust-store: ssl/truststore.p12
    trust-store-password: 123456
    key-store-provider: Kona
    key-store-type: PKCS12
    key-store: ssl/keystore.p12
    key-store-password: 123456
    protocol: TLCP
    client-auth: required
    session-timeout: 28800
```

说明：

| 配置项 | 说明 |
|---|---|
| `server.port` | 原有 Spring Boot HTTP/HTTPS 端口 |
| `server.gm-ssl.ssl-gm-port` | 新增 GM SSL 监听端口 |
| `server.gm-ssl.enabled` | 是否启用 GM SSL Connector |
| `server.gm-ssl.provider` | JCA Provider，当前使用 `Kona` |
| `server.gm-ssl.key-store` | 服务端证书库 |
| `server.gm-ssl.trust-store` | 信任库，用于校验客户端证书 |
| `server.gm-ssl.protocol` | SSLContext 协议，当前 demo 使用 `TLCP` |
| `server.gm-ssl.client-auth` | `required`、`optional`、`none` |
| `server.gm-ssl.session-timeout` | SSL Session 超时时间，单位秒 |

---

## 7. 迁移步骤

### 步骤 1：引入 Kona 依赖

正式项目 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-provider</artifactId>
    <version>1.0.13</version>
</dependency>
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-crypto</artifactId>
    <version>1.0.13</version>
</dependency>
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-pkix</artifactId>
    <version>1.0.13</version>
</dependency>
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-ssl</artifactId>
    <version>1.0.13</version>
</dependency>
```

### 步骤 2：迁移公共类

从 demo 迁移：

```text
KonaProviderRegistrar
KonaSecurityConstants
GmSSLConfig
```

### 步骤 3：删除 demo 式启动逻辑

不要迁移以下代码：

```java
new SpringApplicationBuilder(GmSSLConfig.class)
        .child(TomcatServer.class)
        .run(args);
```

### 步骤 4：新增 `WebServerFactoryCustomizer`

新增 `GmTomcatWebServerCustomizer`，通过：

```java
factory.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
```

把 GM SSL Connector 注入到当前 Spring Boot 管理的 Tomcat。

### 步骤 5：保留 Kona SSL 实现类

需要保留或迁移以下内部类：

```text
KonaSSLHostConfig
KonaSSLImpl
KonaSSLUtil
KonaSSLContext
```

它们负责让 Tomcat 的 JSSE 流程最终使用：

```java
SSLContext.getInstance(protocol, "Kona")
KeyManagerFactory.getInstance("NewSunX509", "Kona")
TrustManagerFactory.getInstance("PKIX", "Kona")
```

### 步骤 6：验证端口

启动后验证：

```bash
netstat -an | grep 8888
```

或：

```bash
ss -lntp | grep 8888
```

再使用支持 TLCP/GM SSL 的 client 访问 GM SSL 端口。

---

## 8. 注意事项

### 8.1 不要覆盖 Spring Boot 默认 Tomcat 工厂

正式项目不要随意声明：

```java
@Bean
public TomcatServletWebServerFactory webServerFactory(...)
```

除非确定项目中没有其他地方依赖 Spring Boot 默认的 Tomcat 工厂配置。

推荐使用：

```java
WebServerFactoryCustomizer<TomcatServletWebServerFactory>
```

这是增量定制，不会替换 Spring Boot 原有 Tomcat 工厂。

---

### 8.2 Provider 必须尽早注册

Kona Provider 应在创建 `KeyStore`、`KeyManagerFactory`、`TrustManagerFactory`、`SSLContext` 之前注册。

建议：

```java
public static void main(String[] args) {
    KonaProviderRegistrar.register();
    SpringApplication.run(Application.class, args);
}
```

配置类中也可以兜底注册。

---

### 8.3 证书库 Provider 使用 Kona

当前 demo 跑通的组合是：

```java
KeyStore.getInstance("PKCS12", "Kona")
KeyManagerFactory.getInstance("NewSunX509", "Kona")
TrustManagerFactory.getInstance("PKIX", "Kona")
SSLContext.getInstance(protocol, "Kona")
```

不要混用 `KonaSSL` 作为 `KeyManagerFactory` 或 `TrustManagerFactory` 的 provider。

---

### 8.4 GM SSL 端口与原端口关系

如果正式项目原来已经有：

```yaml
server:
  port: 8080
```

新增 GM SSL 后，可以变成两个端口：

```text
8080  原有 Spring Boot 端口
8888  GM SSL/TLCP 端口
```

如果希望只保留 GM SSL 端口，则需要根据正式项目部署方式关闭或重定向原端口。

---

## 9. 推荐最终落地结构

```text
src/main/java/.../config
    ├── GmSSLConfig.java
    ├── KonaProviderRegistrar.java
    ├── KonaSecurityConstants.java
    └── GmTomcatWebServerCustomizer.java

src/main/resources
    └── application.yml
```

正式项目中不建议继续保留 demo 的 `TomcatServer.main()`。

---

## 10. 结论

当前 demo 已经证明 OpenJDK 17 可以通过 Tencent Kona 完成 GM SSL/TLCP 通信。正式项目改造时，核心不是再启动一个新的 Tomcat，而是把当前 demo 中的 `httpsConnector(...)`、`KonaSSLImpl`、`KonaSSLUtil`、`KonaSSLContext` 迁移到 `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 中。

推荐方案一句话总结：

```text
用 Kona Provider 注册国密能力，用自定义 SSLImplementation 接管 Tomcat SSLContext 创建，用 WebServerFactoryCustomizer 把 GM SSL Connector 注入 Spring Boot 已管理的内嵌 Tomcat。
```

---

## 11. 通过 application.yml 支持 RSA / GM 切换

正式项目通常需要同时兼容两种 SSL 模式：

```text
1. 普通 RSA SSL / TLS
2. 国密 GM SSL / TLCP
```

### 11.1 是否有现成参数可以区分 RSA 和 GM

Spring Boot 自带的标准配置中没有一个明确表示“当前使用 RSA 还是 GM”的参数。

例如 Spring Boot 常见配置：

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:server.p12
    key-store-password: 123456
    key-store-type: PKCS12
    protocol: TLS
```

这些配置只描述普通 JSSE/TLS SSL，并不能天然表达“是否启用 GM/TLCP”。

当前 demo 中使用的是自定义配置：

```yaml
server:
  gm-ssl:
    enabled: true
    protocol: TLCP
```

其中 `server.gm-ssl.enabled` 可以表示是否启用 GM SSL Connector，但它不能完整表示当前整体 SSL 模式是 RSA 还是 GM。

如果正式项目需要“RSA/GM 二选一”或者“同一套配置结构动态切换”，建议新增一个明确的业务参数。

---

### 11.2 是否可以使用 `server.gm-ssl.protocol` 区分 RSA 和 GM

技术上可以，但不推荐。

例如可以根据：

```yaml
server:
  gm-ssl:
    protocol: TLCP
```

判断当前是 GM；根据：

```yaml
server:
  gm-ssl:
    protocol: TLS
```

判断当前是 RSA/TLS。

但是这种设计存在几个问题：

#### 1. `protocol` 表示的是 SSLContext 协议，不是业务模式

`protocol` 更适合表达：

```text
TLCP
TLCPv1.1
TLS
TLSv1.2
TLSv1.3
```

它描述的是底层协议名称，不应该承担“RSA/GM 模式开关”的职责。

#### 2. 后续扩展容易混乱

例如将来可能出现：

```yaml
protocol: TLCP
protocol: TLCPv1.1
protocol: TLSv1.3
protocol: TLS
```

如果用 protocol 判断 RSA/GM，代码里会出现很多字符串判断：

```java
if ("TLCP".equalsIgnoreCase(protocol) || "TLCPv1.1".equalsIgnoreCase(protocol)) {
    // GM
} else {
    // RSA
}
```

这会让协议选择和证书体系选择耦合在一起。

#### 3. RSA/GM 的差异不只在 protocol

GM 和 RSA 的差异至少包括：

```text
Provider
SSLContext protocol
KeyManagerFactory provider
TrustManagerFactory provider
cipher suites
证书类型
客户端兼容性
```

所以仅靠 `protocol` 判断是不够清晰的。

结论：

```text
server.gm-ssl.protocol 可以作为底层协议配置，但不建议作为 RSA/GM 模式区分参数。
```

---

### 11.3 推荐新增参数：`server.ssl-mode`

推荐在正式项目中新增一个明确参数：

```yaml
server:
  ssl-mode: GM
```

可选值：

```text
RSA
GM
```

示例：

```yaml
server:
  ssl-mode: GM
  port: 7777
  gm-ssl:
    enabled: true
    ssl-gm-port: 8888
    provider: Kona
    trust-store-provider: Kona
    trust-store-type: PKCS12
    trust-store: ssl/truststore.p12
    trust-store-password: 123456
    key-store-provider: Kona
    key-store-type: PKCS12
    key-store: ssl/keystore.p12
    key-store-password: 123456
    protocol: TLCP
    client-auth: required
    session-timeout: 28800
```

RSA 模式：

```yaml
server:
  ssl-mode: RSA
  port: 8443
  ssl:
    enabled: true
    key-store: ssl/rsa-server.p12
    key-store-password: 123456
    key-store-type: PKCS12
    trust-store: ssl/rsa-truststore.p12
    trust-store-password: 123456
    trust-store-type: PKCS12
    client-auth: need
    protocol: TLS
```

这种设计的优点是：

```text
1. ssl-mode 表达业务模式
2. protocol 表达底层协议
3. gm-ssl 配置只负责 GM Connector
4. server.ssl 配置继续交给 Spring Boot 原生 RSA/TLS 逻辑
5. 代码判断简单，不依赖 protocol 字符串猜测
```

---

### 11.4 推荐配置类写法

可以新增枚举：

```java
public enum SslMode {
    RSA,
    GM
}
```

新增配置：

```java
@Value("${server.ssl-mode:RSA}")
private SslMode sslMode;
```

或者使用独立配置类：

```java
@Configuration
@ConfigurationProperties(prefix = "server")
public class ServerSslModeProperties {

    private SslMode sslMode = SslMode.RSA;

    public SslMode getSslMode() {
        return sslMode;
    }

    public void setSslMode(SslMode sslMode) {
        this.sslMode = sslMode;
    }
}
```

---

### 11.5 GM Connector 启用条件

GM 的 Tomcat 自定义器建议只在 `server.ssl-mode=GM` 时生效：

```java
@Configuration
@ConditionalOnProperty(prefix = "server", name = "ssl-mode", havingValue = "GM")
public class GmTomcatWebServerCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    // GM Connector 注入逻辑
}
```

如果还想保留 `server.gm-ssl.enabled` 作为二级开关，可以同时判断：

```java
@Configuration
@ConditionalOnExpression("'${server.ssl-mode:RSA}' == 'GM' && '${server.gm-ssl.enabled:false}' == 'true'")
public class GmTomcatWebServerCustomizer
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    // GM Connector 注入逻辑
}
```

不过更推荐在代码中显式判断，避免表达式过长：

```java
@Override
public void customize(TomcatServletWebServerFactory factory) {
    if (!gmSSLConfig.isSslEnabled()) {
        return;
    }
    factory.addAdditionalTomcatConnectors(httpsConnector(gmSSLConfig));
}
```

并通过类级别控制模式：

```java
@ConditionalOnProperty(prefix = "server", name = "ssl-mode", havingValue = "GM")
```

---

### 11.6 是否继续保留 `server.gm-ssl.enabled`

建议保留。

推荐职责划分：

| 参数 | 职责 |
|---|---|
| `server.ssl-mode` | 选择 SSL 模式：`RSA` 或 `GM` |
| `server.ssl.*` | Spring Boot 原生 RSA/TLS 配置 |
| `server.gm-ssl.enabled` | 是否启用 GM Connector |
| `server.gm-ssl.protocol` | GM SSLContext 使用的协议，例如 `TLCP` |
| `server.gm-ssl.provider` | GM Provider，例如 `Kona` |
| `server.gm-ssl.client-auth` | GM 双向认证模式 |

最终推荐：

```yaml
server:
  ssl-mode: GM
  gm-ssl:
    enabled: true
    protocol: TLCP
```

不推荐：

```yaml
server:
  gm-ssl:
    protocol: GM
```

也不推荐用：

```yaml
server:
  gm-ssl:
    protocol: TLCP
```

来间接推断当前系统是 GM 模式。

---

### 11.7 如果不想新增参数的折中方案

如果正式项目不希望新增 `server.ssl-mode`，也可以只使用已有的：

```yaml
server:
  gm-ssl:
    enabled: true
```

作为 GM 开关：

```java
@ConditionalOnProperty(prefix = "server.gm-ssl", name = "enabled", havingValue = "true")
```

这也是可以工作的。

但这种方式只能表达：

```text
是否额外启用 GM SSL Connector
```

不能很好表达：

```text
当前系统整体 SSL 模式是 RSA 还是 GM
```

因此，如果正式项目只是在原 RSA 端口之外新增 GM 端口，用 `server.gm-ssl.enabled` 足够。

如果正式项目需要 RSA/GM 二选一，推荐新增：

```yaml
server:
  ssl-mode: RSA 或 GM
```

---

## 12. 最终推荐配置方案

### 12.1 RSA 模式

```yaml
server:
  ssl-mode: RSA
  port: 8443
  ssl:
    enabled: true
    key-store: ssl/rsa-server.p12
    key-store-password: 123456
    key-store-type: PKCS12
    trust-store: ssl/rsa-truststore.p12
    trust-store-password: 123456
    trust-store-type: PKCS12
    client-auth: need
    protocol: TLS
```

RSA 模式下：

```text
1. 使用 Spring Boot 原生 server.ssl.*
2. 不加载 GM Connector
3. 不需要指定 TLCP cipher suite
4. 可以不走 Kona SSLImplementation
```

---

### 12.2 GM 模式

```yaml
server:
  ssl-mode: GM
  port: 7777
  gm-ssl:
    enabled: true
    ssl-gm-port: 8888
    provider: Kona
    trust-store-provider: Kona
    trust-store-type: PKCS12
    trust-store: ssl/truststore.p12
    trust-store-password: 123456
    key-store-provider: Kona
    key-store-type: PKCS12
    key-store: ssl/keystore.p12
    key-store-password: 123456
    protocol: TLCP
    client-auth: required
    session-timeout: 28800
```

GM 模式下：

```text
1. 注册 Kona Provider
2. 创建 GM Connector
3. 设置 sslImplementationName = KonaSSLImpl
4. SSLContext 使用 SSLContext.getInstance(protocol, "Kona")
5. cipher suite 使用 TLCP_ECC_SM4_CBC_SM3、TLCP_ECDHE_SM4_CBC_SM3
```

---

## 13. 关于 `server.gm-ssl.protocol` 的结论

`server.gm-ssl.protocol` 可以继续保留，但它的职责应该是：

```text
指定 GM SSLContext 使用的协议名称
```

例如：

```yaml
server:
  gm-ssl:
    protocol: TLCP
```

或者：

```yaml
server:
  gm-ssl:
    protocol: TLCPv1.1
```

它不建议承担：

```text
区分 RSA / GM 模式
```

最终建议：

```text
如果只是额外增加 GM SSL 端口：使用 server.gm-ssl.enabled 即可。
如果需要 RSA/GM 二选一：新增 server.ssl-mode，值为 RSA 或 GM。
server.gm-ssl.protocol 只用于配置 TLCP/TLS 协议，不用于判断业务模式。
```

---

## 14. 已确认：正式项目 RSA 与 GM 必须二选一

正式项目已确定 RSA 和 GM 不会同时存在，也不会采用“原 RSA 端口 + 新增 GM 端口”的双端口模式。因此最终方案应调整为：同一应用启动时只选择一种 SSL 模式，RSA 或 GM。

这种场景下建议必须新增独立、明确的模式参数：

```yaml
server:
  ssl-mode: RSA
```

或者：

```yaml
server:
  ssl-mode: GM
```

不建议使用 `server.gm-ssl.protocol` 来推断当前是 RSA 还是 GM。`protocol` 的职责是指定底层 SSLContext 协议，例如 `TLCP`、`TLCPv1.1`、`TLS`、`TLSv1.2`、`TLSv1.3`，不应该承担业务模式判断职责。

最终职责边界如下：

| 参数 | 职责 |
|---|---|
| `server.ssl-mode` | 选择 RSA 或 GM |
| `server.ssl.*` | RSA 模式使用，交给 Spring Boot 原生 SSL |
| `server.gm-ssl.*` | GM 模式使用，交给自定义 Tomcat GM SSL 逻辑 |
| `server.gm-ssl.protocol` | GM SSLContext 使用的协议，例如 `TLCP` |

# 附录A：本次改造踩坑记录与最终结论

## A.1 改造目标

原 Demo 方案：

```text
TomcatServer
    ├── SpringApplicationBuilder(...).child(...)
    ├── 新建 TomcatServletWebServerFactory
    └── addAdditionalTomcatConnectors(...)
```

属于验证性质方案。

正式项目要求：

```text
RSA 与 GM 二选一
只保留一个业务端口
不额外创建 Connector
不额外启动 Spring 上下文
```

最终目标：

```text
RSA 模式
    -> Spring Boot 原生 SSL

GM 模式
    -> Kona + TLCP
    -> 接管 Spring Boot 主 Connector
```

---

## A.2 第一次设计的问题

最开始沿用了 Demo 中的配置：

```yaml
server:
  gm-ssl:
    ssl-gm-port: 8888
```

并在：

```java
GmSSLConfig
```

中定义：

```java
@Value("${server.gm-ssl.ssl-gm-port}")
private int port;
```

正式方案中已经取消：

```text
addAdditionalTomcatConnectors(...)
```

因此：

```text
ssl-gm-port
```

已经没有意义。

结果启动时报错：

```text
Could not resolve placeholder
'server.gm-ssl.ssl-gm-port'
```

### 修复

删除：

```java
@Value("${server.gm-ssl.ssl-gm-port}")
private int port;
```

删除：

```java
gmSSLConfig.getPort()
```

统一使用：

```yaml
server:
  port: 8888
```

作为唯一端口。

---

## A.3 第二个问题：出现两个 HTTPS Connector

启动日志：

```text
Tomcat initialized with ports
8888 (https), -1 (https)
```

说明：

```text
新方案 Connector
+
旧 Demo Connector
```

同时存在。

最终报错：

```text
Connector["https-jsse-nio--1"]

The connector cannot start since
the specified port value of [-1]
is invalid
```

### 根因

旧类：

```java
TomcatServer
```

仍然存在：

```java
@Configuration
```

因此 Spring Boot 仍然加载：

```java
@Bean
TomcatServletWebServerFactory
```

并创建旧 Connector。

### 修复

删除：

```java
@Configuration
```

使：

```java
TomcatServer
```

退化为：

```text
历史 Demo 代码
```

不再参与 Spring 容器。

---

## A.4 第三个问题：Spring Boot 默认 SSL 仍在参与

日志：

```text
certificate type [EC]
configured from keystore
[/home/xxx/.keystore]
using alias [tomcat]
```

说明：

```text
Spring Boot 默认 SSL
```

仍在初始化。

而不是：

```text
KonaSSLImpl
KonaSSLContext
```

完全接管。

### 风险

可能出现：

```text
Spring Boot JSSE SSL
+
GM SSL
```

同时初始化。

### 建议

GM 模式下：

```yaml
server:
  ssl:
    enabled: false
```

必须关闭。

由：

```java
GmTomcatWebServerCustomizer
```

接管 SSL。

---

## A.5 最终推荐配置

### RSA 模式

```yaml
server:
  ssl-mode: RSA

  port: 8443

  ssl:
    enabled: true
    key-store: xxx.p12
    key-store-password: xxx
    protocol: TLS
```

说明：

```text
完全使用 Spring Boot 原生 SSL
```

---

### GM 模式

```yaml
server:
  ssl-mode: GM

  port: 8888

  ssl:
    enabled: false

  gm-ssl:
    enabled: true

    provider: Kona

    trust-store-provider: Kona
    trust-store-type: PKCS12
    trust-store: ssl/truststore.p12
    trust-store-password: 123456

    key-store-provider: Kona
    key-store-type: PKCS12
    key-store: ssl/keystore.p12
    key-store-password: 123456

    protocol: TLCP

    client-auth: required

    session-timeout: 28800
```

---

## A.6 正式项目必须迁移的类

```text
KonaProviderRegistrar
KonaSecurityConstants
GmSSLConfig
SslMode
ServerSslModeProperties
GmTomcatWebServerCustomizer
```

---

## A.7 不建议迁移的 Demo 代码

不要迁移：

```java
SpringApplicationBuilder(...).child(...)
```

不要迁移：

```java
addAdditionalTomcatConnectors(...)
```

不要迁移：

```yaml
server.gm-ssl.ssl-gm-port
```

不要迁移：

```java
TomcatServer.main()
```

---

## A.8 正式项目最终结构

```text
config
 ├── GmSSLConfig
 ├── KonaProviderRegistrar
 ├── KonaSecurityConstants
 ├── SslMode
 ├── ServerSslModeProperties
 └── GmTomcatWebServerCustomizer
```

---

## A.9 实施顺序（推荐）

步骤1：

```text
引入 Kona 依赖
```

步骤2：

```text
注册 Kona Provider
```

步骤3：

```text
增加 ssl-mode
```

步骤4：

```text
实现 GmTomcatWebServerCustomizer
```

步骤5：

```text
关闭 server.ssl.enabled
```

步骤6：

```text
验证只存在一个 Connector
```

正确日志应类似：

```text
Tomcat initialized with port(s): 8888 (https)
```

不能出现：

```text
-1 (https)
```

步骤7：

```text
验证 SSLContext 来自 Kona
```

确认不是：

```text
/home/xxx/.keystore
alias tomcat
```

这种 Spring Boot 默认 SSL 初始化日志。

---

## A.10 最终经验总结

正式项目不要从：

```text
新增 Connector
```

思路出发。

应从：

```text
接管 Spring Boot 当前主 Connector
```

思路出发。

这样：

```text
一个端口
一个 Connector
一个 SSL 模式
```

最容易维护，也最符合 RSA/GM 二选一的业务要求。
