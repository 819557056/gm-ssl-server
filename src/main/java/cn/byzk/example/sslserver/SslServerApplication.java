package cn.byzk.example.sslserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SslServerApplication {

    public static void main(String[] args) {
        System.setProperty("com.tencent.kona.ssl.debug", "all");
        SpringApplication.run(SslServerApplication.class, args);
    }

}
