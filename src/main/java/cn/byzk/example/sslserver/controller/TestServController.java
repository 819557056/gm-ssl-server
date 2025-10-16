package cn.byzk.example.sslserver.controller;

import cn.byzk.example.sslserver.model.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequestMapping("/test/serv/")
@RestController
public class TestServController {

    @PostMapping("t1")
    public String testServer(UserDto user) {

        user.setName("server");
        return "OK";
    }

    @GetMapping("t2")
    public String testServer2() {
        log.info("收到信息Get请求");

        return "返回Get请求";
    }
}
