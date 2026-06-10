# OpenJDK + Tencent Kona 国密 SSL 替换腾讯 JDK 技术方案

## 一、结论

当前项目没有单独的“腾讯 JDK 替换为 OpenJDK + Kona Maven 依赖实现国密 SSL”的专项 Markdown 方案。已有文档包括：

- `README.md`：说明项目使用 JDK 17、Spring Boot、Tencent Kona SM Suite 1.0.13。
- `国密硬件改造方案.md`：说明从 Kona 软件算法改造成硬件密码设备/HSM 的方案。
- `SSL硬件密码设备接口规范.md`：说明硬件密码设备在 SSL/TLCP 场景下应提供的接口。

从代码和编译验证看，当前 `ssl-server/ssl-server` 与 `ssl-server/ssl-client` 已具备使用 OpenJDK 17 + Kona 依赖运行国密 SSL/TLCP 的基础。关键前提是：不能依赖 OpenJDK 自带 JSSE 支持国密，而必须在应用启动时显式注册 Kona Provider，并且在 `KeyStore`、`KeyManagerFactory`、`TrustManagerFactory`、`SSLContext` 创建时指定 Kona Provider。

## 二、当前项目依据

### 2.1 Maven 依赖已经具备

服务端已引入四个 Kona 组件：

- `ssl-server/ssl-server/pom.xml:35` - `kona-provider`
- `ssl-server/ssl-server/pom.xml:41` - `kona-crypto`
- `ssl-server/ssl-server/pom.xml:47` - `kona-pkix`
- `ssl-server/ssl-server/pom.xml:53` - `kona-ssl`

客户端也已引入同一组依赖：

- `ssl-server/ssl-client/pom.xml:34` - `kona-provider`
- `ssl-server/ssl-client/pom.xml:40` - `kona-crypto`
- `ssl-server/ssl-client/pom.xml:46` - `kona-pkix`
- `ssl-server/ssl-client/pom.xml:52` - `kona-ssl`

早期 `demo-server`、`demo-client` 也有相同依赖，可作为参考项目，但建议以后以 `ssl-server/ssl-server` 和 `ssl-server/ssl-client` 为准。

### 2.2 服务端没有强依赖腾讯 JDK 内置 Provider

服务端自定义 Tomcat SSL 实现中已经显式使用 Kona：

- `src/main/java/cn/byzk/example/sslserver/config/TomcatServer.java:85` - 注册 `KonaProvider`
- `src/main/java/cn/byzk/example/sslserver/config/TomcatServer.java:168` - `KeyStore.getInstance(storeType, "Kona")`
- `src/main/java/cn/byzk/example/sslserver/config/TomcatServer.java:250` - `KeyManagerFactory.getInstance("NewSunX509", "Kona")`
- `src/main/java/cn/byzk/example/sslserver/config/TomcatServer.java:324` - `SSLContext.getInstance(protocol, "Kona")`

这说明服务端核心路径是通过 Kona Provider 获取 TLCP/国密能力，而不是使用 OpenJDK 默认 JSSE。

### 2.3 客户端也已显式注册 Kona Provider

客户端 `RestTemplateHttpsConfig` 中已经显式注册四类 Kona Provider：

- `src/main/java/cn/byzk/example/sslclient/http/RestTemplateHttpsConfig.java:463` - `KonaProvider`
- `src/main/java/cn/byzk/example/sslclient/http/RestTemplateHttpsConfig.java:464` - `KonaCryptoProvider`
- `src/main/java/cn/byzk/example/sslclient/http/RestTemplateHttpsConfig.java:465` - `KonaPKIXProvider`
- `src/main/java/cn/byzk/example/sslclient/http/RestTemplateHttpsConfig.java:466` - `KonaSSLProvider`

客户端国密 SSL 初始化路径：

- `RestTemplateHttpsConfig.java:471` - `KeyManagerFactory.getInstance("NewSunX509", "KonaSSL")`
- `RestTemplateHttpsConfig.java:472` - `KeyStore.getInstance("PKCS12", "KonaPKIX")`
- `RestTemplateHttpsConfig.java:481` - `TrustManagerFactory.getInstance("PKIX", "KonaSSL")`
- `RestTemplateHttpsConfig.java:485` - `SSLContext.getInstance("TLCPv1.1", "KonaSSL")`

## 三、推荐替换方案

### 3.1 运行时 JDK

将运行时 JDK 切换为标准 OpenJDK 17 发行版，例如：

- Eclipse Temurin 17
- OpenJDK 17
- Amazon Corretto 17
- Alibaba Dragonwell 17

不建议使用低于 17 的 JDK。当前项目的 `pom.xml` 已配置 Java 17，Spring Boot 3.x 也要求 Java 17 以上。

### 3.2 保留 Kona Maven 依赖

保留以下依赖，版本先维持当前项目已验证的 `1.0.13`：

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

### 3.3 统一 Provider 注册

建议新增一个集中注册类，服务端和客户端都复用，避免多个类重复插入 Provider：

```java
public final class KonaProviderRegistrar {

    private KonaProviderRegistrar() {
    }

    public static synchronized void register() {
        if (Security.getProvider("Kona") == null) {
            Security.insertProviderAt(new KonaProvider(), 1);
        }
        if (Security.getProvider("KonaCrypto") == null) {
            Security.insertProviderAt(new KonaCryptoProvider(), 2);
        }
        if (Security.getProvider("KonaPKIX") == null) {
            Security.insertProviderAt(new KonaPKIXProvider(), 3);
        }
        if (Security.getProvider("KonaSSL") == null) {
            Security.insertProviderAt(new KonaSSLProvider(), 4);
        }
    }
}
```

服务端启动前调用：

```java
static {
    KonaProviderRegistrar.register();
}
```

客户端创建国密 `SSLContext` 前调用：

```java
KonaProviderRegistrar.register();
```

### 3.4 服务端保持自定义 Tomcat SSL 实现

OpenJDK 默认 `SSLContext` 不支持 TLCP，所以服务端不能只依赖 Spring Boot 的标准 `server.ssl.*` 配置。必须保留当前自定义实现：

- `KonaSSLHostConfig`：启用 `TLCPv1.1`、`TLSv1.3`
- `KonaSSLUtil`：返回 Kona 支持的协议和套件
- `KonaSSLContext`：通过 Kona 创建 `SSLContext`

推荐后续优化：

1. 将 `TomcatServer.java` 中硬编码的 `"Kona"`、`"TLCPv1.1"`、国密套件名称抽成常量。
2. `GmSSLConfig.java` 已读取 `server.gm-ssl.provider`、`key-store-provider`、`trust-store-provider`，但 `TomcatServer.java` 目前仍硬编码 Provider，建议让配置真正生效。
3. 删除生产环境默认开启的 `java.security.debug`、`com.tencent.kona.ssl.debug`，仅通过配置开关启用。

### 3.5 客户端保持 KonaSSL 创建 SSLContext

客户端国密场景必须继续使用：

```java
SSLContext context = SSLContext.getInstance("TLCPv1.1", "KonaSSL");
```

并且 KeyStore 使用：

```java
KeyStore.getInstance("PKCS12", "KonaPKIX");
```

不建议退回：

```java
SSLContext.getInstance("TLS");
```

因为 OpenJDK 默认 TLS Provider 不支持 TLCP 和 TLCP 国密套件。

## 四、迁移步骤

### 4.1 替换运行环境

确认当前运行环境：

```bash
java -version
mvn -version
```

期望示例：

```text
openjdk version "17.x"
Apache Maven 3.6+
```

### 4.2 编译验证

服务端：

```bash
cd ssl-server/ssl-server
mvn -Dmaven.repo.local=/tmp/m2 -DskipTests compile
```

客户端：

```bash
cd ssl-server/ssl-client
mvn -Dmaven.repo.local=/tmp/m2 -DskipTests compile
```

本次已在当前机器 OpenJDK 17.0.2 下验证：

- `ssl-server/ssl-server`：编译通过
- `ssl-server/ssl-client`：编译通过

### 4.3 启动验证

启动服务端：

```bash
cd ssl-server/ssl-server
mvn spring-boot:run
```

启动客户端：

```bash
cd ssl-server/ssl-client
mvn spring-boot:run
```

访问客户端测试接口：

```bash
curl http://127.0.0.1:7778/test/cli/t1
```

服务端成功握手时，应能访问 `https://127.0.0.1:8888` 对应接口。

### 4.4 Provider 检查

运行时日志中应能确认以下 Provider 已加载：

- `Kona`
- `KonaCrypto`
- `KonaPKIX`
- `KonaSSL`

如出现以下异常，优先检查 Provider 注册顺序和 Maven 依赖是否进入运行时 classpath：

- `NoSuchProviderException: KonaSSL`
- `NoSuchAlgorithmException: TLCPv1.1`
- `KeyStoreException: PKCS12 not found for provider KonaPKIX`
- `SSLHandshakeException`

## 五、主要风险和处理建议

### 5.1 OpenJDK 默认不支持 TLCP

OpenJDK 标准 JSSE 不提供 TLCP 协议和 TLCP 国密套件。替换腾讯 JDK 后，所有国密 SSL 路径都必须显式走 Kona Provider。

### 5.2 Provider 名称不统一

当前服务端使用 `"Kona"`，客户端使用 `"KonaPKIX"` 和 `"KonaSSL"`。建议统一封装 Provider 注册和常量，避免后续维护时混用。

### 5.3 证书路径存在绝对路径

客户端配置中存在绝对路径：

- `ssl-server/ssl-client/src/main/resources/application.yml:26`
- `ssl-server/ssl-client/src/main/resources/application.yml:28`

建议改成部署目录相对路径或环境变量配置，避免 OpenJDK 容器化部署后路径失效。

### 5.4 国密套件命名存在差异

服务端 Tomcat 配置使用：

- `TLCP_ECC_SM4_CBC_SM3`
- `TLCP_ECDHE_SM4_CBC_SM3`

客户端注释/配置中也出现：

- `ECC-SM2-SM4-CBC-SM3`
- `ECC-SM2-WITH-SM4-SM3`
- `ECDHE-SM2-WITH-SM4-SM3`

迁移时应以实际 `SSLContext.getSupportedSSLParameters().getCipherSuites()` 输出为准，统一服务端和客户端可协商套件。

### 5.5 早期 demo-server Provider 注册不完整

`demo-server/src/main/java/com/xcf/demo/ssl/server/config/KonaSSLCustomizer.java` 只显式注册了 `KonaSSLProvider`，但代码里使用了 `KonaPKIX`。如果继续维护早期 `demo-server`，建议同步为四 Provider 集中注册方式。

## 六、推荐验收标准

1. OpenJDK 17 环境下服务端、客户端均能编译通过。
2. 服务端启动日志中能看到国密 SSL 端口 `8888` 启动成功。
3. 客户端调用 `http://127.0.0.1:7778/test/cli/t1` 能完成 TLCP 双向认证请求。
4. 抓包或 SSL 调试日志可确认协商协议为 `TLCPv1.1`，套件为国密套件。
5. 移除腾讯 JDK 后，不再出现 `NoSuchProviderException`、`NoSuchAlgorithmException`、`SSLHandshakeException`。

## 七、最终判断

可以采用 OpenJDK 17 + `com.tencent.kona:*:1.0.13` Maven 依赖实现当前 demo 的国密 SSL/TLCP，不需要腾讯 JDK 作为运行时前提。

当前项目缺少的不是依赖，而是专项迁移说明和 Provider 注册规范化。建议优先补齐集中 Provider 注册、配置化 Provider 名称、相对证书路径，再做完整握手验收。
