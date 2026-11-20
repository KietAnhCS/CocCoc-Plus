package com.vnsearch.crawler;

import java.net.URI;
import java.util.Locale;

/**
 * Chuẩn hoá URL về một dạng biểu diễn duy nhất trước khi đưa vào frontier
 * hoặc lưu trữ.
 *
 * <p><b>Vì sao cần:</b> cùng một trang web có thể xuất hiện dưới nhiều
 * chuỗi URL khác nhau. Nếu không chuẩn hoá, Bloom Filter và tập
 * {@code enqueued} của frontier coi chúng là các URL khác nhau nên crawl
 * lặp lại cùng một nội dung. Hậu quả không chỉ là lãng phí băng thông: các
 * bản sao cùng lọt vào chỉ mục và cùng xuất hiện trong kết quả tìm kiếm,
 * làm giảm chất lượng thấy rõ. Trong phiên crawl 5.011 trang đầu tiên, lỗi
 * này tạo ra <b>23 cặp trang trùng nhau</b> chỉ khác dấu gạch chéo cuối.
 *
 * <p>Các phép chuẩn hoá áp dụng, đều là những phép <b>an toàn</b> theo
 * RFC 3986 (không làm thay đổi tài nguyên được trỏ tới):
 * <ul>
 *   <li>Bỏ fragment {@code #...} — fragment chỉ có ý nghĩa phía trình
 *       duyệt, không được gửi lên máy chủ.</li>
 *   <li>Chuyển scheme và host về chữ thường — theo RFC 3986 hai thành phần
 *       này không phân biệt hoa thường, còn đường dẫn thì CÓ phân biệt nên
 *       phải giữ nguyên.</li>
 *   <li>Bỏ cổng mặc định ({@code :80} với http, {@code :443} với https).</li>
 *   <li>Bỏ dấu {@code /} ở cuối đường dẫn. Riêng đường dẫn gốc thì rút gọn
 *       hẳn thành chuỗi rỗng, để {@code https://a.com/} và
 *       {@code https://a.com} cho ra cùng một kết quả.</li>
 * </ul>
 *
 * <p>Cố ý <b>không</b> đụng tới query string: thứ tự tham số hay việc bỏ
 * tham số theo dõi (utm_*) có thể làm thay đổi trang trả về, nên đó là phép
 * chuẩn hoá không an toàn và không được áp dụng ở đây.
 */
public final class UrlCanonicalizer {

    private UrlCanonicalizer() {
    }

    /**
     * Trả về dạng chuẩn của URL. Nếu chuỗi không phân tích được thành URI
     * hợp lệ thì trả về nguyên văn đầu vào (đã bỏ fragment) — thà giữ
     * nguyên còn hơn làm hỏng một URL vốn có thể fetch được.
     */
    public static String canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String withoutFragment = stripFragment(rawUrl.trim());
        try {
            URI uri = URI.create(withoutFragment);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return withoutFragment;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);

            StringBuilder sb = new StringBuilder(scheme).append("://").append(host);

            int port = uri.getPort();
            boolean isDefaultPort = (port == 80 && scheme.equals("http"))
                    || (port == 443 && scheme.equals("https"));
            if (port > 0 && !isDefaultPort) {
                sb.append(':').append(port);
            }

            String path = uri.getRawPath();
            if (path != null && !path.isEmpty()) {
                // Bỏ dấu "/" cuối; đường dẫn gốc "/" trở thành chuỗi rỗng.
                while (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (!path.equals("/")) {
                    sb.append(path);
                }
            }

            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                sb.append('?').append(query);
            }
            return sb.toString();
        } catch (Exception e) {
            return withoutFragment;
        }
    }

    public static String stripFragment(String url) {
        int hashIndex = url.indexOf('#');
        return hashIndex >= 0 ? url.substring(0, hashIndex) : url;
    }
}
