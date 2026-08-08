package com.vnsearch.crawler.bus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bus in-process — hai tinh chat quan trong nhat: PHAT TAN toi moi service, va
 * CO LAP loi giua chung.
 */
class InProcessCrawlEventBusTest {

    private static PageEvent page(String url) {
        return new PageEvent(url, "a.com", 0, "t", "than bai", "vi",
                "<html></html>", "hash", Instant.EPOCH, "job-1");
    }

    /**
     * Phat tan mot-toi-nhieu: MOI service nhan duoc TOAN BO luong, khong phai
     * chia nhau. Day la tinh chat ma Kafka cho bang consumer group, va ban
     * in-process phai mo phong dung nhu vay.
     */
    @Test
    void everyHandlerReceivesEveryPage() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        List<String> c = new ArrayList<>();

        bus.subscribePages(e -> a.add(e.url()))
                .subscribePages(e -> b.add(e.url()))
                .subscribePages(e -> c.add(e.url()));

        bus.publishPage(page("https://a.com/1"));
        bus.publishPage(page("https://a.com/2"));

        assertEquals(List.of("https://a.com/1", "https://a.com/2"), a);
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(3, bus.pageHandlerCount());
        assertEquals(2, bus.getPagesPublishedCount());
    }

    /**
     * Mot service hong KHONG duoc lam hai service kia ngung chay.
     *
     * <p>Neu thieu co lap: UrlExtractorService dang ky sau se khong bao gio
     * chay, frontier ngung duoc nap, va ca phien crawl chet dung — mot service
     * phu giet ca crawler.
     */
    @Test
    void oneFailingHandlerDoesNotStopTheOthers() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();

        bus.subscribePages(e -> before.incrementAndGet())
                .subscribePages(e -> {
                    throw new IllegalStateException("service nay hong");
                })
                .subscribePages(e -> after.incrementAndGet());

        bus.publishPage(page("https://a.com/1"));

        assertEquals(1, before.get());
        assertEquals(1, after.get(), "Service dang ky SAU cai hong van phai chay");
        assertEquals(1, bus.getPublishFailureCount());
    }

    /** Ngoai le tu handler khong duoc bay nguoc ve phia crawler. */
    @Test
    void publishNeverThrowsToTheCaller() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        bus.subscribePages(e -> {
            throw new RuntimeException("no");
        });
        bus.publishPage(page("https://a.com/1")); // khong duoc nem
        assertEquals(1, bus.getPublishFailureCount());
    }

    @Test
    void allFourChannelsAreDelivered() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        AtomicInteger pages = new AtomicInteger();
        AtomicInteger urls = new AtomicInteger();
        AtomicInteger outlinks = new AtomicInteger();
        AtomicInteger images = new AtomicInteger();

        bus.subscribePages(e -> pages.incrementAndGet())
                .subscribeDiscoveredUrls(u -> urls.incrementAndGet())
                .subscribeOutlinks(o -> outlinks.incrementAndGet())
                .subscribeImages(i -> images.incrementAndGet());

        bus.publishPage(page("https://a.com/1"));
        bus.publishDiscoveredUrl(new DiscoveredUrl("https://a.com/2", "a.com", 1,
                "https://a.com/1", "job-1"));
        bus.publishOutlinks(new OutlinksExtracted("https://a.com/1", "a.com",
                List.of("https://a.com/2"), "job-1"));
        bus.publishImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/anh.jpg", "alt", 10, 10));

        assertEquals(1, pages.get());
        assertEquals(1, urls.get());
        assertEquals(1, outlinks.get());
        assertEquals(1, images.get());
        assertEquals(1, bus.getUrlsPublishedCount());
        assertEquals(1, bus.getImagesPublishedCount());
    }

    /** null bi bo qua lang le thay vi nem NPE nguoc ve crawler. */
    @Test
    void nullPayloadsAreIgnored() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        AtomicInteger calls = new AtomicInteger();
        bus.subscribePages(e -> calls.incrementAndGet())
                .subscribeDiscoveredUrls(u -> calls.incrementAndGet())
                .subscribeOutlinks(o -> calls.incrementAndGet())
                .subscribeImages(i -> calls.incrementAndGet());

        bus.publishPage(null);
        bus.publishDiscoveredUrl(null);
        bus.publishOutlinks(null);
        bus.publishImage(null);

        assertEquals(0, calls.get());
        assertEquals(0, bus.getPublishFailureCount());
    }

    /** Dang ky null khong duoc lam hong danh sach handler. */
    @Test
    void nullSubscribersAreIgnored() {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        bus.subscribePages(null).subscribeDiscoveredUrls(null)
                .subscribeOutlinks(null).subscribeImages(null);
        assertEquals(0, bus.pageHandlerCount());
    }

    /**
     * Bus bi goi tu MOI worker thread cua crawler cung luc. Bai test nay chay
     * that nhieu luong de bat mat mat khi dem.
     */
    @Test
    void isThreadSafeUnderConcurrentPublishing() throws Exception {
        InProcessCrawlEventBus bus = new InProcessCrawlEventBus();
        AtomicInteger received = new AtomicInteger();
        bus.subscribePages(e -> received.incrementAndGet());

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        bus.publishPage(page("https://a.com/" + i));
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(30, TimeUnit.SECONDS), "Cac luong phai ket thuc trong 30 giay");
        pool.shutdownNow();

        assertEquals(threads * perThread, received.get());
        assertEquals(threads * perThread, bus.getPagesPublishedCount());
    }

    /** Bus rong (Null Object) nhan moi thu ma khong nem gi. */
    @Test
    void noopBusSwallowsEverything() {
        CrawlEventBus bus = CrawlEventBus.noop();
        bus.publishPage(page("https://a.com/1"));
        bus.publishDiscoveredUrl(new DiscoveredUrl("https://a.com/2", "a.com", 1, "s", "j"));
        bus.publishOutlinks(new OutlinksExtracted("https://a.com/1", "a.com", List.of(), "j"));
        bus.publishImage(ImageFound.metadataOnly("https://a.com/1", "a.com",
                "https://a.com/x.jpg", "", 1, 1));
        assertEquals(0, bus.getPublishFailureCount());
    }
}
