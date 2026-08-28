package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;

import java.util.ArrayList;
import java.util.Collection;
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
 * rồi <i>không ai giữ lại</i>: {@code CrawlAnalyticsService} đếm nó xong là
 * vứt bản ghi. Nghĩa là hệ thống biết "có 4.213 ảnh" nhưng không trả lời được
 * "ảnh nào nằm ở trang nào" — mà đó chính là câu hỏi giao diện cần.
 *
 * <h2>MỘT ẢNH cho mỗi trang</h2>
 *
 * <p>Kho này <b>không</b> giữ mọi ảnh của một trang. Nó giữ đúng một tấm — tấm
 * mà {@link ImageQuality} chấm là có khả năng cao nhất là ảnh chính của bài.
 *
 * <p><b>Vì sao.</b> Đo trên corpus thật (1.028 trang): giữ tất cả cho ra 25.707
 * ảnh trên 1.013 trang, tức trung bình 25 ảnh/trang, và tệp trên đĩa là 10,7 MB.
 * Nhưng 24 trong 25 tấm đó là logo toà soạn, icon chia sẻ, và ảnh thu nhỏ của
 * những bài "đọc thêm" ở chân trang. Hệ quả nhìn thấy được ở giao diện:
 *
 * <ul>
 *   <li>Lưới ảnh đầy logo. Heuristic "có {@code alt} nghĩa là ảnh nội dung"
 *       không cứu được, vì logo cũng có {@code alt} — đo được một tấm
 *       {@code 100×42} mang {@code alt="Fica"} nằm ở đầu kết quả.</li>
 *   <li>Một tên miền chiếm 38–52% lưới, vì một trang nhiều ảnh nuốt trọn phần
 *       đầu danh sách.</li>
 * </ul>
 *
 * <p>Giữ một ảnh mỗi trang giải quyết cả hai cùng lúc, và giảm <b>96,1%</b> dung
 * lượng: 1.013 bản ghi thay vì 25.707, khoảng 0,4 MB thay vì 10,7 MB.
 *
 * <p><b>Cái mất đi:</b> một trang thư viện ảnh có 30 tấm đáng xem thì 29 tấm
 * không vào chỉ mục. Đây là đánh đổi có chủ ý cho một máy tìm kiếm <i>trang
 * web</i>: người dùng bấm vào ảnh là để tới TRANG chứa nó, nên mỗi trang xuất
 * hiện một lần là đủ. Muốn đổi hướng thì {@link ImageQuality} là chỗ duy nhất
 * phải sửa.
 *
 * <h2>Chọn dần, không cần thấy hết</h2>
 *
 * <p>Ảnh tới lần lượt qua bus, không thành lô. Nên kho không thể "xem hết rồi
 * chọn" — nó giữ tấm tốt nhất ĐANG CÓ và thay khi gặp tấm tốt hơn. Kết quả cuối
 * cùng giống hệt như khi chọn trên toàn bộ danh sách, vì
 * {@link ImageQuality#compare} là một quan hệ thứ tự toàn phần và phép chọn cực
 * đại thì không phụ thuộc thứ tự duyệt.
 *
 * <p>Điều đó quan trọng ở chế độ Kafka, nơi thứ tự thông điệp trong một phân
 * hoạch được bảo đảm nhưng thứ tự GIỮA các phân hoạch thì không.
 *
 * <h2>Trong bộ nhớ, có ghi ra đĩa</h2>
 *
 * <p>Bảng băm trong bộ nhớ, khoá theo URL trang; phần ghi/đọc đĩa do
 * {@link ImageStorage} lo. {@link #MAX_PAGES} chặn bảng lớn tuyến tính theo
 * corpus — với một ảnh mỗi trang thì đây là trần duy nhất còn cần.
 *
 * <p>Thread-safe: được ghi từ nhiều worker của crawler và từ nhiều luồng
 * consumer Kafka cùng lúc.
 */
public class ImageStore {

    /** Số trang tối đa được theo dõi — xem Javadoc lớp. */
    public static final int MAX_PAGES = 50_000;

    /**
     * trang -&gt; ảnh đại diện của trang đó.
     *
     * <p>Trước đây đây là {@code Map<String, Map<String, ImageFound>>} — mọi ảnh
     * của mọi trang. Rút xuống một ảnh làm biến mất luôn cả một lớp lỗi mà cấu
     * trúc cũ phải chống đỡ bằng {@code LinkedHashMap} bọc
     * {@code synchronizedMap}: thứ tự ảnh TRONG một trang không còn tồn tại thì
     * cũng không còn chuyện phân trang đọc phải hai thứ tự khác nhau ở hai lần
     * gọi.
     */
    private final Map<String, ImageFound> byPage = new ConcurrentHashMap<>();

    private final AtomicLong pagesAdded = new AtomicLong();
    private final AtomicLong replaced = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong droppedPageLimit = new AtomicLong();

    /**
     * Ghi nhận một ảnh: giữ nó nếu trang chưa có ảnh nào, hoặc nếu nó tốt hơn
     * ảnh đang giữ.
     *
     * @return {@code true} nếu ảnh này trở thành ảnh đại diện của trang — kể cả
     *         khi nó thay thế một ảnh đã có
     */
    public boolean add(ImageFound image) {
        if (image == null) {
            return false;
        }
        String pageUrl = image.pageUrl();

        // Kiểm tra trần TRƯỚC khi tạo mục mới. Có một cửa sổ đua nhỏ ở đây —
        // hai luồng cùng thấy size() == MAX-1 và cùng thêm — nên bảng có thể
        // vượt trần vài mục. Chấp nhận được: đây là kho thống kê phục vụ hiển
        // thị, không phải một bất biến an toàn, và một khoá quanh đường nóng
        // này đắt hơn nhiều so với vài mục thừa.
        if (!byPage.containsKey(pageUrl) && byPage.size() >= MAX_PAGES) {
            droppedPageLimit.incrementAndGet();
            return false;
        }

        // `compute` là NGUYÊN TỬ trên từng khoá của ConcurrentHashMap. Viết
        // thành "đọc rồi so rồi ghi" thì hai luồng cùng thấy ảnh cũ, cùng kết
        // luận mình tốt hơn, và tấm ghi sau đè tấm ghi trước — kết quả phụ
        // thuộc thời điểm, tức là không tái lập được.
        //
        // Mảng một phần tử để mang kết cục ra khỏi lambda: biến cục bộ thường
        // phải là final mới dùng được bên trong.
        boolean[] won = new boolean[1];
        byPage.compute(pageUrl, (url, current) -> {
            if (current == null) {
                won[0] = true;
                pagesAdded.incrementAndGet();
                return image;
            }
            if (ImageQuality.isBetter(image, current)) {
                won[0] = true;
                replaced.incrementAndGet();
                return image;
            }
            rejected.incrementAndGet();
            return current;
        });
        return won[0];
    }

    /**
     * Ảnh đại diện của một trang — danh sách <b>không quá một phần tử</b>.
     *
     * <p>Vẫn trả về {@code List} chứ không phải {@code Optional} để
     * {@code FeedController} không phải sửa: nó đang gọi {@code images.get(0)}
     * sau khi kiểm tra rỗng, và ngữ nghĩa của lời gọi đó không đổi — chỉ có
     * điều bây giờ phần tử thứ nhất là ảnh TỐT NHẤT chứ không phải ảnh đầu tiên
     * theo thứ tự DOM.
     *
     * <p>Khác biệt đó có ý nghĩa: đo trên corpus thật, ảnh đầu DOM chỉ là ảnh
     * lớn nhất của trang trong 19% số trang — phần còn lại nó là logo hoặc icon
     * nằm trong {@code <header>}.
     */
    public List<ImageFound> forPage(String pageUrl) {
        ImageFound image = byPage.get(pageUrl);
        return image == null ? List.of() : List.of(image);
    }

    /**
     * Ảnh của nhiều trang, giữ <b>đúng thứ tự các trang được truyền vào</b>.
     *
     * <p>Thứ tự đó là thứ tự xếp hạng của máy tìm kiếm, nên giữ nguyên nó nghĩa
     * là ảnh của trang liên quan nhất hiện trước — tab "Hình ảnh" thừa hưởng
     * luôn chất lượng xếp hạng của tab "Web" mà không cần một mô hình xếp hạng
     * riêng cho ảnh.
     *
     * <p>Vì mỗi trang góp đúng một ảnh, số ảnh trả về không bao giờ vượt quá số
     * trang truyền vào — {@code ImageSearchController} phải quét đủ nhiều trang
     * thì lưới mới có đủ ảnh để cuộn.
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
            if (out.size() >= limit) {
                return out;
            }
            ImageFound image = byPage.get(pageUrl);
            // Cùng một ảnh có thể là ảnh đại diện của nhiều trang (ảnh chuyên
            // mục dùng chung). Hiện nó một lần là đủ.
            if (image != null && seen.add(image.imageUrl())) {
                out.add(image);
            }
        }
        return out;
    }

    /**
     * Toàn bộ ảnh trong kho — đúng một ảnh cho mỗi trang.
     *
     * <p>Dùng để ghi ra đĩa ({@link ImageStorage#saveToJson}). Thứ tự là thứ tự
     * của {@code ConcurrentHashMap}, tức không xác định, và điều đó không sao:
     * người đọc tệp đều gom lại theo {@code pageUrl}, còn thứ tự phục vụ tìm
     * kiếm do {@link #forPages} áp đặt lại theo xếp hạng.
     *
     * <p>Trả về bản SAO — kho được ghi từ nhiều worker trong lúc crawl, nên đưa
     * tham chiếu sống ra ngoài là mời một {@code ConcurrentModificationException}
     * vào luồng ghi điểm kiểm tra.
     */
    public List<ImageFound> all() {
        return new ArrayList<>(byPage.values());
    }

    /**
     * Nạp một loạt ảnh vào kho — đường ngược của {@link #all()}.
     *
     * <p>Đi qua đúng {@link #add} chứ không đổ thẳng vào bảng, nên mọi bất biến
     * vẫn được giữ. Điều này còn cho một hiệu ứng có ích khi nạp một tệp ghi
     * bằng bản mã CŨ (nhiều ảnh mỗi trang): tệp đó tự động được rút xuống một
     * ảnh mỗi trang, và ảnh được giữ là ảnh tốt nhất trong số đó — không cần
     * bước chuyển đổi riêng nào.
     *
     * @return số trang có ảnh đại diện MỚI hoặc ĐƯỢC THAY sau lần nạp này
     */
    public int addAll(Collection<ImageFound> images) {
        if (images == null) {
            return 0;
        }
        int changed = 0;
        for (ImageFound image : images) {
            if (add(image)) {
                changed++;
            }
        }
        return changed;
    }

    public int pageCount() {
        return byPage.size();
    }

    /** Số ảnh đang giữ — bằng đúng số trang, vì mỗi trang một ảnh. */
    public long imageCount() {
        return byPage.size();
    }

    /** Số lần một ảnh tốt hơn thay thế ảnh đang giữ. */
    public long getReplacedCount() {
        return replaced.get();
    }

    /** Số ảnh bị bỏ vì không tốt hơn ảnh đang giữ. */
    public long getRejectedCount() {
        return rejected.get();
    }

    public long getDroppedByPageLimitCount() {
        return droppedPageLimit.get();
    }

    /** Xoá sạch — dùng khi lập chỉ mục lại từ đầu. */
    public void clear() {
        byPage.clear();
    }

    /** Bản tóm tắt cho {@code /api/admin/stats}. */
    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pagesWithImages", byPage.size());
        stats.put("images", byPage.size());
        // Số ảnh ĐÃ XÉT, không phải số ảnh đang giữ. Chênh lệch giữa hai con số
        // cho biết bộ lọc đang loại bao nhiêu — nếu `rejected` bằng 0 trong một
        // phiên crawl thật thì gần như chắc chắn ImageQuality không được gọi.
        stats.put("candidatesRejected", rejected.get());
        stats.put("replaced", replaced.get());
        stats.put("pagesAdded", pagesAdded.get());
        stats.put("droppedPageLimit", droppedPageLimit.get());
        return stats;
    }
}
