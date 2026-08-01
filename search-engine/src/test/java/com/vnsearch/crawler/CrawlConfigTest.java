package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kiem thu Builder bat bien cua cau hinh crawl. */
class CrawlConfigTest {

    @Test
    void defaultsAreSane() {
        CrawlConfig config = CrawlConfig.builder().build();
        assertEquals(3, config.maxDepth());
        assertEquals(100, config.maxPages());
        assertEquals(4, config.threadCount());
        assertTrue(config.allowedDomains().isEmpty());
    }

    @Test
    void fluentSettersApplyValues() {
        CrawlConfig config = CrawlConfig.builder()
                .maxDepth(5)
                .maxPages(5000)
                .threadCount(12)
                .allowedDomains(Set.of("vnexpress.net"))
                .maxDurationMinutes(90)
                .urlStoragePath("data/seen-urls.txt")
                .build();

        assertEquals(5, config.maxDepth());
        assertEquals(5000, config.maxPages());
        assertEquals(12, config.threadCount());
        assertEquals(Set.of("vnexpress.net"), config.allowedDomains());
        assertEquals(90, config.maxDurationMinutes());
        assertEquals("data/seen-urls.txt", config.urlStoragePath());
    }

    /** Mac dinh KHONG luu ben URL — chuoi rong cung phai quy ve null, khong tao file rong. */
    @Test
    void urlStorageIsOffByDefaultAndBlankPathMeansOff() {
        assertNull(CrawlConfig.builder().build().urlStoragePath());
        assertNull(CrawlConfig.builder().urlStoragePath("   ").build().urlStoragePath());
        assertNull(CrawlConfig.builder().urlStoragePath(null).build().urlStoragePath());
    }

    @Test
    void allowedDomainsIsDefensiveCopy() {
        Set<String> mutable = new HashSet<>(Set.of("a.vn"));
        CrawlConfig config = CrawlConfig.builder().allowedDomains(mutable).build();

        mutable.add("b.vn"); // sua tap GOC sau khi build
        assertEquals(Set.of("a.vn"), config.allowedDomains(),
                "Cau hinh phai bat bien truoc moi thay doi tu ben ngoai");
        assertNotSame(mutable, config.allowedDomains());
    }

    @Test
    void allowedDomainsIsUnmodifiable() {
        CrawlConfig config = CrawlConfig.builder().allowedDomains(Set.of("a.vn")).build();
        assertThrows(UnsupportedOperationException.class, () -> config.allowedDomains().add("b.vn"));
    }

    @Test
    void nullDomainsBecomesEmptySet() {
        assertTrue(CrawlConfig.builder().allowedDomains(null).build().allowedDomains().isEmpty());
    }

    @Test
    void validationRejectsNonPositiveMaxPages() {
        assertThrows(IllegalArgumentException.class,
                () -> CrawlConfig.builder().maxPages(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> CrawlConfig.builder().maxPages(-1).build());
    }

    @Test
    void validationRejectsNegativeDepth() {
        assertThrows(IllegalArgumentException.class,
                () -> CrawlConfig.builder().maxDepth(-1).build());
    }

    @Test
    void validationRejectsNonPositiveThreadCount() {
        // newFixedThreadPool(0) nem ngoai le kho hieu o giua phien crawl;
        // bat o day thi loi hien ngay tai cho sai.
        assertThrows(IllegalArgumentException.class,
                () -> CrawlConfig.builder().threadCount(0).build());
    }

    @Test
    void validationRejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> CrawlConfig.builder().maxDurationMinutes(0).build());
    }
}
