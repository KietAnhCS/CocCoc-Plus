package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentParserTest {

    private final ContentParser parser = new ContentParser();

    @Test
    void extractsTitleDescriptionAndBody() {
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
                    <footer>Ban quyen</footer>
                </body>
                </html>
                """;
        Document doc = Jsoup.parse(html, "https://vnsearch.example/");
        WebDocument webDoc = parser.parse("https://vnsearch.example/", doc);

        assertEquals("https://vnsearch.example/", webDoc.getUrl());
        assertEquals("Trang chu VnSearch", webDoc.getTitle());
        assertEquals("Cong cu tim kiem tu xay", webDoc.getMetaDescription());
        assertTrue(webDoc.getBodyText().contains("Noi dung chinh cua trang."));
        assertFalse(webDoc.getBodyText().contains("var x"), "Khong duoc chua noi dung script");
        assertFalse(webDoc.getBodyText().contains("color: red"), "Khong duoc chua noi dung style");
        assertNotNull(webDoc.getCrawledAt());
    }

    @Test
    void missingMetaDescriptionFallsBackToEmptyString() {
        Document doc = Jsoup.parse("<html><head><title>T</title></head><body>Noi dung</body></html>");
        WebDocument webDoc = parser.parse("https://a.vn/", doc);
        assertEquals("", webDoc.getMetaDescription());
    }

    @Test
    void ogDescriptionUsedWhenNoStandardMetaDescription() {
        String html = "<html><head><title>T</title>"
                + "<meta property=\"og:description\" content=\"Mo ta OG\"></head><body>x</body></html>";
        Document doc = Jsoup.parse(html);
        WebDocument webDoc = parser.parse("https://a.vn/", doc);
        assertEquals("Mo ta OG", webDoc.getMetaDescription());
    }

    /**
     * Bao ve ranh gioi trach nhiem giua Content Parser va Link Extractor:
     * so do dat khoi Content Seen? o GIUA hai khoi nay, nen parser khong
     * duoc phep boc lien ket truoc.
     */
    @Test
    void parserDoesNotExtractLinks() {
        String html = "<html><body><a href=\"/tin-tuc\">Tin tuc</a></body></html>";
        Document doc = Jsoup.parse(html, "https://a.vn/");
        WebDocument webDoc = parser.parse("https://a.vn/", doc);
        assertTrue(webDoc.getOutlinks().isEmpty(), "Content Parser khong duoc boc lien ket");
    }
}
