package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.bus.PageEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modular Service 3 — bien bo dem roi rac thanh thang do Prometheus.
 */
class CrawlAnalyticsServiceTest {

    private MeterRegistry registry;
    private CrawlAnalyticsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new CrawlAnalyticsService(registry);
    }

    private static PageEvent page(String url, String host, String language, int depth) {
        return new PageEvent(url, host, depth, "Tieu de", "Than bai", language,
                "<html><body>noi dung</body></html>", "hash", Instant.EPOCH, "job-1");
    }

    @Test
    void countsPagesAndExposesThemAsGauge() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        service.onPage(page("https://a.com/2", "a.com", "vi", 1));

        assertEquals(2, service.getPagesTotal());
        assertEquals(2.0,
                registry.get("vnsearch.crawl.pages.total").gauge().value());
    }

    /**
     * Ngon ngu la chieu duy nhat duoc lam NHAN: luc luong nho (vi/en/und) va
     * biet truoc. Xem Javadoc lop ve viec vi sao host thi khong.
     */
    @Test
    void languageBecomesAPrometheusLabel() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        service.onPage(page("https://a.com/2", "a.com", "vi", 0));
        service.onPage(page("https://b.com/1", "b.com", "en", 0));

        assertEquals(2.0, registry.get("vnsearch.crawl.pages.by.language.total")
                .tag("language", "vi").counter().count());
        assertEquals(1.0, registry.get("vnsearch.crawl.pages.by.language.total")
                .tag("language", "en").counter().count());
        assertEquals(List.of("en", "vi"), service.languagesSeen());
    }

    /** Ngon ngu rong duoc chuan hoa thanh "und", khong sinh mot nhan rong. */
    @Test
    void blankLanguageBecomesUnd() {
        service.onPage(page("https://a.com/1", "a.com", "", 0));
        service.onPage(page("https://a.com/2", "a.com", null, 0));

        assertEquals(List.of("und"), service.languagesSeen());
        assertEquals(2.0, registry.get("vnsearch.crawl.pages.by.language.total")
                .tag("language", "und").counter().count());
    }

    /**
     * Host KHONG duoc xuat hien lam nhan Prometheus — day la bai test chan
     * viec ai do "tien tay" them tag("host", ...) sau nay. Mot phien crawl
     * cham 30.000 host se tao 30.000 chuoi thoi gian tu mot thang do.
     */
    @Test
    void hostIsNeverUsedAsAPrometheusLabel() {
        service.onPage(page("https://vnexpress.net/1", "vnexpress.net", "vi", 0));
        service.onPage(page("https://tuoitre.vn/1", "tuoitre.vn", "vi", 0));

        boolean anyHostTag = registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> "host".equals(tag.getKey()));

        assertTrue(!anyHostTag, "Khong thang do nao duoc gan nhan host");
        // Nhung so lieu theo host van co, o bang trong bo nho
        assertEquals(2, service.getDistinctHostCount());
    }

    @Test
    void tracksDistinctHostsAndTopHosts() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        service.onPage(page("https://a.com/2", "a.com", "vi", 0));
        service.onPage(page("https://a.com/3", "a.com", "vi", 0));
        service.onPage(page("https://b.com/1", "b.com", "vi", 0));

        assertEquals(2, service.getDistinctHostCount());
        Map<String, Long> top = service.topHosts(10);
        assertEquals(List.of("a.com", "b.com"), List.copyOf(top.keySet()));
        assertEquals(3L, top.get("a.com"));
        assertEquals(1L, top.get("b.com"));
    }

    @Test
    void topHostsRespectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            service.onPage(page("https://h" + i + ".com/1", "h" + i + ".com", "vi", 0));
        }
        assertEquals(2, service.topHosts(2).size());
        assertEquals(0, service.topHosts(0).size());
        assertEquals(0, service.topHosts(-1).size());
    }

    @Test
    void tracksMaximumDepthSeen() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        service.onPage(page("https://a.com/2", "a.com", "vi", 4));
        service.onPage(page("https://a.com/3", "a.com", "vi", 2));

        assertEquals(4, service.getMaxDepthSeen(), "Phai giu gia tri LON NHAT, khong phai cuoi cung");
        assertEquals(4.0, registry.get("vnsearch.crawl.depth.max").gauge().value());
    }

    @Test
    void recordsPageSizeDistribution() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        var summary = registry.get("vnsearch.crawl.page.size.bytes").summary();
        assertEquals(1, summary.count());
        assertTrue(summary.totalAmount() > 0);
    }

    @Test
    void recordsBodyTextLength() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 0));
        assertEquals(1, registry.get("vnsearch.crawl.page.text.chars").summary().count());
    }

    /**
     * Hai Modular Service gap nhau QUA BUS: Image Download phat ImageFound,
     * Analytics dang ky nhan. Tat Image Download di thi Analytics van chay.
     */
    @Test
    void aggregatesImagesFromTheImageService() {
        service.onImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/x.jpg", "co alt", 10, 10));
        service.onImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/y.jpg", "", 10, 10));

        assertEquals(2.0, registry.get("vnsearch.crawl.images.total").counter().count());
        assertEquals(1.0,
                registry.get("vnsearch.crawl.images.missing.alt.total").counter().count());
    }

    /**
     * So anh cua mot trang duoc chot khi trang do di qua onPage. Thu tu that
     * o che do Kafka la anh toi truoc (Image Download nhanh hon), nen bai test
     * dung dung thu tu do.
     */
    @Test
    void recordsImagesPerPageWhenThePageArrives() {
        service.onImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/x.jpg", "a", 1, 1));
        service.onImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/y.jpg", "b", 1, 1));

        service.onPage(page("https://a.com/1", "a.com", "vi", 0));

        var summary = registry.get("vnsearch.crawl.page.images").summary();
        assertEquals(1, summary.count());
        assertEquals(2.0, summary.totalAmount());
    }

    @Test
    void snapshotContainsTheHeadlineNumbers() {
        service.onPage(page("https://a.com/1", "a.com", "vi", 3));
        Map<String, Object> snapshot = service.snapshot();

        assertEquals(1L, snapshot.get("pagesTotal"));
        assertEquals(1, snapshot.get("distinctHosts"));
        assertEquals(3L, snapshot.get("maxDepth"));
        assertEquals(0L, snapshot.get("hostsDropped"));
        assertNotNull(snapshot.get("topHosts"));
    }

    @Test
    void blankHostIsNotTracked() {
        service.onPage(new PageEvent("https://a.com/1", "a.com", 0, "t", "b", "vi",
                "<html></html>", "h", Instant.EPOCH, "job"));
        assertEquals(1, service.getDistinctHostCount());
    }

    @Test
    void handlerNameIsReadableInLogs() {
        assertEquals("Analytics Service", service.handlerName());
    }

    @Test
    void constructorRequiresARegistry() {
        assertThrows(IllegalArgumentException.class, () -> new CrawlAnalyticsService(null));
    }
}
