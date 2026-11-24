package com.yuzhua.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "yuzhua.brand")
public class BrandProperties {

    private String fullName = "愈爪|宠物医院预约与在线问诊平台";
}