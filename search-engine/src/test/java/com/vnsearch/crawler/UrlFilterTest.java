package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlFilterTest {

    @Test
    void rejectsNegativeMaxDepth() {
        assertThrows(IllegalArgumentException.class, () -> new UrlFilter(Set.of(), -1));
    }

    @Test
    void acceptsHtmlPageInsideAllowedDomain() {
        UrlFilter filter = new UrlFilter(Set.of("vnexpress.net"), 3);
        assertTrue(filter.accept("https://vnexpress.net/bai-viet-123.html", 1));
        assertEquals(1, filter.getAcceptedCount());
    }

    @Test
    void rejectsUrlDeeperThanMaxDepth() {
        UrlFilter filter = new UrlFilter(Set.of(), 2);
        assertTrue(filter.accept("https://a.vn/x", 2));
        assertFalse(filter.accept("https://a.vn/x", 3));
        assertEquals(1, filter.getRejectedByDepthCount());
    }

    @Test
    void rejectsDomainOutsideAllowedSet() {
        UrlFilter filter = new UrlFilter(Set.of("vnexpress.net"), 3);
        assertFalse(filter.accept("https://facebook.com/gi-do", 1));
        assertEquals(1, filter.getRejectedByDomainCount());
    }

    /** Tap allowedDomains rong nghia la KHONG gioi han domain. */
    @Test
    void emptyAllowedDomainsMeansNoDomainRestriction() {
        UrlFilter filter = new UrlFilter(Set.of(), 3);
        assertTrue(filter.accept("https://bat-ky-dau.com/x", 1));
        assertEquals(0, filter.getRejectedByDomainCount());
    }

    /** So khop bang hau to nen subdomain van duoc chap nhan. */
    @Test
    void subdomainOfAllowedDomainIsAccepted() {
        UrlFilter filter = new UrlFilter(Set.of("vnexpress.net"), 3);
        assertTrue(filter.accept("https://vnexpress.net/x", 1));
        assertTrue(filter.accept("https://www.vnexpress.net/x", 1));
        assertTrue(filter.accept("https://video.vnexpress.net/x", 1));
    }

    @Test
    void rejectsNonHtmlFileExtensions() {
        UrlFilter filter = new UrlFilter(Set.of(), 5);
        assertFalse(filter.accept("https://a.vn/anh.jpg", 1));
        assertFalse(filter.accept("https://a.vn/tai-lieu.pdf", 1));
        assertFalse(filter.accept("https://a.vn/goi.zip", 1));
        assertFalse(filter.accept("https://a.vn/style.CSS", 1), "Phai khong phan biet hoa thuong");
        assertFalse(filter.accept("https://a.vn/video.mp4", 1));
        assertEquals(5, filter.getRejectedByExtensionCount());
    }

    @Test
    void acceptsHtmlAndExtensionlessPaths() {
        UrlFilter filter = new UrlFilter(Set.of(), 5);
        assertTrue(filter.accept("https://a.vn/bai-viet.html", 1));
        assertTrue(filter.accept("https://a.vn/chuyen-muc/bai-viet", 1));
        assertTrue(filter.accept("https://a.vn", 1));
        assertTrue(filter.accept("https://a.vn/", 1));
    }

    /** Dau cham trong ten mien khong duoc nham la duoi tep cua duong dan. */
    @Test
    void dotInHostIsNotTreatedAsFileExtension() {
        UrlFilter filter = new UrlFilter(Set.of(), 5);
        assertTrue(filter.accept("https://tin.tuc.a.vn", 1));
        assertEquals(0, filter.getRejectedByExtensionCount());
    }

    @Test
    void rejectsNonHttpSchemes() {
        UrlFilter filter = new UrlFilter(Set.of(), 5);
        assertFalse(filter.accept("mailto:toasoan@a.vn", 1));
        assertFalse(filter.accept("ftp://a.vn/tep", 1));
        assertFalse(filter.accept("khong-phai-url", 1));
        assertFalse(filter.accept("", 1));
        assertFalse(filter.accept(null, 1));
        assertEquals(5, filter.getRejectedBySchemeCount());
    }

    @Test
    void totalRejectedSumsEveryReason() {
        UrlFilter filter = new UrlFilter(Set.of("a.vn"), 1);
        filter.accept("https://a.vn/x", 9);        // do sau
        filter.accept("mailto:x@a.vn", 0);          // scheme
        filter.accept("https://b.vn/x", 0);         // domain
        filter.accept("https://a.vn/anh.png", 0);   // duoi tep
        assertEquals(4, filter.getTotalRejectedCount());
        assertEquals(0, filter.getAcceptedCount());
    }
}
