package com.xiyiyun.shop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;

/**
 * {@code @EnableScheduling} 为批次6 新增：项目原先没开启定时任务，
 * {@code ProductMonitorWorker} 是自己 new 的守护线程。改用 {@code @Scheduled} 后必须开启，
 * 否则注解会被静默忽略、监控完全不跑。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.xiyiyun.shop.persistence.mapper")
public class XiyiyunApplication {
    public static void main(String[] args) {
        SpringApplication.run(XiyiyunApplication.class, args);
    }
}
