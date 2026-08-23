package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * downloads-service — so tai xuong, dong bo giua cac thiet bi.
 *
 * <p>Khong co endpoint cong khai nao ngoai tai lieu API: moi thu o day la du
 * lieu ca nhan gan voi mot tai khoan.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",
        "com.vnsearch.controller",
        "com.vnsearch.downloads"
})
public class DownloadsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownloadsServiceApplication.class, args);
    }

    @Bean
    public PublicEndpoints downloadsPublicEndpoints() {
        return PublicEndpoints.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
