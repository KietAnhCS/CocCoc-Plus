package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * history-service — lich su duyet web va lich su tim kiem.
 *
 * <p><b>KHONG co endpoint cong khai nao</b> ngoai tai lieu API. Moi thu o day
 * la du lieu ca nhan gan voi mot tai khoan; mot endpoint cong khai trong
 * service nay khong co nghia gi khac ngoai mot lo hong.
 */
@SpringBootApplication(scanBasePackages = {
        "com.vnsearch.config",      // platform: CORS, loi, rate-limit, resource server
        "com.vnsearch.controller",
        "com.vnsearch.history"
})
@EnableMongoRepositories(basePackages = "com.vnsearch.history")
public class HistoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryServiceApplication.class, args);
    }

    @Bean
    public PublicEndpoints historyPublicEndpoints() {
        return PublicEndpoints.of("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
