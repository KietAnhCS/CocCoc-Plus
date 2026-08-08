package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link CrawlListener} ghi corpus xuống đĩa ĐỊNH KỲ giữa phiên crawl, thay vì
 * chỉ một lần duy nhất sau khi crawl kết thúc.
 *
 * <p><b>Vấn đề nó giải quyết.</b> {@link ContentStorage} giữ toàn bộ nội dung
 * trong bộ nhớ và chỉ được ghi ra tệp sau khi {@link CrawlerService#crawl}
 * trả về. Nghĩa là trước thời điểm đó, mọi trang đã tải chỉ tồn tại trong RAM
 * của một tiến trình: một lần Ctrl+C, một lần mất điện, một lần
 * {@code OutOfMemoryError} là mất trắng — không phải mất tệp, mà mất cả công
 * tải về. Với phiên 2.000 trang chạy trong 70 giây thì rủi ro nhỏ; với phiên
 * 20.000 trang chạy 15 phút thì đó là 15 phút băng thông và 20.000 lượt gõ vào
 * máy chủ người khác bị vứt đi, và phải gõ lại từ đầu.
 *
 * <p>Ghi điểm kiểm tra biến mất mát từ "toàn bộ phiên" thành "phần crawl được
 * kể từ điểm kiểm tra gần nhất". Kết hợp với việc crawl nối tiếp
 * ({@link CrawlerService#crawl(List, CrawlConfig, List)}), phiên sau nạp lại
 * đúng tệp này và đi tiếp — không tải lại trang nào đã có.
 *
 * <p><b>Ghi trên luồng riêng, và BỎ QUA nếu lần ghi trước chưa xong.</b> Serial
 * hoá 25 MB JSON mất khoảng một giây. Ghi ngay trong luồng gọi sẽ treo một
 * worker suốt thời gian đó — và tệ hơn, nếu chu kỳ ghi ngắn hơn thời gian ghi
 * thì các lần ghi dồn lại thành hàng đợi càng lúc càng dài, crawler dần biến
 * thành chương trình chuyên ghi đĩa. Bỏ qua lần ghi khi lần trước còn đang
 * chạy là lựa chọn đúng ở đây: điểm kiểm tra chỉ cần "đủ gần đây", không cần
 * "đủ mọi lần".
 *
 * <p>Bản chụp lấy qua {@link Supplier} chứ không phải danh sách truyền sẵn, vì
 * nội dung cần ghi là trạng thái TẠI LÚC ghi, không phải lúc đăng ký listener.
 *
 * <p><b>Chu kỳ ghi GIÃN DẦN theo kích thước corpus.</b> Mỗi lần ghi là ghi lại
 * <i>toàn bộ</i> corpus, nên chi phí một lần ghi tỉ lệ với số tài liệu đang có.
 * Ghi đều đặn mỗi {@code everyN} trang thì tổng chi phí cả phiên là
 * {@code O(n²/everyN)} — và đó không phải lo xa, nó đã đo được: thông lượng
 * crawl <b>tụt 37%</b> giữa phiên (38 → 24 trang/s), vì ở mốc 30.000 tài liệu
 * mỗi lần ghi đã nặng ~350 MB mà vẫn ghi 10 giây một lần.
 *
 * <pre>
 *   Chu kỳ CỐ ĐỊNH 250 trang          Chu kỳ GIÃN DẦN (+25% mỗi lần)
 *   ├─ 120 lần ghi cho 30k trang      ├─ ~21 lần ghi cho 30k trang
 *   ├─ mỗi lần một nặng hơn           ├─ khoảng cách giãn cùng nhịp với
 *   └─ tổng ≈ O(n²/everyN)            │  chi phí, nên tổng ≈ O(n)
 *                                      └─ giảm ~12 lần lượng byte ghi
 * </pre>
 *
 * <p>Cái mất đi là gì? Khi corpus lớn, khoảng "có thể mất" cũng lớn hơn: ở mốc
 * 30.000 trang, điểm kiểm tra cách nhau ~6.000 trang thay vì 250. Đánh đổi này
 * <b>đúng hướng</b> — điểm kiểm tra sinh ra để khỏi mất <i>cả phiên</i>, và mất
 * 20% cuối vẫn tốt hơn nhiều so với việc crawl chậm đi 37% khiến phiên dài thêm
 * và rủi ro hỏng giữa chừng tăng theo.
 */
public final class CheckpointCrawlListener implements CrawlListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointCrawlListener.class);

    /**
     * Tỉ lệ giãn: chỉ ghi lại khi corpus đã lớn thêm 25% so với lần ghi trước.
     *
     * <p>Chọn 25% vì nó cho tổng chi phí ghi cả phiên xấp xỉ 5 lần kích thước
     * corpus cuối — chấp nhận được — trong khi vẫn đủ dày để không mất quá nhiều
     * khi hỏng. Số nhỏ hơn thì ghi dày hơn và chậm hơn; số lớn hơn thì lưới an
     * toàn thưa dần tới mức gần như không còn tác dụng.
     */
    private static final double GROWTH_RATIO = 0.25;

    private final Supplier<List<WebDocument>> snapshot;
    private final String path;
    private final int everyN;

    /** Chặn hai lần ghi chồng nhau — xem ghi chú về dồn hàng đợi ở javadoc lớp. */
    private final AtomicBoolean writing = new AtomicBoolean();

    /**
     * Một luồng nền duy nhất, đặt daemon: nếu phiên crawl kết thúc bất thường,
     * luồng này không được phép giữ cho JVM sống mãi.
     */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "crawl-checkpoint");
        t.setDaemon(true);
        return t;
    });

    private volatile int lastCheckpointPages;

    /**
     * @param snapshot nguồn lấy bản chụp corpus tại thời điểm ghi
     * @param path     tệp đích — TRÙNG với tệp đầu ra cuối phiên, để lần chạy
     *                 sau nạp lại được mà không cần biết có điểm kiểm tra hay không
     * @param everyN   ghi sau mỗi bấy nhiêu trang
     */
    public CheckpointCrawlListener(Supplier<List<WebDocument>> snapshot, String path, int everyN) {
        this.snapshot = snapshot;
        this.path = path;
        this.everyN = Math.max(1, everyN);
    }

    @Override
    public void onPageCrawled(CrawlEvent e) {
        if (e.pageNumber() % everyN != 0
                || !isDueForCheckpoint(e.pageNumber(), lastCheckpointPages, everyN)) {
            return;
        }
        // compareAndSet chứ không phải "kiểm tra rồi đặt": onPageCrawled chạy
        // đồng thời trên mọi worker thread, nên hai luồng có thể cùng vượt qua
        // một phép kiểm tra không nguyên tử và cùng xếp một lần ghi.
        if (!writing.compareAndSet(false, true)) {
            log.debug("Bỏ qua điểm kiểm tra ở trang {}: lần ghi trước chưa xong", e.pageNumber());
            return;
        }
        int pages = e.pageNumber();
        writer.submit(() -> {
            try {
                write(pages);
            } finally {
                writing.set(false);
            }
        });
    }

    /**
     * Ghi nốt lần cuối và chờ cho xong. Không có bước này, phần crawl được sau
     * điểm kiểm tra cuối cùng sẽ nằm lại trong luồng nền và mất khi JVM thoát —
     * đúng thứ lớp này sinh ra để ngăn.
     */
    @Override
    public void onFinished(int totalPages, long elapsedMs) {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.MINUTES)) {
                log.warn("Điểm kiểm tra cuối chưa ghi xong sau 2 phút, bỏ dở");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Đã đến lúc ghi điểm kiểm tra chưa?
     *
     * <p>Điều kiện: số trang crawl thêm kể từ lần ghi trước phải đạt ít nhất
     * {@link #GROWTH_RATIO} của corpus hiện có. Nhờ vậy khoảng cách giữa hai lần
     * ghi giãn ra <b>cùng nhịp</b> với chi phí mỗi lần ghi — xem javadoc lớp.
     *
     * <p>Lần ghi đầu tiên luôn được phép ({@code lastCheckpointPages == 0}), nếu
     * không thì phiên crawl ngắn sẽ chẳng có điểm kiểm tra nào.
     *
     * <p>Viết thành hàm <b>tĩnh, thuần</b> (chỉ phụ thuộc tham số, không đọc
     * trạng thái nào) để kiểm thử được trực tiếp. Kiểm thử qua
     * {@link #onPageCrawled} thì phải dựng luồng nền và tệp tạm — nhiều công sức
     * cho một phép so sánh số học.
     *
     * @param pages          số trang đã crawl tới thời điểm này
     * @param lastCheckpoint số trang tại điểm kiểm tra thành công gần nhất
     * @param everyN         khoảng cách tối thiểu giữa hai lần ghi
     */
    static boolean isDueForCheckpoint(int pages, int lastCheckpoint, int everyN) {
        int grown = pages - lastCheckpoint;
        return grown >= Math.max(everyN, (int) (lastCheckpoint * GROWTH_RATIO));
    }

    private void write(int pages) {
        try {
            long start = System.currentTimeMillis();
            List<WebDocument> docs = snapshot.get();
            ContentStorage.saveToJson(docs, path);
            lastCheckpointPages = pages;
            log.info("Điểm kiểm tra: {} tài liệu -> {} ({} ms)",
                    docs.size(), path, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // Không ném ra ngoài: hỏng việc ghi điểm kiểm tra là mất một lưới an
            // toàn, còn ném ngoại lệ lên luồng worker sẽ làm hỏng chính phiên
            // crawl mà lưới đó đang bảo vệ.
            log.warn("Không ghi được điểm kiểm tra vào {}: {}", path, e.toString());
        }
    }

    /** Số trang tại điểm kiểm tra ghi thành công gần nhất — dùng cho báo cáo. */
    public int getLastCheckpointPages() {
        return lastCheckpointPages;
    }
}
