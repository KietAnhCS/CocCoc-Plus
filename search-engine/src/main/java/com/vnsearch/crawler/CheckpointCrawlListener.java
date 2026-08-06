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
 */
public final class CheckpointCrawlListener implements CrawlListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointCrawlListener.class);

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
        if (e.pageNumber() % everyN != 0) {
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
