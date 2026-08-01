package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;

/**
 * <b>Khối "Content Parser"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p>Nhận cây DOM đã được {@link HtmlDownloader} tải và phân tích, rút ra
 * phần <b>nội dung</b> của trang: tiêu đề, mô tả meta, văn bản thân bài.
 *
 * <p><b>Vì sao không rút luôn cả liên kết ở đây.</b> Sơ đồ kiến trúc tách
 * {@code Content Parser} và {@code Link Extractor} thành hai khối, và thứ
 * tự giữa chúng có ý nghĩa: giữa hai khối còn có {@code Content Seen?}.
 * Nếu một trang là bản trùng nội dung, ta vứt nó ngay sau khi phân tích
 * nội dung và <b>không</b> bóc liên kết — vì các liên kết đó đã được lấy từ
 * bản gốc rồi. Gộp hai việc vào một lớp (như {@code HtmlExtractor} bản cũ)
 * khiến công đoạn bóc liên kết luôn chạy, kể cả với trang sắp bị vứt.
 * Việc bóc liên kết nay do {@link LinkExtractor} đảm nhiệm.
 *
 * <p>Jsoup là thư viện DUY NHẤT được phép dùng để PARSE HTML theo đặc tả —
 * chỉ làm nhiệm vụ duyệt DOM, KHÔNG làm thay việc tokenize/index/rank; những
 * việc đó vẫn do {@code VietnameseTokenizer}/{@code InvertedIndex}/
 * {@code ResultRanker} tự cài đảm nhiệm.
 */
public class ContentParser {

    /**
     * Dựng một {@link WebDocument} từ cây DOM đã tải.
     *
     * <p>Tài liệu trả về chưa có {@code docId} (do {@code CrawlerService} gán
     * sau) và <b>chưa có outlink</b> (do {@link LinkExtractor} điền sau, nếu
     * trang vượt qua được bước kiểm tra trùng nội dung).
     */
    public WebDocument parse(String url, Document document) {
        WebDocument doc = new WebDocument();
        doc.setUrl(url);
        doc.setTitle(document.title());
        doc.setMetaDescription(extractMetaDescription(document));
        doc.setBodyText(extractBodyText(document));
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
}
