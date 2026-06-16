package cn.byzk.example.sslserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "server")
public class ServerSslModeProperties {

    /**
     * SSL 模式：RSA 使用 Spring Boot 原生 server.ssl.*，GM 使用自定义 Kona/TLCP Connector。
     */
    private SslMode sslMode = SslMode.RSA;
}
