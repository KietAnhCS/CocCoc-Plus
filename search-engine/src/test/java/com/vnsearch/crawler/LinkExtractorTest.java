package com.vnsearch.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkExtractorTest {

    private final LinkExtractor extractor = new LinkExtractor();

    @Test
    void convertsRelativeLinksToAbsoluteAndSkipsSelfAnchor() {
        String html = """
                <html><body>
                    <a href="/tin-tuc">Tin tuc</a>
                    <a href="https://khac.vn/bai-viet">Bai viet khac</a>
                    <a href="#section">Muc luc</a>
                </body></html>
                """;
        Document doc = Jsoup.parse(html, "https://vnsearch.example/");
        List<String> links = extractor.extract("https://vnsearch.example/", doc);

        assertTrue(links.contains("https://vnsearch.example/tin-tuc"));
        assertTrue(links.contains("https://khac.vn/bai-viet"));
        assertEquals(2, links.size(), "Link chi co fragment (#section) khong tinh la outlink rieng");
    }

    @Test
    void duplicateLinksAreDeduplicated() {
        String html = "<html><body>"
                + "<a href=\"/x\">1</a><a href=\"/x\">2</a><a href=\"/x#top\">3</a>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn/");
        assertEquals(1, extractor.extract("https://a.vn/", doc).size());
    }

    @Test
    void nonHttpSchemesAreSkipped() {
        String html = "<html><body>"
                + "<a href=\"mailto:toasoan@a.vn\">Mail</a>"
                + "<a href=\"tel:0123456789\">Goi</a>"
                + "<a href=\"javascript:void(0)\">JS</a>"
                + "<a href=\"https://a.vn/that\">That</a>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn/");
        List<String> links = extractor.extract("https://a.vn/", doc);

        assertEquals(List.of("https://a.vn/that"), links);
    }

    /** Base va outlink deu duoc chuan hoa truoc khi so sanh, khong chi bo fragment. */
    @Test
    void trailingSlashVariantOfSelfIsNotAnOutlink() {
        String html = "<html><body><a href=\"https://a.vn/\">Ve trang chu</a></body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn");
        assertTrue(extractor.extract("https://a.vn", doc).isEmpty());
    }

    @Test
    void orderOfFirstAppearanceIsPreserved() {
        String html = "<html><body>"
                + "<a href=\"/b\">b</a><a href=\"/a\">a</a><a href=\"/b\">b lai</a>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn/");
        assertEquals(List.of("https://a.vn/b", "https://a.vn/a"),
                extractor.extract("https://a.vn/", doc));
    }
}
