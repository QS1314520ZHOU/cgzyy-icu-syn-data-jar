package com.digixmed.icu.viform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>组件扫描基础包为 {@code com.digixmed.icu.viform}，
 * 其下的 entity / repository / service / runner / controller 均会被自动扫描。</p>
 */
@SpringBootApplication
public class SynDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynDataApplication.class, args);
    }
}
