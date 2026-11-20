package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.LinkExtractor;
import com.vnsearch.crawler.UrlFilter;
import com.vnsearch.crawler.UrlSeenFilter;
import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.InProcessCrawlEventBus;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.crawler.bus.PageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modular Service 1 — chang {@code URL Extractor -> URL Filter -> URL Seen}.
 *
 * <p>Toan bo bai test chay KHONG can broker: service khong biet Kafka ton tai,
 * dung nhu thiet ke. Do chinh la thu ma interface {@code PageEventHandler}
 * mua duoc.
 */
class UrlExtractorServiceTest {

    private InProcessCrawlEventBus bus;
    private List<DiscoveredUrl> discovered;
    private List<OutlinksExtracted> outlinks;
    private UrlFilter filter;
    private UrlSeenFilter seen;

    @BeforeEach
    void setUp() {
        bus = new InProcessCrawlEventBus();
        discovered = new ArrayList<>();
        outlinks = new ArrayList<>();
        bus.subscribeDiscoveredUrls(discovered::add).subscribeOutlinks(outlinks::add);
        filter = new UrlFilter(Set.of("a.com"), 5);
        seen = UrlSeenFilter.forMaxPages(1000);
    }

    private UrlExtractorService service() {
        return new UrlExtractorService(new LinkExtractor(), () -> filter, () -> seen, bus);
    }

    private static PageEvent pageWith(String html) {
        return new PageEvent("https://a.com/bai", "a.com", 2, "Tieu de", "Than bai",
                "vi", html, "hash", Instant.EPOCH, "job-42");
    }

    @Test
    void extractsLinksAndPublishesThemBothWays() {
        String html = """
                <html><body>
                  <a href="https://a.com/mot">mot</a>
                  <a href="/hai">hai</a>
                </body></html>
                """;

        service().onPage(pageWith(html));

        // Mot su kien outlinks cho ca trang
        assertEquals(1, outlinks.size());
        assertEquals(2, outlinks.get(0).size());
        assertEquals("https://a.com/bai", outlinks.get(0).sourceUrl());

        // Hai URL rieng le di vao frontier
        assertEquals(2, discovered.size());
        assertTrue(discovered.stream().anyMatch(d -> d.url().equals("https://a.com/mot")));
        assertTrue(discovered.stream().anyMatch(d -> d.url().equals("https://a.com/hai")));
    }

    /**
     * Do sau tang dung MOT bac. Sai cho nay thi luat maxDepth hoac khong bao
     * gio chan, hoac chan qua som — ca hai deu chi lo ra sau hang nghin trang.
     */
    @Test
    void childDepthIsParentPlusOne() {
        service().onPage(pageWith("<a href='https://a.com/con'>con</a>"));
        assertEquals(3, discovered.get(0).depth(), "Trang o do sau 2 -> con o do sau 3");
    }

    /** jobId phai di xuyen suot: trang -> lien ket -> frontier. */
    @Test
    void jobIdIsPropagatedToEveryDownstreamEvent() {
        service().onPage(pageWith("<a href='https://a.com/con'>con</a>"));

        assertEquals("job-42", discovered.get(0).jobId());
        assertEquals("job-42", outlinks.get(0).jobId());
    }

    @Test
    void sourceUrlPointsBackToThePage() {
        service().onPage(pageWith("<a href='https://a.com/con'>con</a>"));
        assertEquals("https://a.com/bai", discovered.get(0).sourceUrl());
    }

    /**
     * Lien ket tuong doi phai phan giai duoc. Neu baseUri khong duoc truyen
     * vao Jsoup.parse thi absUrl tra ve chuoi rong va trang coi nhu khong co
     * lien ket nao — crawler dung sau vai trang ma khong co loi nao duoc ghi.
     */
    @Test
    void resolvesRelativeLinksAgainstThePageUrl() {
        service().onPage(pageWith("<a href='/muc/con'>con</a>"));
        assertEquals("https://a.com/muc/con", discovered.get(0).url());
    }

    @Test
    void urlFilterRejectionIsCountedAndNotPublished() {
        // b.com khong nam trong allowedDomains
        service().onPage(pageWith("<a href='https://b.com/ngoai'>ngoai</a>"));

        assertEquals(0, discovered.size());
        UrlExtractorService s = service();
        s.onPage(pageWith("<a href='https://b.com/ngoai'>ngoai</a>"));
        assertEquals(1, s.getRejectedByFilterCount());
    }

    /** URL da gap thi khong xep hang lai — nhung VAN nam trong outlinks. */
    @Test
    void alreadySeenUrlIsNotQueuedButStaysInOutlinks() {
        UrlExtractorService s = service();
        String html = "<a href='https://a.com/con'>con</a>";

        s.onPage(pageWith(html));
        assertEquals(1, discovered.size());

        s.onPage(pageWith(html)); // lan hai: URL da gap
        assertEquals(1, discovered.size(), "Khong duoc xep hang lai");
        assertEquals(1, s.getRejectedAsSeenCount());

        // Nhung outlinks van day du — day la du lieu cho PageRank, khong duoc loc
        assertEquals(2, outlinks.size());
        assertEquals(1, outlinks.get(1).size());
    }

    /**
     * Day la bat bien quan trong nhat cua thiet ke: tap outlinks (cho PageRank)
     * KHAC tap URL vao frontier (cho vong lap crawl). Gop lam mot thi do thi
     * lien ket mat gan het canh noi bo va PageRank thanh mot cot so vo nghia
     * ma van chay trot lot.
     */
    @Test
    void outlinksKeepEverythingEvenWhatTheFilterRejects() {
        service().onPage(pageWith("""
                <a href="https://a.com/trong">trong</a>
                <a href="https://b.com/ngoai">ngoai</a>
                """));

        assertEquals(2, outlinks.get(0).size(), "Outlinks giu ca lien ket bi loc");
        assertEquals(1, discovered.size(), "Nhung chi 1 URL duoc vao frontier");
    }

    @Test
    void pageWithoutHtmlIsSkippedAndCounted() {
        UrlExtractorService s = service();
        s.onPage(pageWith(null));
        s.onPage(pageWith("   "));

        assertEquals(2, s.getPagesWithoutHtmlCount());
        assertEquals(0, s.getPagesProcessedCount());
        assertEquals(0, discovered.size());
    }

    @Test
    void countsAverageOutlinksPerPage() {
        UrlExtractorService s = service();
        assertEquals(0.0, s.getAverageOutlinksPerPage());

        s.onPage(pageWith("<a href='https://a.com/1'>1</a><a href='https://a.com/2'>2</a>"));
        s.onPage(new PageEvent("https://a.com/bai2", "a.com", 1, "t", "b", "vi",
                "<a href='https://a.com/3'>3</a>", "h", Instant.EPOCH, "job-42"));

        assertEquals(2, s.getPagesProcessedCount());
        assertEquals(3, s.getLinksExtractedCount());
        assertEquals(1.5, s.getAverageOutlinksPerPage(), 0.001);
    }

    /**
     * Supplier chu khong phai tham chieu co dinh: CrawlerService cap phat lai
     * bo loc cho TUNG phien crawl. Giu tham chieu co dinh thi phien thu hai
     * loc theo domain cua phien thu nhat.
     */
    @Test
    void seesTheCurrentFilterNotTheOneAtConstructionTime() {
        UrlExtractorService s = service();

        s.onPage(pageWith("<a href='https://b.com/x'>x</a>"));
        assertEquals(0, discovered.size(), "b.com bi loai boi bo loc phien 1");

        // "Phien 2": doi bo loc, service phai thay ngay
        filter = new UrlFilter(Set.of("b.com"), 5);
        seen = UrlSeenFilter.forMaxPages(1000);
        s.onPage(pageWith("<a href='https://b.com/x'>x</a>"));

        assertEquals(1, discovered.size(), "Sau khi doi bo loc, b.com phai duoc chap nhan");
    }

    @Test
    void handlerNameIsReadableInLogs() {
        assertEquals("URL Extractor", service().handlerName());
    }

    @Test
    void constructorRejectsMissingCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new UrlExtractorService(null, () -> filter, () -> seen, bus));
        assertThrows(IllegalArgumentException.class,
                () -> new UrlExtractorService(new LinkExtractor(), null, () -> seen, bus));
        assertThrows(IllegalArgumentException.class,
                () -> new UrlExtractorService(new LinkExtractor(), () -> filter, null, bus));
        assertThrows(IllegalArgumentException.class,
                () -> new UrlExtractorService(new LinkExtractor(), () -> filter, () -> seen, null));
    }

    /** Lien ket tro ve chinh trang dang xet phai bi LinkExtractor loai. */
    @Test
    void selfLinkIsNotQueued() {
        service().onPage(pageWith("<a href='https://a.com/bai'>chinh no</a>"));
        assertFalse(discovered.stream().anyMatch(d -> d.url().equals("https://a.com/bai")));
    }
}
