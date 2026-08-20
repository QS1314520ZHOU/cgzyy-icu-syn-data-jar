package com.digixmed.icu.viform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ICU 数据同步接口")
                        .description("ICU 数据处理任务：从 SmartCare MongoDB 读取在院患者数据并执行同步")
                        .version("1.0.0"));
    }
}
