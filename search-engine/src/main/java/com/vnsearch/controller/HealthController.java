package com.vnsearch.controller;

import com.vnsearch.service.SearchEngineFacade;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoint suc khoe <b>cong khai</b>, danh cho healthcheck cua Docker/Kubernetes.
 *
 * <p><b>Vi sao phai co, tach khoi {@code /api/admin/stats}.</b> Truoc day
 * {@code docker-compose.yml} thăm dò suc khoe bang chinh endpoint quan tri. Khi
 * {@code /api/admin/**} duoc khoa lai bang API key, healthcheck se nhan 401,
 * container bi danh dau <i>unhealthy</i>, va {@code restart: unless-stopped}
 * khoi dong lai vo han — mot vong lap hong do chinh phep sua bao mat gay ra.
 *
 * <p><b>Chi phoi bay dung nhung gi can de biet "co phuc vu duoc khong".</b>
 * Khong co so lieu vac hanh chi tiet (kich thuoc chi muc, ty le trung cache) —
 * nhung thu do o {@code /api/admin/stats} va can xac thuc. Ranh gioi: mot
 * endpoint cong khai duoc phep noi <i>he thong con song</i>, khong duoc phep
 * noi <i>he thong dang chua nhung gi</i>.
 *
 * <p>Tra ve {@code 503} khi chi muc rong: khi do ung dung <b>dang chay nhung
 * khong phuc vu duoc</b> — va do chinh la thu healthcheck can phan biet. Tra
 * {@code 200} vo dieu kien se khien bo can bang tai gui luu luong toi mot ban
 * sao khong the tra ve ket qua nao.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final SearchEngineFacade facade;

    public HealthController(SearchEngineFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        int documents = facade.getIndexedDocumentCount();
        boolean ready = documents > 0;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "OUT_OF_SERVICE");
        body.put("indexedDocuments", documents);

        return ready
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(503).body(body);
    }
}
