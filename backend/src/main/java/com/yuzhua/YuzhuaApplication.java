package com.yuzhua;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
@ConfigurationPropertiesScan("com.yuzhua.config")
@MapperScan("com.yuzhua.mapper")
public class YuzhuaApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuzhuaApplication.class, args);
    }
}
