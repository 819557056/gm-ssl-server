package cn.byzk.example.sslserver;

import cn.byzk.example.sslserver.config.KonaProviderRegistrar;
import org.springframework.boot.SpringApplication;
import cn.byzk.example.sslserver.config.ServerSslModeProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ServerSslModeProperties.class)
public class SslServerApplication {

    public static void main(String[] args) {
        KonaProviderRegistrar.register();
        SpringApplication.run(SslServerApplication.class, args);
    }

}
