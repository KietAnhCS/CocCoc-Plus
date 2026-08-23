package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * settings-service — tuy chon nguoi dung, dong bo giua cac thiet bi.
 *
 * <p>Service nho nhat he thong: mot bang, mot cot JSONB, nam endpoint. Giu no
 * nho la co y — xem chu thich trong pom.xml ve viec vi sao KHONG nhet tuy chon
 * vao bang tai khoan cua auth-service.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",
        "com.vnsearch.controller",
        "com.vnsearch.settings"
})
public class SettingsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettingsServiceApplication.class, args);
    }

    @Bean
    public PublicEndpoints settingsPublicEndpoints() {
        return PublicEndpoints.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
