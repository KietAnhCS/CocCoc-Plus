package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.DnsResolver;
import com.vnsearch.crawler.bus.ImageFound;
import com.vnsearch.crawler.bus.InProcessCrawlEventBus;
import com.vnsearch.crawler.bus.PageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modular Service 2 — bo anh ra khoi DOM.
 *
 * <p>Moi bai test chay o CHE DO MAC DINH (khong tai noi dung), nen khong bai
 * nao mo mot ket noi mang. Do vua la hanh vi mac dinh dung, vua la thu khien
 * bo test chay duoc o moi truong khong co mang.
 */
class ImageDownloadServiceTest {

    private InProcessCrawlEventBus bus;
    private List<ImageFound> found;

    @BeforeEach
    void setUp() {
        bus = new InProcessCrawlEventBus();
        found = new ArrayList<>();
        bus.subscribeImages(found::add);
    }

    private static PageEvent pageWith(String html) {
        return new PageEvent("https://a.com/bai", "a.com", 1, "Tieu de", "Than bai",
                "vi", html, "hash", Instant.EPOCH, "job-1");
    }

    @Test
    void extractsImageMetadataWithoutDownloading() {
        ImageDownloadService service = new ImageDownloadService(bus);
        service.onPage(pageWith("""
                <img src="https://a.com/anh1.jpg" alt="Mo ta" width="800" height="600">
                """));

        assertEquals(1, found.size());
        ImageFound image = found.get(0);
        assertEquals("https://a.com/anh1.jpg", image.imageUrl());
        assertEquals("Mo ta", image.altText());
        assertEquals(800, image.declaredWidth());
        assertEquals(600, image.declaredHeight());
        assertFalse(image.isDownloaded(), "Mac dinh KHONG tai noi dung anh");
        assertFalse(service.isDownloadEnabled());
    }

    /**
     * Anh nap tre (lazy loading): dia chi that nam o data-src, con src chi la
     * anh giu cho. Do tren vnexpress.net: 22/31 the img dung kieu nay — chi
     * doc src thi thu duy nhat lot vao kho la logo va icon cua site.
     */
    @Test
    void prefersDataSrcOverPlaceholderSrc() {
        new ImageDownloadService(bus).onPage(pageWith("""
                <img class="lazy" src="https://a.com/giu-cho.jpg"
                     data-src="https://a.com/anh-that.jpg" alt="anh that">
                """));

        assertEquals(1, found.size());
        assertEquals("https://a.com/anh-that.jpg", found.get(0).imageUrl(),
                "Phai lay data-src, khong phai anh giu cho o src");
    }

    /** Quy uoc cu cua jQuery Lazy Load, van con gap tren site doi truoc. */
    @Test
    void fallsBackToDataOriginal() {
        new ImageDownloadService(bus).onPage(
                pageWith("<img data-original='https://a.com/cu.jpg' alt='x'>"));
        assertEquals("https://a.com/cu.jpg", found.get(0).imageUrl());
    }

    /** Khong co data-src thi src van duoc dung nhu binh thuong. */
    @Test
    void usesPlainSrcWhenThereIsNoLazyAttribute() {
        new ImageDownloadService(bus).onPage(
                pageWith("<img src='https://a.com/thuong.jpg' alt='x'>"));
        assertEquals("https://a.com/thuong.jpg", found.get(0).imageUrl());
    }

    @Test
    void resolvesRelativeImageUrls() {
        new ImageDownloadService(bus).onPage(pageWith("<img src='/tinh/anh.png'>"));
        assertEquals("https://a.com/tinh/anh.png", found.get(0).imageUrl());
    }

    /**
     * Cat query truoc khi xet duoi tep. Bo buoc nay thi gan nhu MOI anh that
     * tren bao dien tu bi loai — chung deu co tham so doi kich thuoc.
     */
    @Test
    void acceptsImagesWithQueryStringAfterTheExtension() {
        new ImageDownloadService(bus).onPage(pageWith("""
                <img src="https://a.com/anh.jpg?w=800&amp;v=2">
                """));
        assertEquals(1, found.size());
    }

    @Test
    void extensionCheckHandlesQueryAndFragment() {
        assertTrue(ImageDownloadService.hasImageExtension("https://a.com/x.jpg"));
        assertTrue(ImageDownloadService.hasImageExtension("https://a.com/x.JPG"));
        assertTrue(ImageDownloadService.hasImageExtension("https://a.com/x.webp?w=1"));
        assertTrue(ImageDownloadService.hasImageExtension("https://a.com/x.png#top"));
        assertFalse(ImageDownloadService.hasImageExtension("https://a.com/x.pdf"));
        assertFalse(ImageDownloadService.hasImageExtension("https://a.com/tracker"));
    }

    @Test
    void skipsNonImageExtensions() {
        ImageDownloadService service = new ImageDownloadService(bus);
        service.onPage(pageWith("""
                <img src="https://a.com/tai-lieu.pdf">
                <img src="https://a.com/that.jpg">
                """));

        assertEquals(1, found.size());
        assertEquals(1, service.getImagesSkippedByExtensionCount());
    }

    /** Mot trang thuong lap cung mot anh o nhieu cho. */
    @Test
    void deduplicatesRepeatedImagesOnTheSamePage() {
        new ImageDownloadService(bus).onPage(pageWith("""
                <img src="https://a.com/anh.jpg" alt="mot">
                <img src="https://a.com/anh.jpg" alt="hai">
                """));
        assertEquals(1, found.size());
    }

    /** Trang thu vien anh co the co hang nghin the img. */
    @Test
    void respectsTheMaxImagesPerPageLimit() {
        ImageDownloadService service = new ImageDownloadService(
                bus, new DnsResolver(), false, 3, 1024, 1000);

        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            html.append("<img src='https://a.com/anh").append(i).append(".jpg'>");
        }
        service.onPage(pageWith(html.toString()));

        assertEquals(3, found.size());
        assertEquals(7, service.getImagesOverPageLimitCount());
    }

    @Test
    void countsImagesMissingAltText() {
        ImageDownloadService service = new ImageDownloadService(bus);
        service.onPage(pageWith("""
                <img src="https://a.com/co-alt.jpg" alt="co">
                <img src="https://a.com/khong-alt.jpg">
                <img src="https://a.com/alt-rong.jpg" alt="">
                """));

        assertEquals(3, service.getImagesFoundCount());
        assertEquals(2, service.getImagesMissingAltCount());
        assertEquals(2.0 / 3.0, service.getMissingAltRate(), 0.001);
    }

    @Test
    void parsesDimensionsWithUnitSuffix() {
        new ImageDownloadService(bus).onPage(
                pageWith("<img src='https://a.com/x.jpg' width='800px' height='abc'>"));
        assertEquals(800, found.get(0).declaredWidth());
        assertEquals(-1, found.get(0).declaredHeight(), "Gia tri khong doc duoc -> -1");
    }

    @Test
    void missingDimensionsBecomeMinusOne() {
        new ImageDownloadService(bus).onPage(pageWith("<img src='https://a.com/x.jpg'>"));
        assertEquals(-1, found.get(0).declaredWidth());
        assertEquals(-1, found.get(0).declaredHeight());
    }

    @Test
    void countsAverageImagesPerPage() {
        ImageDownloadService service = new ImageDownloadService(bus);
        assertEquals(0.0, service.getAverageImagesPerPage());

        service.onPage(pageWith("<img src='https://a.com/1.jpg'><img src='https://a.com/2.jpg'>"));
        service.onPage(pageWith("<img src='https://a.com/3.jpg'>"));

        assertEquals(2, service.getPagesProcessedCount());
        assertEquals(3, service.getImagesFoundCount());
        assertEquals(1.5, service.getAverageImagesPerPage(), 0.001);
    }

    @Test
    void pageWithoutHtmlIsIgnored() {
        ImageDownloadService service = new ImageDownloadService(bus);
        service.onPage(pageWith(null));
        assertEquals(0, service.getPagesProcessedCount());
        assertEquals(0, found.size());
    }

    @Test
    void pageWithNoImagesProducesNothing() {
        ImageDownloadService service = new ImageDownloadService(bus);
        service.onPage(pageWith("<html><body><p>khong co anh</p></body></html>"));
        assertEquals(1, service.getPagesProcessedCount());
        assertEquals(0, found.size());
    }

    @Test
    void handlerNameIsReadableInLogs() {
        assertEquals("Image Download", new ImageDownloadService(bus).handlerName());
    }

    @Test
    void constructorValidatesItsLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ImageDownloadService(null));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageDownloadService(bus, new DnsResolver(), false, 0, 100, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new ImageDownloadService(bus, new DnsResolver(), false, 10, 0, 1000));
    }

    /**
     * SSRF: khi BAT tai anh, mot dia chi noi bo phai bi chan va service phai
     * lui ve ban ghi sieu du lieu — khong nem, khong lam chet ca trang.
     *
     * <p>169.254.169.254 la dia chi dich vu sieu du lieu cua may ao dam may;
     * day dung la dia chi ma SeedUrlValidator sinh ra de chan.
     */
    @Test
    void blockedAddressFallsBackToMetadataWhenDownloadEnabled() {
        ImageDownloadService service = new ImageDownloadService(
                bus, new DnsResolver(), true, 10, 1024, 1000);

        service.onPage(pageWith("<img src='http://169.254.169.254/anh.jpg' alt='x'>"));

        assertEquals(1, found.size());
        assertFalse(found.get(0).isDownloaded(), "Anh bi chan van cho ra ban ghi sieu du lieu");
        assertEquals(1, service.getImagesBlockedCount());
        assertEquals(0, service.getImagesDownloadedCount());
    }
}
