package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Trich xuat noi dung tu mot {@link Document} da duoc Jsoup fetch+parse san
 * (Jsoup la thu vien DUY NHAT duoc phep dung de PARSE HTML theo dac ta —
 * chi lam nhiem vu duyet DOM, KHONG lam thay viec tokenize/index/rank,
 * nhung viec do van do VietnameseTokenizer/InvertedIndex/ResultRanker tu
 * cai dat dam nhiem).
 *
 * <p>Trich xuat: title, meta description, body text (da loai script/style
 * va cac the khong hien thi), danh sach outlink (chuyen ve absolute URL),
 * ngay crawl (thoi diem goi ham nay).
 */
public class HtmlExtractor {

    /**
     * Xay dung mot {@link WebDocument} (chua co docId - se duoc
     * CrawlerService gan sau) tu Document da fetch.
     */
    public WebDocument extract(String url, Document document) {
        String title = document.title();
        String metaDescription = extractMetaDescription(document);
        String bodyText = extractBodyText(document);
        List<String> outlinks = extractOutlinks(url, document);

        WebDocument doc = new WebDocument();
        doc.setUrl(url);
        doc.setTitle(title);
        doc.setMetaDescription(metaDescription);
        doc.setBodyText(bodyText);
        doc.setOutlinks(outlinks);
        doc.setCrawledAt(Instant.now());
        return doc;
    }

    private String extractMetaDescription(Document document) {
        Element meta = document.selectFirst("meta[name=description]");
        if (meta == null) {
            meta = document.selectFirst("meta[property=og:description]");
        }
        return meta != null ? meta.attr("content").trim() : "";
    }

    private String extractBodyText(Document document) {
        Document clone = document.clone();
        clone.select("script, style, noscript, nav, footer, header, iframe, svg").remove();
        return clone.body() != null ? clone.body().text().trim() : "";
    }

    private List<String> extractOutlinks(String baseUrl, Document document) {
        // Chuan hoa CA base lan outlink truoc khi so sanh: neu chi bo fragment,
        // "https://a.com" va "https://a.com/" bi coi la 2 trang khac nhau va
        // deu duoc crawl - xem UrlCanonicalizer de biet chi tiet.
        String canonicalBase = UrlCanonicalizer.canonicalize(baseUrl);
        Set<String> seen = new LinkedHashSet<>();
        Elements links = document.select("a[href]");
        for (Element link : links) {
            String absUrl = link.absUrl("href");
            if (absUrl == null || absUrl.isBlank()) {
                continue;
            }
            if (absUrl.startsWith("http://") || absUrl.startsWith("https://")) {
                String canonical = UrlCanonicalizer.canonicalize(absUrl);
                // Bo qua anchor link tro ve chinh trang nay (vd href="#section") -
                // khong phai outlink that su, khong nen queue lai chinh trang dang crawl.
                if (!canonical.equals(canonicalBase)) {
                    seen.add(canonical);
                }
            }
        }
        return new ArrayList<>(seen);
    }
}
