package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExtractorTest {

    private final HtmlExtractor extractor = new HtmlExtractor();

    @Test
    void extractsTitleDescriptionBodyAndLinks() {
        String html = """
                <html>
                <head>
                    <title>Trang chu VnSearch</title>
                    <meta name="description" content="Cong cu tim kiem tu xay">
                    <script>var x = 1;</script>
                    <style>.a { color: red; }</style>
                </head>
                <body>
                    <nav>Menu</nav>
                    <p>Noi dung chinh cua trang.</p>
                    <a href="/tin-tuc">Tin tuc</a>
                    <a href="https://khac.vn/bai-viet">Bai viet khac</a>
                    <a href="#section">Muc luc</a>
                    <footer>Ban quyen</footer>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html, "https://vnsearch.example/");
        WebDocument webDoc = extractor.extract("https://vnsearch.example/", doc);

        assertEquals("Trang chu VnSearch", webDoc.getTitle());
        assertEquals("Cong cu tim kiem tu xay", webDoc.getMetaDescription());
        assertTrue(webDoc.getBodyText().contains("Noi dung chinh cua trang."));
        assertFalse(webDoc.getBodyText().contains("var x"), "Khong duoc chua noi dung script");
        assertFalse(webDoc.getBodyText().contains("color: red"), "Khong duoc chua noi dung style");

        assertTrue(webDoc.getOutlinks().contains("https://vnsearch.example/tin-tuc"));
        assertTrue(webDoc.getOutlinks().contains("https://khac.vn/bai-viet"));
        assertEquals(2, webDoc.getOutlinks().size(), "Link chi co fragment (#section) khong tinh la outlink rieng");
    }

    @Test
    void missingMetaDescriptionFallsBackToEmptyString() {
        Document doc = Jsoup.parse("<html><head><title>T</title></head><body>Noi dung</body></html>");
        WebDocument webDoc = extractor.extract("https://a.vn/", doc);
        assertEquals("", webDoc.getMetaDescription());
    }

    @Test
    void ogDescriptionUsedWhenNoStandardMetaDescription() {
        String html = "<html><head><title>T</title>"
                + "<meta property=\"og:description\" content=\"Mo ta OG\"></head><body>x</body></html>";
        Document doc = Jsoup.parse(html);
        WebDocument webDoc = extractor.extract("https://a.vn/", doc);
        assertEquals("Mo ta OG", webDoc.getMetaDescription());
    }

    @Test
    void duplicateLinksAreDeduplicated() {
        String html = "<html><body>"
                + "<a href=\"/x\">1</a><a href=\"/x\">2</a><a href=\"/x#top\">3</a>"
                + "</body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn/");
        WebDocument webDoc = extractor.extract("https://a.vn/", doc);
        assertEquals(1, webDoc.getOutlinks().size());
    }
}
