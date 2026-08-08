package com.vnsearch.crawler;

import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.InProcessCrawlEventBus;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.crawler.bus.PageEvent;
import com.vnsearch.model.WebDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cach {@link CrawlerService} noi voi cum Modular Services — phan KHONG can
 * mang.
 *
 * <p>Cac bai test o day kiem tra dung ranh gioi moi: che do nao thi tu dang ky
 * service, va hai duong quay ve ({@code acceptDiscoveredUrl},
 * {@code acceptOutlinks}) co lam dung viec khong. Phan tai trang that thi
 * khong test o day — no can mang, va cac khoi cau thanh da co bai test rieng.
 */
class CrawlerServiceBusWiringTest {

    /**
     * Che do mac dinh phai giu nguyen hanh vi cu: mot lenh `new CrawlerService()`
     * la co day du ba Modular Service, khong phai cau hinh gi them.
     *
     * <p>Day la dieu kien de run-crawl.bat va MultiDomainCrawlRunner khong phai
     * sua mot dong nao.
     */
    @Test
    void defaultConstructorBuildsAnInProcessBus() {
        CrawlerService crawler = new CrawlerService();

        assertTrue(crawler.isInProcessMode());
        assertTrue(crawler.getEventBus() instanceof InProcessCrawlEventBus);
        assertNotNull(crawler.getJobId(), "Chay dong lenh cung phai co danh tinh hop le");
    }

    /**
     * Cac Modular Service chi duoc dang ky khi mot phien crawl bat dau — truoc
     * do chua co UrlFilter/UrlSeenFilter de dua cho chung.
     */
    @Test
    void modularServicesAreNotWiredBeforeTheFirstCrawl() {
        CrawlerService crawler = new CrawlerService();
        assertNull(crawler.getUrlExtractorService());
        assertNull(crawler.getImageDownloadService());
        assertNull(crawler.getAnalyticsService());
    }

    /**
     * Khi bus duoc tiem tu ngoai (che do Kafka), lop nay KHONG duoc tu dang ky
     * service cuc bo — lam vay thi moi trang bi xu ly hai lan va moi URL bi xep
     * hang hai luot.
     */
    @Test
    void injectedBusMeansNoLocalServices() {
        CrawlEventBus external = CrawlEventBus.noop();
        CrawlerService crawler = new CrawlerService(external);

        assertFalse(crawler.isInProcessMode());
        assertSame(external, crawler.getEventBus());
    }

    /** Truyen null nghia la "ve mac dinh", khong phai loi. */
    @Test
    void nullBusFallsBackToInProcess() {
        CrawlerService crawler = new CrawlerService(null);
        assertTrue(crawler.isInProcessMode());
        assertTrue(crawler.getEventBus() instanceof InProcessCrawlEventBus);
    }

    @Test
    void jobIdCanBeSetButBlankValuesAreIgnored() {
        CrawlerService crawler = new CrawlerService();
        String generated = crawler.getJobId();

        crawler.setJobId("job-abc");
        assertEquals("job-abc", crawler.getJobId());

        crawler.setJobId("");
        crawler.setJobId(null);
        assertEquals("job-abc", crawler.getJobId(), "Gia tri rong khong duoc xoa id dang co");
        assertFalse(generated.equals(crawler.getJobId()));
    }

    /**
     * Duong quay ve thu nhat: URL da qua loc di thang vao frontier, KHONG bi
     * loc lai.
     *
     * <p>Loc lai la mot loi tinh vi: markSeenIfNew se tra ve false cho chinh
     * URL vua duoc ghi nhan o UrlExtractorService, nen khong URL nao vao duoc
     * frontier va crawler dung ngay sau cac seed.
     */
    @Test
    void acceptDiscoveredUrlPutsTheUrlStraightIntoTheFrontier() {
        CrawlerService crawler = new CrawlerService();
        assertEquals(0, crawler.getQueueSize());

        boolean added = crawler.acceptDiscoveredUrl(new DiscoveredUrl(
                "https://a.com/con", "a.com", 1, "https://a.com", "job-1"));

        assertTrue(added);
        assertEquals(1, crawler.getQueueSize());
    }

    @Test
    void acceptDiscoveredUrlIgnoresNull() {
        CrawlerService crawler = new CrawlerService();
        assertFalse(crawler.acceptDiscoveredUrl(null));
        assertEquals(0, crawler.getQueueSize());
    }

    /** Cung mot URL hai lan chi vao frontier mot lan. */
    @Test
    void frontierRejectsDuplicateUrls() {
        CrawlerService crawler = new CrawlerService();
        DiscoveredUrl url = new DiscoveredUrl("https://a.com/con", "a.com", 1,
                "https://a.com", "job-1");

        assertTrue(crawler.acceptDiscoveredUrl(url));
        assertFalse(crawler.acceptDiscoveredUrl(url));
        assertEquals(1, crawler.getQueueSize());
    }

    /**
     * Duong quay ve thu hai: outlinks toi cho mot URL chua duoc luu thi bi bo
     * qua NHUNG phai duoc dem — neu con so nay bang tong so su kien thi dinh
     * tuyen da hong hoan toan.
     */
    @Test
    void outlinksForAnUnknownUrlAreCountedAsOrphans() {
        CrawlerService crawler = new CrawlerService();

        crawler.acceptOutlinks(new OutlinksExtracted("https://a.com/chua-luu", "a.com",
                List.of("https://a.com/1"), "job-1"));

        assertEquals(1, crawler.getOrphanOutlinksCount());
    }

    @Test
    void acceptOutlinksIgnoresNull() {
        CrawlerService crawler = new CrawlerService();
        crawler.acceptOutlinks(null);
        assertEquals(0, crawler.getOrphanOutlinksCount());
    }

    /**
     * Content Storage nhan duoc outlinks SAU khi tai lieu da luu — day la thu
     * khien PageRank van co du lieu du viec boc lien ket da roi khoi duong
     * chay cua crawler.
     */
    @Test
    void contentStorageAcceptsOutlinksAfterTheDocumentIsSaved() {
        ContentStorage storage = new ContentStorage();
        WebDocument doc = new WebDocument(0, "https://a.com/bai", "Tieu de", "mo ta",
                "than bai", new ArrayList<>(), Instant.EPOCH);
        assertTrue(storage.save(doc));

        assertTrue(storage.applyOutlinks("https://a.com/bai",
                List.of("https://a.com/1", "https://a.com/2")));
        assertEquals(2, doc.getOutlinks().size());
    }

    @Test
    void applyOutlinksReturnsFalseForUnknownOrNullInput() {
        ContentStorage storage = new ContentStorage();
        assertFalse(storage.applyOutlinks("https://a.com/khong-co", List.of("x")));
        assertFalse(storage.applyOutlinks(null, List.of("x")));
        assertFalse(storage.applyOutlinks("https://a.com/bai", null));
    }

    /** Danh sach duoc sao chep: sua ban goc khong duoc doi tai lieu da luu. */
    @Test
    void applyOutlinksCopiesTheList() {
        ContentStorage storage = new ContentStorage();
        WebDocument doc = new WebDocument(0, "https://a.com/bai", "t", "m", "b",
                new ArrayList<>(), Instant.EPOCH);
        storage.save(doc);

        List<String> source = new ArrayList<>(List.of("https://a.com/1"));
        storage.applyOutlinks("https://a.com/bai", source);
        source.add("https://a.com/2");

        assertEquals(1, doc.getOutlinks().size());
    }

    /**
     * Vong khep kin, chay bang tay khong can mang: mot PageEvent di vao bus,
     * UrlExtractorService boc lien ket, va URL quay ve frontier.
     *
     * <p>Day la bai test chung minh ca kien truc hoat dong — cung mot chuoi su
     * kien se chay qua Kafka o che do phan tan, chi khac cho no di qua mang.
     */
    @Test
    void fullLoopFromPageEventBackToTheFrontier() {
        CrawlerService crawler = new CrawlerService();
        InProcessCrawlEventBus bus = (InProcessCrawlEventBus) crawler.getEventBus();

        // Dung tay dung dung nhung gi wireInProcessServices() dung, nhung voi
        // bo loc cua rieng bai test — de khong phai chay mot phien crawl that.
        UrlFilter filter = new UrlFilter(java.util.Set.of("a.com"), 5);
        UrlSeenFilter seen = UrlSeenFilter.forMaxPages(1000);
        var extractor = new com.vnsearch.crawler.modular.UrlExtractorService(
                new LinkExtractor(), () -> filter, () -> seen, bus);

        bus.subscribePages(extractor)
                .subscribeDiscoveredUrls(crawler::acceptDiscoveredUrl)
                .subscribeOutlinks(crawler::acceptOutlinks);

        bus.publishPage(new PageEvent(
                "https://a.com/bai", "a.com", 0, "Tieu de", "Than bai", "vi",
                "<a href='https://a.com/mot'>1</a><a href='https://a.com/hai'>2</a>",
                "hash", Instant.EPOCH, crawler.getJobId()));

        assertEquals(2, crawler.getQueueSize(),
                "Hai lien ket phai di het vong bus va quay ve frontier");
    }
}
