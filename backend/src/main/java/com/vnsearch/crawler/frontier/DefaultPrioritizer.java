package com.vnsearch.crawler.frontier;

/**
 * Chính sách ưu tiên mặc định của VnSearch — cài đặt {@link Prioritizer}.
 *
 * <p><b>Mức bắt đầu bằng chính độ sâu BFS</b>, rồi được nâng lên bởi hai tín
 * hiệu, cuối cùng kẹp vào khoảng hợp lệ:
 *
 * <pre>
 *   level = depth
 *   level-- neu host ket thuc bang ".vn"        (yeu cau de bai)
 *   level-- neu knownBacklinks >= 5             (trang duoc tro toi nhieu)
 *   level  = clamp(level, 0, levels-1)
 * </pre>
 *
 * <p><b>Vì sao lấy độ sâu làm gốc.</b> Crawler duyệt theo BFS, nên độ sâu đã
 * là thước đo "gần seed tới đâu" — mà trang gần seed thường là trang chủ và
 * trang chuyên mục, tức những trang quan trọng nhất. Hai tín hiệu còn lại chỉ
 * <i>nâng một bậc</i> chứ không lật ngược thứ tự: một trang sâu 5 lớp dù có
 * đuôi {@code .vn} và nhiều backlink vẫn xếp sau một trang sâu 1 lớp.
 *
 * <p><b>Vì sao dùng phép cộng bậc chứ không phải điểm số.</b> Bản trước cộng
 * các trọng số {@code double} tuỳ chọn ({@code -2*depth + 0,5*backlinks + 5})
 * rồi so sánh. Cách đó phải giải thích ba hằng số ma thuật, và tệ hơn: nó cho
 * phép backlink lấn át hoàn toàn độ sâu ({@code 50 backlink = 25 điểm} đủ để
 * một trang sâu 12 lớp vượt lên trên seed). Đếm theo bậc thì mỗi tín hiệu chỉ
 * đáng đúng một bậc, giới hạn ảnh hưởng của nó ngay trong định nghĩa.
 *
 * <p>Bất biến và không giữ trạng thái, nên dùng chung được cho mọi luồng.
 */
public final class DefaultPrioritizer implements Prioritizer {

    /** Năm mức là đủ: crawl thực tế hiếm khi vượt quá độ sâu 3–4. */
    public static final int DEFAULT_LEVELS = 5;

    /** Từ ngưỡng này trở lên thì coi là "được trỏ tới nhiều" và nâng một bậc. */
    public static final int BACKLINK_BOOST_THRESHOLD = 5;

    private final int levels;

    public DefaultPrioritizer() {
        this(DEFAULT_LEVELS);
    }

    public DefaultPrioritizer(int levels) {
        if (levels <= 0) {
            throw new IllegalArgumentException("levels phải > 0, nhận được: " + levels);
        }
        this.levels = levels;
    }

    @Override
    public int levels() {
        return levels;
    }

    @Override
    public int levelOf(String url, String host, int depth, int knownBacklinks) {
        int level = depth;
        if (host != null && host.endsWith(".vn")) {
            level--;
        }
        if (knownBacklinks >= BACKLINK_BOOST_THRESHOLD) {
            level--;
        }
        return Math.max(0, Math.min(level, levels - 1));
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        DefaultPrioritizer prioritizer = new DefaultPrioritizer();
        System.out.println("Số mức ưu tiên: " + prioritizer.levels() + " (0 = cao nhất)");
        System.out.println("seed .vn        : " + prioritizer.levelOf("https://a.vn", "a.vn", 0, 10));
        System.out.println("sâu 1, .vn      : " + prioritizer.levelOf("https://a.vn/x", "a.vn", 1, 0));
        System.out.println("sâu 1, .com     : " + prioritizer.levelOf("https://a.com/x", "a.com", 1, 0));
        System.out.println("sâu 1, backlink : " + prioritizer.levelOf("https://a.com/y", "a.com", 1, 40));
        System.out.println("sâu 9           : " + prioritizer.levelOf("https://a.com/z", "a.com", 9, 0));
    }
}
