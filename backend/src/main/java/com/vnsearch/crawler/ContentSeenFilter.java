package com.vnsearch.crawler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Khối "Content Seen?"</b> trong sơ đồ kiến trúc crawler — khối trước
 * đây <b>hoàn toàn không có</b> trong hệ thống.
 *
 * <p><b>Vì sao chống trùng theo URL là chưa đủ.</b> {@link UrlSeenFilter}
 * đảm bảo mỗi URL chỉ được tải một lần, và {@link UrlCanonicalizer} gom
 * được các biến thể chỉ khác nhau về dấu {@code /} cuối hay chữ hoa/thường.
 * Nhưng hai URL <i>thực sự khác nhau</i> vẫn có thể trả về cùng một nội
 * dung, và đó là chuyện phổ biến trên báo điện tử Việt Nam:
 * <ul>
 *   <li>cùng một bài nằm ở hai chuyên mục ({@code /the-thao/bai-x} và
 *       {@code /bong-da/bai-x});</li>
 *   <li>bản in / bản AMP của cùng một bài;</li>
 *   <li>cùng một bài kèm tham số theo dõi khác nhau — mà
 *       {@link UrlCanonicalizer} cố ý <b>không</b> đụng tới query string vì
 *       đó là phép chuẩn hoá không an toàn.</li>
 * </ul>
 * Hậu quả không chỉ là lãng phí băng thông: các bản sao cùng lọt vào chỉ
 * mục, cùng xuất hiện trong một trang kết quả, và làm nhiễu cả PageRank
 * (một bài được đếm nhiều lần như nhiều trang độc lập).
 *
 * <p><b>Cách làm: so vân tay nội dung.</b> Băm phần văn bản thân bài bằng
 * SHA-256 rồi đối chiếu với tập vân tay đã thấy. Lưu vân tay chứ không lưu
 * nội dung: 64 ký tự hex cho mỗi trang, không phụ thuộc trang dài bao nhiêu.
 *
 * <p><b>Chuẩn hoá trước khi băm.</b> Văn bản được hạ chữ thường và gộp mọi
 * chuỗi khoảng trắng thành một dấu cách. Không có bước này, hai bản sao chỉ
 * khác nhau ở cách xuống dòng trong HTML sẽ cho hai vân tay khác nhau và
 * phép so trở nên vô dụng.
 *
 * <p><b>Giới hạn đã biết.</b> Đây là phép phát hiện trùng <b>chính xác</b>:
 * chỉ cần khác một ký tự — một dòng "cập nhật lúc 14:05", một banner quảng
 * cáo lọt vào phần thân — là hai vân tay khác nhau. Phát hiện trùng
 * <i>gần đúng</i> cần SimHash hoặc MinHash trên tập shingle, phức tạp hơn
 * đáng kể và chưa cài ở đây. Đổi lại, phép chính xác không bao giờ vứt nhầm
 * một trang thật sự khác nội dung — sai lầm đắt hơn nhiều so với việc bỏ
 * sót một bản trùng.
 *
 * <p><b>Vì sao không dùng Bloom Filter như {@link UrlSeenFilter}.</b> Bloom
 * Filter có false positive, tức có thể báo "đã thấy" cho một trang chưa
 * từng thấy — với URL thì chỉ mất một trang, nhưng ở đây nghĩa là <b>vứt
 * hẳn một trang có nội dung riêng</b> khỏi corpus. Số trang trong một phiên
 * crawl (hàng nghìn) nhỏ hơn nhiều số URL đã gặp (hàng trăm nghìn), nên chi
 * phí lưu vân tay chính xác là chấp nhận được.
 *
 * <p>Thread-safe nhờ {@code ConcurrentHashMap.newKeySet()}, và phép kiểm tra
 * là <b>test-and-set nguyên tử</b> — xem {@link #seenBefore(String)}.
 *
 * <p>Độ phức tạp: O(độ dài văn bản) cho mỗi lần băm, O(1) cho phép tra cứu.
 */
public class ContentSeenFilter {

    private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();

    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong blankSkipped = new AtomicLong();

    /**
     * Trả về {@code true} nếu nội dung này đã từng gặp trong phiên crawl —
     * nghĩa là trang hiện tại phải bị vứt (nhánh "Yes" của khối
     * {@code Content Seen?} trong sơ đồ).
     *
     * <p>Nếu chưa từng gặp thì vân tay được ghi nhận luôn, nên hai worker
     * cùng lúc gặp hai bản sao của cùng một bài sẽ chỉ có <b>một</b> bản đi
     * tiếp: {@code Set.add} của {@code ConcurrentHashMap.newKeySet()} là
     * nguyên tử và chỉ trả về {@code true} cho đúng một luồng.
     *
     * <p><b>Văn bản rỗng được cho qua.</b> Thân bài rỗng thường là dấu hiệu
     * trích xuất thất bại (trang dựng hoàn toàn bằng JavaScript) chứ không
     * phải các trang đó giống nhau. Gom chúng làm một sẽ khiến chỉ trang
     * lỗi đầu tiên được giữ lại và mọi trang lỗi sau đó bị vứt im lặng.
     */
    public boolean seenBefore(String bodyText) {
        if (bodyText == null || bodyText.isBlank()) {
            blankSkipped.incrementAndGet();
            return false;
        }
        String fingerprint = fingerprint(bodyText);
        boolean isNew = fingerprints.add(fingerprint);
        if (!isNew) {
            duplicates.incrementAndGet();
        }
        return !isNew;
    }

    /** Vân tay SHA-256 (dạng hex) của văn bản sau khi chuẩn hoá. */
    public static String fingerprint(String text) {
        String normalized = normalize(text);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 là thuật toán bắt buộc mọi JVM phải có theo đặc tả Java.
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }

    /** Hạ chữ thường và gộp mọi chuỗi khoảng trắng thành một dấu cách. */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Số vân tay phân biệt đã ghi nhận — chính là số trang có nội dung riêng. */
    public int size() {
        return fingerprints.size();
    }

    /** Số trang bị vứt vì trùng nội dung — số liệu đưa vào báo cáo. */
    public long getDuplicateCount() {
        return duplicates.get();
    }

    /** Số trang có thân bài rỗng nên được cho qua mà không kiểm tra. */
    public long getBlankSkippedCount() {
        return blankSkipped.get();
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        ContentSeenFilter filter = new ContentSeenFilter();

        String goc = "Đội tuyển Việt Nam thắng 2-0 trong trận đấu tối qua.";
        String banSao = "Đội tuyển   Việt Nam thắng 2-0\ntrong trận đấu tối qua."; // chỉ khác khoảng trắng
        String khac = "Giá vàng trong nước tăng phiên thứ ba liên tiếp.";

        System.out.println("Trang gốc đã thấy chưa?  " + filter.seenBefore(goc));
        System.out.println("Bản sao đã thấy chưa?    " + filter.seenBefore(banSao) + "  <- bị phát hiện trùng");
        System.out.println("Bài khác đã thấy chưa?   " + filter.seenBefore(khac));
        System.out.println("Số nội dung phân biệt    : " + filter.size());
        System.out.println("Số trang trùng bị vứt    : " + filter.getDuplicateCount());
    }
}
