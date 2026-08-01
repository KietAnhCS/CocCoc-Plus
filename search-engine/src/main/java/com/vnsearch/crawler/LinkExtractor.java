package com.vnsearch.crawler;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Khối "Link Extractor"</b> trong sơ đồ kiến trúc crawler.
 *
 * <p>Bóc toàn bộ liên kết ra khỏi cây DOM của một trang, chuyển về URL tuyệt
 * đối và chuẩn hoá. Đây là nguồn duy nhất sinh ra URL mới cho vòng lặp
 * crawl — chính là mũi tên đi từ khối này sang {@link UrlFilter}.
 *
 * <p>Tách khỏi {@link ContentParser} vì sơ đồ đặt {@code Content Seen?} vào
 * giữa hai khối: trang trùng nội dung bị vứt trước khi tới đây, nên công
 * đoạn bóc liên kết không phải chạy cho những trang đó.
 *
 * <p><b>Ba phép lọc áp dụng ngay tại đây</b> (đều rẻ và không cần biết gì về
 * cấu hình phiên crawl, nên không đẩy sang {@link UrlFilter}):
 * <ul>
 *   <li>Bỏ liên kết không phải {@code http}/{@code https} — {@code mailto:},
 *       {@code javascript:}, {@code tel:} không phải trang để tải.</li>
 *   <li>Khử trùng lặp bằng {@link LinkedHashSet} — một trang tin thường trỏ
 *       tới cùng một bài từ nhiều vị trí (ảnh, tiêu đề, nút "xem thêm").
 *       Giữ thứ tự xuất hiện để kết quả ổn định giữa các lần chạy.</li>
 *   <li>Bỏ liên kết trỏ về chính trang đang xét (ví dụ {@code href="#muc-2"}) —
 *       không phải liên kết ra ngoài, và nếu để lọt sẽ xếp lại chính trang
 *       vừa crawl vào hàng đợi.</li>
 * </ul>
 *
 * <p>Cả URL gốc lẫn URL đích đều đi qua {@link UrlCanonicalizer} trước khi so
 * sánh. Nếu chỉ bỏ fragment, {@code https://a.com} và {@code https://a.com/}
 * bị coi là hai trang khác nhau và cùng được crawl.
 *
 * <p>Độ phức tạp: O(L) với L là số thẻ {@code <a href>} của trang.
 */
public class LinkExtractor {

    /** Trả về danh sách URL tuyệt đối, đã chuẩn hoá và khử trùng lặp. */
    public List<String> extract(String baseUrl, Document document) {
        String canonicalBase = UrlCanonicalizer.canonicalize(baseUrl);
        Set<String> seen = new LinkedHashSet<>();

        Elements links = document.select("a[href]");
        for (Element link : links) {
            String absUrl = link.absUrl("href");
            if (absUrl == null || absUrl.isBlank()) {
                continue;
            }
            if (!absUrl.startsWith("http://") && !absUrl.startsWith("https://")) {
                continue;
            }
            String canonical = UrlCanonicalizer.canonicalize(absUrl);
            if (!canonical.equals(canonicalBase)) {
                seen.add(canonical);
            }
        }
        return new ArrayList<>(seen);
    }
}
