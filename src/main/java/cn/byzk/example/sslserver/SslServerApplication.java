package cn.byzk.example.sslserver;

import cn.byzk.example.sslserver.config.KonaProviderRegistrar;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SslServerApplication {

    public static void main(String[] args) {
        KonaProviderRegistrar.register();
        SpringApplication.run(SslServerApplication.class, args);
    }

}
