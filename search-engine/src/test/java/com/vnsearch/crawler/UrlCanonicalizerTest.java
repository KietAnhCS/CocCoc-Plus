package com.vnsearch.crawler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test cho việc chuẩn hoá URL.
 *
 * <p>Bảo vệ chống lại lỗi đã gặp thật: phiên crawl 5.011 trang tạo ra 23
 * cặp trang trùng nhau chỉ vì khác dấu gạch chéo cuối.
 */
class UrlCanonicalizerTest {

    @Test
    void trailingSlashIsRemoved() {
        assertEquals(UrlCanonicalizer.canonicalize("https://a.com/"),
                UrlCanonicalizer.canonicalize("https://a.com"),
                "Trang gốc có và không có dấu / phải cho cùng một dạng chuẩn");
    }

    @Test
    void trailingSlashOnPathIsRemoved() {
        assertEquals("https://a.com/tin-tuc", UrlCanonicalizer.canonicalize("https://a.com/tin-tuc/"));
        assertEquals("https://a.com/tin-tuc", UrlCanonicalizer.canonicalize("https://a.com/tin-tuc"));
    }

    @Test
    void fragmentIsRemoved() {
        assertEquals("https://a.com/bai-viet",
                UrlCanonicalizer.canonicalize("https://a.com/bai-viet#phan-2"));
    }

    @Test
    void schemeAndHostAreLowercasedButPathIsNot() {
        // RFC 3986: scheme và host không phân biệt hoa thường, đường dẫn thì CÓ.
        assertEquals("https://a.com/Duong-Dan-Hoa",
                UrlCanonicalizer.canonicalize("HTTPS://A.COM/Duong-Dan-Hoa"));
    }

    @Test
    void defaultPortsAreRemoved() {
        assertEquals("https://a.com/x", UrlCanonicalizer.canonicalize("https://a.com:443/x"));
        assertEquals("http://a.com/x", UrlCanonicalizer.canonicalize("http://a.com:80/x"));
    }

    @Test
    void nonDefaultPortIsKept() {
        assertEquals("http://a.com:8080/x", UrlCanonicalizer.canonicalize("http://a.com:8080/x"));
    }

    @Test
    void queryStringIsPreserved() {
        // Cố ý KHÔNG đụng tới query: bỏ hay đổi thứ tự tham số có thể làm
        // thay đổi trang trả về, đó là phép chuẩn hoá không an toàn.
        assertEquals("https://a.com/tim?q=abc&page=2",
                UrlCanonicalizer.canonicalize("https://a.com/tim?q=abc&page=2"));
        assertNotEquals(UrlCanonicalizer.canonicalize("https://a.com/tim?q=abc"),
                UrlCanonicalizer.canonicalize("https://a.com/tim?q=xyz"));
    }

    @Test
    void malformedUrlIsReturnedAsIsWithoutFragment() {
        // Thà giữ nguyên còn hơn làm hỏng một URL vốn có thể fetch được.
        assertEquals("khong-phai-url", UrlCanonicalizer.canonicalize("khong-phai-url#abc"));
    }

    @Test
    void nullAndBlankAreHandled() {
        assertEquals(null, UrlCanonicalizer.canonicalize(null));
        assertEquals("", UrlCanonicalizer.canonicalize(""));
    }

    @Test
    void frontierTreatsVariantsOfSameUrlAsDuplicate() {
        com.vnsearch.datastructure.UrlFrontier frontier = new com.vnsearch.datastructure.UrlFrontier();
        assertEquals(true, frontier.addUrl("https://a.com/tin", 0, 0));
        assertEquals(false, frontier.addUrl("https://a.com/tin/", 0, 0),
                "Biến thể chỉ khác dấu / phải bị coi là trùng");
        assertEquals(false, frontier.addUrl("https://A.COM/tin#phan-1", 0, 0),
                "Biến thể khác hoa thường và có fragment cũng phải bị coi là trùng");
        assertEquals(1, frontier.size());
    }
}
