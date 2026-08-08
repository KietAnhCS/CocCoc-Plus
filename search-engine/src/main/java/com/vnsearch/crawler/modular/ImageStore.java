package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kho ảnh mà {@link ImageDownloadService} tìm được — nguồn dữ liệu cho tab
 * "Hình ảnh" ở giao diện.
 *
 * <p><b>Vì sao cần lớp này.</b> Trước đó {@code ImageFound} được phát lên bus
 * rồi <i>không ai giữ lại</i>: {@link CrawlAnalyticsService} đếm nó xong là
 * vứt bản ghi. Nghĩa là hệ thống biết "có 4.213 ảnh" nhưng không trả lời được
 * "ảnh nào nằm ở trang nào" — mà đó chính là câu hỏi giao diện cần.
 *
 * <h2>Trong bộ nhớ, không phải cơ sở dữ liệu</h2>
 *
 * <p>Cố ý bắt chước {@link com.vnsearch.crawler.ContentStorage}: một
 * {@code ConcurrentHashMap} khoá theo URL trang. Lý do giống hệt lý do lớp kia
 * đã ghi — ở quy mô đồ án thì vài chục nghìn bản ghi vừa bộ nhớ, và cách này
 * tránh được chi phí ghi đĩa trên đường đi nóng của crawler.
 *
 * <p>Đổi sang lưu bền thì chỉ cần thay lớp này; phần còn lại không đổi vì
 * chúng chỉ biết tới hai phương thức {@link #add} và {@link #forPage}.
 *
 * <h2>Hai trần chặn, hai bài toán khác nhau</h2>
 *
 * <table border="1">
 *   <caption>Vì sao một trần là không đủ</caption>
 *   <tr><th>Trần</th><th>Chặn cái gì</th></tr>
 *   <tr>
 *     <td>{@link #MAX_PAGES}</td>
 *     <td>Số <b>trang</b> được theo dõi. Một phiên crawl 30.000 trang mà
 *         không chặn thì bảng này lớn tuyến tính theo corpus.</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #MAX_IMAGES_PER_PAGE}</td>
 *     <td>Số ảnh <b>mỗi trang</b>. {@code ImageDownloadService} đã chặn ở
 *         phía nó, nhưng trần đó là cấu hình còn trần này là bất biến của
 *         kho — hai tầng bảo vệ cho một thứ do <i>trang bên ngoài</i> quyết
 *         định độ lớn.</td>
 *   </tr>
 * </table>
 *
 * <h2>Khử trùng theo địa chỉ ảnh</h2>
 *
 * <p>Một trang thường trỏ tới cùng một ảnh từ nhiều chỗ (bản thường, bản
 * {@code srcset}, ảnh nền). {@code ImageDownloadService} đã khử trùng trong
 * phạm vi một lần xử lý, nhưng cùng một trang có thể được xử lý lại ở phiên
 * crawl sau — nên kho vẫn phải tự khử.
 *
 * <p><b>Khử theo {@code imageUrl}, không theo {@code contentHash}.</b> Vân tay
 * nội dung chỉ có khi bật tải ảnh, mà mặc định là tắt — dùng nó làm khoá thì
 * ở cấu hình mặc định mọi ảnh đều có khoá {@code null} và gộp thành một.
 *
 * <p>Thread-safe: được ghi từ nhiều worker của crawler và từ nhiều luồng
 * consumer Kafka cùng lúc.
 */
public class ImageStore {

    /** Số trang tối đa được theo dõi — xem Javadoc lớp. */
    public static final int MAX_PAGES = 50_000;

    /** Số ảnh tối đa giữ cho mỗi trang. */
    public static final int MAX_IMAGES_PER_PAGE = 60;

    /**
     * trang -&gt; (địa chỉ ảnh -&gt; bản ghi), <b>giữ nguyên thứ tự xuất hiện</b>.
     *
     * <h3>Vì sao map bên trong phải giữ thứ tự</h3>
     *
     * <p>Trước đây đây là {@code ConcurrentHashMap}, và thứ tự nó trả về là
     * <b>không xác định</b> — có thể khác nhau giữa hai lần đọc. Với một lời
     * gọi lấy toàn bộ thì không sao, nhưng nó làm <b>phân trang hỏng ngầm</b>:
     *
     * <pre>
     *   lần 1: lấy ảnh 0..7   -> [A B C D E F G H]
     *   lần 2: lấy ảnh 8..15  -> thứ tự đã đổi, C và F xuất hiện LẠI,
     *                            còn J và K không bao giờ được trả về
     * </pre>
     *
     * <p>Người dùng thấy ảnh trùng khi cuộn xuống, và một phần ảnh biến mất —
     * không có lỗi nào, chỉ là kết quả sai một cách khó tả.
     *
     * <h3>Vì sao thứ tự xuất hiện là thứ tự ĐÚNG</h3>
     *
     * <p>Không chỉ để phân trang chạy được — nó còn là thứ tự tốt nhất về mặt
     * nội dung. Ảnh đầu tiên trong DOM của một bài báo gần như luôn là ảnh
     * chính; ảnh cuối là ảnh của các bài "đọc thêm" ở chân trang. Sắp theo thứ
     * tự DOM nghĩa là ảnh quan trọng nhất của mỗi trang hiện trước.
     *
     * <p>{@code LinkedHashMap} không thread-safe nên phải bọc
     * {@code synchronizedMap}. Chi phí chấp nhận được: khoá chỉ bao một phép
     * chèn vào map, không có thao tác vào/ra nào bên trong.
     */
    private final Map<String, Map<String, ImageFound>> byPage = new ConcurrentHashMap<>();

    private final AtomicLong added = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong droppedPageLimit = new AtomicLong();
    private final AtomicLong droppedPerPageLimit = new AtomicLong();

    /**
     * Ghi nhận một ảnh.
     *
     * @return {@code true} nếu ảnh thực sự được thêm mới
     */
    public boolean add(ImageFound image) {
        if (image == null) {
            return false;
        }
        String pageUrl = image.pageUrl();

        Map<String, ImageFound> images = byPage.get(pageUrl);
        if (images == null) {
            // Kiểm tra trần TRƯỚC khi tạo mục mới. Có một cửa sổ đua nhỏ ở đây
            // — hai luồng cùng thấy size() == MAX-1 và cùng thêm — nên bảng có
            // thể vượt trần vài mục. Chấp nhận được: đây là kho thống kê phục
            // vụ hiển thị, không phải một bất biến an toàn, và một khoá quanh
            // đường nóng này đắt hơn nhiều so với vài mục thừa. Cùng đánh đổi
            // mà CrawlAnalyticsService đã chọn cho bảng host.
            if (byPage.size() >= MAX_PAGES) {
                droppedPageLimit.incrementAndGet();
                return false;
            }
            images = byPage.computeIfAbsent(pageUrl,
                    url -> java.util.Collections.synchronizedMap(new LinkedHashMap<>()));
        }

        if (images.size() >= MAX_IMAGES_PER_PAGE && !images.containsKey(image.imageUrl())) {
            droppedPerPageLimit.incrementAndGet();
            return false;
        }

        // putIfAbsent: giữ bản GHI ĐẦU TIÊN. Nếu một phiên sau tìm lại cùng ảnh
        // nhưng lần này có kèm contentHash (đã bật tải), bản có dữ liệu hơn sẽ
        // bị bỏ. Đổi lại là tính ổn định: kết quả hiển thị không đổi giữa hai
        // lần crawl. Với một kho phục vụ giao diện thì ổn định quan trọng hơn.
        boolean isNew = images.putIfAbsent(image.imageUrl(), image) == null;
        if (isNew) {
            added.incrementAndGet();
        } else {
            duplicates.incrementAndGet();
        }
        return isNew;
    }

    /**
     * Ảnh của một trang, <b>theo đúng thứ tự xuất hiện trong DOM</b>.
     *
     * <p>Sao chép trong khối {@code synchronized}: {@code synchronizedMap} chỉ
     * đồng bộ từng phương thức, còn việc DUYỆT thì người gọi phải tự khoá —
     * nếu không, một lần ghi xen vào giữa lúc duyệt sẽ ném
     * {@code ConcurrentModificationException}. Đây là bẫy kinh điển của
     * {@code Collections.synchronizedMap}, và nó chỉ nổ dưới tải.
     */
    public List<ImageFound> forPage(String pageUrl) {
        Map<String, ImageFound> images = byPage.get(pageUrl);
        if (images == null) {
            return List.of();
        }
        synchronized (images) {
            return new ArrayList<>(images.values());
        }
    }

    /**
     * Ảnh của nhiều trang, giữ <b>đúng thứ tự các trang được truyền vào</b>.
     *
     * <p>Thứ tự đó là thứ tự xếp hạng của máy tìm kiếm, nên giữ nguyên nó
     * nghĩa là ảnh của trang liên quan nhất hiện trước — tab "Hình ảnh" thừa
     * hưởng luôn chất lượng xếp hạng của tab "Web" mà không cần một mô hình
     * xếp hạng riêng cho ảnh.
     *
     * @param limit số ảnh tối đa trả về
     */
    public List<ImageFound> forPages(List<String> pageUrls, int limit) {
        List<ImageFound> out = new ArrayList<>();
        if (pageUrls == null || limit <= 0) {
            return out;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String pageUrl : pageUrls) {
            for (ImageFound image : forPage(pageUrl)) {
                if (out.size() >= limit) {
                    return out;
                }
                // Cùng một ảnh có thể xuất hiện trên nhiều trang trong kết quả
                // (logo, ảnh chuyên mục). Hiện nó một lần là đủ.
                if (seen.add(image.imageUrl())) {
                    out.add(image);
                }
            }
        }
        return out;
    }

    public int pageCount() {
        return byPage.size();
    }

    public long imageCount() {
        return added.get();
    }

    public long getDuplicateCount() {
        return duplicates.get();
    }

    public long getDroppedByPageLimitCount() {
        return droppedPageLimit.get();
    }

    public long getDroppedByPerPageLimitCount() {
        return droppedPerPageLimit.get();
    }

    /** Xoá sạch — dùng khi lập chỉ mục lại từ đầu. */
    public void clear() {
        byPage.clear();
    }

    /** Bản tóm tắt cho {@code /api/admin/stats}. */
    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pagesWithImages", byPage.size());
        stats.put("images", added.get());
        stats.put("duplicates", duplicates.get());
        stats.put("droppedPageLimit", droppedPageLimit.get());
        stats.put("droppedPerPageLimit", droppedPerPageLimit.get());
        return stats;
    }
}
