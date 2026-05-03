package com.xiyiyun.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.xiyiyun.shop.persistence.mapper")
public class XiyiyunApplication {
    public static void main(String[] args) {
        SpringApplication.run(XiyiyunApplication.class, args);
    }
}
