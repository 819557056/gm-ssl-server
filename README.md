# SSL Server - 基于国密算法的Spring Boot服务器

## 项目简介

这是一个基于Spring Boot 3.5.6和腾讯Kona加密套件（Tencent Kona SM Suite）构建的国密SSL/TLS服务器示例项目。该项目演示了如何在Spring Boot应用中集成国密算法，支持TLCP协议和双向SSL认证。

## 技术栈

- **Java版本**: JDK 17
- **Spring Boot**: 3.5.6
- **加密库**: Tencent Kona SM Suite 1.0.13
  - kona-provider
  - kona-crypto
  - kona-pkix
  - kona-ssl
- **构建工具**: Maven
- **Web容器**: Embedded Tomcat

## 主要特性

### 1. 国密算法支持
- ✅ 支持国密SM2、SM3、SM4算法
- ✅ 支持TLCP（Transport Layer Cryptography Protocol）v1.1协议
- ✅ 支持TLS v1.3协议
- ✅ 使用国密加密套件：
  - `TLCP_ECC_SM4_CBC_SM3`
  - `TLCP_ECDHE_SM4_CBC_SM3`

### 2. 双向SSL认证
- ✅ 服务端证书配置
- ✅ 客户端证书验证（可配置三种模式）
  - `required`: 强制要求客户端提供证书
  - `optional`: 客户端可选择提供证书
  - `none`: 不验证客户端证书

### 3. 双端口配置
- HTTP端口: 7777（会自动重定向到HTTPS）
- HTTPS端口: 8888（支持国密TLCP协议）

## 项目结构

```
ssl-server/
├── src/
│   └── main/
│       ├── java/
│       │   └── cn/byzk/example/sslserver/
│       │       ├── config/
│       │       │   ├── GmSSLConfig.java          # 国密SSL配置类
│       │       │   └── TomcatServer.java         # Tomcat服务器配置
│       │       ├── controller/
│       │       │   └── TestServController.java   # 测试控制器
│       │       ├── model/
│       │       │   └── UserDto.java              # 数据模型
│       │       └── SslServerApplication.java     # 主应用入口
│       └── resources/
│           └── application.yml                    # 应用配置文件
├── ssl/                                           # SSL证书目录
│   ├── keystore.p12                              # 服务器密钥库（包含签名和加密证书）
│   ├── truststore.p12                            # 信任库（CA证书）
│   └── read.me                                   # 证书生成说明
└── pom.xml                                        # Maven配置文件
```

## 快速开始

### 前置要求

1. **安装JDK 17或更高版本**
   ```bash
   java -version
   ```

2. **安装Maven**
   ```bash
   mvn -version
   ```

3. **准备SSL证书**（可选，项目已包含测试证书）
   - 如需生成新证书，请参考 `ssl/read.me` 中的说明

### 安装步骤

1. **克隆项目**
   ```bash
   git clone <repository-url>
   cd ssl-server
   ```

2. **配置证书**（如使用自己的证书）
   
   将证书文件放入 `ssl/` 目录：
   - `keystore.p12`: 服务器密钥库（包含签名证书和加密证书）
   - `truststore.p12`: 信任库（CA证书）

3. **修改配置文件**（可选）
   
   编辑 `src/main/resources/application.yml`：
   ```yaml
   server:
     port: 7777
     gm-ssl:
       ssl-gm-port: 8888              # HTTPS端口
       enabled: true                   # 启用国密SSL
       provider: Kona                  # 使用Kona提供者
       key-store: ssl/keystore.p12     # 密钥库路径
       key-store-password: 123456      # 密钥库密码
       trust-store: ssl/truststore.p12 # 信任库路径
       trust-store-password: 123456    # 信任库密码
       protocol: TLCP                  # 使用TLCP协议
       client-auth: required           # 客户端认证模式
   ```

4. **编译项目**
   ```bash
   mvn clean package
   ```

5. **运行应用**
   ```bash
   mvn spring-boot:run
   ```
   
   或者直接运行编译后的JAR包：
   ```bash
   java -jar target/ssl-server-0.0.1-SNAPSHOT.jar
   ```

### 验证服务

应用启动后，您可以通过以下方式验证：

1. **查看日志**
   ```
   启用了Kona SSL调试信息，可以在控制台看到详细的SSL握手过程
   ```

2. **访问测试接口**
   ```bash
   # 使用支持国密的客户端访问
   curl --cert client.crt --key client.key --cacert ca.crt \
        https://localhost:8888/tomcat
   ```
   
   预期响应：
   ```
   This is a testing server on Tencent Kona SM Suite
   ```

## 配置说明

### SSL配置参数

| 参数 | 说明 | 默认值 |
|-----|------|--------|
| `server.gm-ssl.enabled` | 是否启用国密SSL | true |
| `server.gm-ssl.ssl-gm-port` | HTTPS监听端口 | 8888 |
| `server.gm-ssl.provider` | 安全提供者 | Kona |
| `server.gm-ssl.protocol` | SSL协议 | TLCP |
| `server.gm-ssl.client-auth` | 客户端认证模式 | required |
| `server.gm-ssl.key-store-type` | 密钥库类型 | PKCS12 |
| `server.gm-ssl.trust-store-type` | 信任库类型 | PKCS12 |

### 客户端认证模式

- **required**: 强制要求客户端提供证书，如果客户端没有证书或证书无效，连接会被拒绝
- **optional**: 客户端可以选择提供证书，如果提供了会进行验证，不提供也允许连接
- **none**: 不验证客户端证书（默认行为）

## SSL证书生成

如果需要生成新的国密证书，请使用Tongsuo（铜锁）工具，参考 `ssl/read.me` 中的步骤：

### 1. 合并证书链
```bash
cat ca_server_sign.crt ca.crt > combined_server_cert.pem
cat ca_server_enc.crt ca.crt > combined_enc_server_cert.pem
```

### 2. 生成PKCS12格式的密钥库
```bash
# 生成签名证书keystore
openssl pkcs12 -export -out keystore.p12 \
  -in combined_server_cert.pem \
  -inkey ca_server_sign.key \
  -name server_sign \
  -password pass:123456

# 生成加密证书keystore
openssl pkcs12 -export -out keystore_enc.p12 \
  -in combined_enc_server_cert.pem \
  -inkey ca_server_enc.key \
  -name server_enc \
  -password pass:123456
```

### 3. 合并密钥库
```bash
keytool -importkeystore \
  -srckeystore keystore_enc.p12 \
  -srcstoretype PKCS12 \
  -srcstorepass 123456 \
  -destkeystore keystore.p12 \
  -deststoretype PKCS12 \
  -deststorepass 123456
```

### 4. 生成信任库
```bash
keytool -importcert \
  -file ca.crt \
  -keystore truststore.p12 \
  -storetype PKCS12 \
  -alias ca
```

### 5. 验证证书
```bash
# 验证密钥库
keytool -list -v -keystore keystore.p12 \
  -storetype PKCS12 -storepass 123456

# 验证信任库
keytool -list -v -keystore truststore.p12 \
  -storetype PKCS12 -storepass 123456
```

## 开发调试

### 启用SSL调试日志

在 `SslServerApplication.java` 中已经启用了Kona SSL调试：
```java
System.setProperty("com.tencent.kona.ssl.debug", "all");
```

这将输出详细的SSL握手和加密过程信息，有助于排查问题。

### 常见问题

1. **证书加载失败**
   - 检查证书路径是否正确
   - 确认证书密码是否匹配
   - 验证证书格式是否为PKCS12

2. **客户端连接被拒绝**
   - 确认客户端证书是否由信任的CA签发
   - 检查`client-auth`配置是否符合需求
   - 查看服务器日志中的SSL握手详情

3. **不支持的加密套件**
   - 确认客户端支持国密算法
   - 检查Kona Provider是否正确加载

## API接口

### GET /tomcat
测试接口，验证服务器是否正常运行。

**请求示例：**
```bash
curl --cert client.crt --key client.key --cacert ca.crt \
     https://localhost:8888/tomcat
```

**响应示例：**
```
This is a testing server on Tencent Kona SM Suite
```

## 性能优化

- 使用PKCS12格式统一管理证书
- 启用SSL会话缓存
- 合理配置线程池大小
- 根据实际需求选择加密套件

## 安全建议

1. **生产环境使用强密码**: 修改默认的 `123456` 密码
2. **定期更新证书**: 避免证书过期导致的服务中断
3. **启用客户端认证**: 在敏感环境中使用 `client-auth: required`
4. **限制访问端口**: 使用防火墙限制SSL端口的访问
5. **监控SSL日志**: 关注异常的SSL握手失败

## 依赖说明

### 核心依赖

```xml
<!-- Tencent Kona 国密加密套件 -->
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-provider</artifactId>
    <version>1.0.13</version>
</dependency>
<dependency>
    <groupId>com.tencent.kona</groupId>
    <artifactId>kona-ssl</artifactId>
    <version>1.0.13</version>
</dependency>

<!-- Spring Boot Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

## 许可证

本项目代码部分来自 Tencent Kona SM Suite 示例，遵循 GNU General Public License v2.0 许可证。

## 参考资料

- [Tencent Kona 官方文档](https://github.com/Tencent/TencentKona-17)
- [国密算法标准](http://www.gmbz.org.cn/)
- [TLCP协议规范](http://www.gmbz.org.cn/main/viewfile/20180108023812835219.html)
- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)

## 贡献

欢迎提交Issue和Pull Request！

## 联系方式

如有问题，请通过以下方式联系：
- 提交 GitHub Issue
- 发送邮件至项目维护者

---

**注意**: 本项目仅供学习和测试使用，生产环境部署请根据实际需求进行安全加固。

