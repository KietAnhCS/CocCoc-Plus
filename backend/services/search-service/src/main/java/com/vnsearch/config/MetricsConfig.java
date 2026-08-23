package com.vnsearch.config;

import com.vnsearch.service.SearchEngineFacade;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dang ky cac thang do NGHIEP VU cho Micrometer, phoi ra qua
 * {@code /actuator/prometheus}.
 *
 * <p><b>Vi sao can lop nay.</b> Actuator co san rat nhieu thang do ky thuat —
 * bo nho JVM, so luong luong, do tre HTTP. Nhung khong cai nao tra loi duoc cau
 * hoi ma nguoi VAN HANH mot may tim kiem thuc su can hoi:
 *
 * <pre>
 *   Actuator tu co                 Chi lop nay biet
 *   ─────────────────────────      ─────────────────────────
 *   Heap dang dung bao nhieu?      Chi muc dang co bao nhieu tai lieu?
 *   Request/giay la bao nhieu?     Ty le trung cache la bao nhieu?
 *   GC chay bao lau?               Chi muc co RONG khong?
 * </pre>
 *
 * <p>Cau hoi ben phai moi la cau hoi phan biet duoc "he thong con song" voi
 * "he thong con phuc vu duoc". Mot ban sao co heap khoe manh nhung chi muc rong
 * thi tra ve 0 ket qua cho MOI truy van — va moi thang do ky thuat deu xanh.
 *
 * <p><b>Vi sao dung {@link Gauge} chu khong phai mot con so ghi san.</b> Gauge
 * nhan mot <i>ham lay gia tri</i>, va Micrometer goi lai ham do moi lan bi hoi.
 * Neu day gia tri vao mot bien luc khoi dong thi so lieu se dong bang o thoi
 * diem do — sau mot lan reindex, bang dieu khien van bao con so cu.
 *
 * <p><b>Vi sao {@link MeterBinder} chu khong phai {@code @PostConstruct} tiem
 * {@code MeterRegistry}.</b> MeterBinder la diem mo rong chinh thuc: Spring goi
 * no SAU khi registry da san sang, nen khong co chuyen thu tu khoi tao bean lam
 * mat thang do mot cach im lang.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder vnsearchMetrics(SearchEngineFacade facade) {
        return registry -> {
            Gauge.builder("vnsearch.index.documents", facade,
                            SearchEngineFacade::getIndexedDocumentCount)
                    .description("So tai lieu dang co trong chi muc")
                    .register(registry);

            Gauge.builder("vnsearch.index.terms", facade,
                            SearchEngineFacade::getTermCount)
                    .description("So term phan biet trong chi muc")
                    .register(registry);

            Gauge.builder("vnsearch.cache.hit.rate", facade,
                            SearchEngineFacade::getCacheHitRate)
                    .description("Ty le trung cache tim kiem, tu 0 den 1")
                    .register(registry);
        };
    }
}
