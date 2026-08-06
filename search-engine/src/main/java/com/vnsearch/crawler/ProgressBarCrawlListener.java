package com.vnsearch.crawler;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link CrawlListener} vẽ thanh tiến trình cập nhật TẠI CHỖ trên terminal —
 * dành cho người ngồi nhìn một phiên crawl dài chạy.
 *
 * <p><b>Vì sao cần thêm listener này khi đã có {@link ConsoleCrawlListener}.</b>
 * Hai cái phục vụ hai mục đích khác nhau. {@code ConsoleCrawlListener} ghi log
 * SLF4J: mỗi trang một dòng, giữ lại được, phân tích sau được, nhưng với 2.000
 * trang thì đó là 80 dòng cuộn qua màn hình và người xem không trả lời được câu
 * hỏi duy nhất họ quan tâm — "còn bao lâu nữa?". Lớp này trả lời đúng câu đó:
 * một dòng duy nhất, phần trăm, tốc độ, và thời gian còn lại ước tính.
 *
 * <p>Đây chính là lợi ích mà Observer pattern hứa hẹn trong {@link CrawlListener}:
 * thêm một cách quan sát mới mà không sửa một dòng nào trong
 * {@link CrawlerService}. Hai listener chạy song song cũng được — xem ghi chú về
 * xung đột ở {@link #onError} bên dưới.
 *
 * <p><b>Ba chế độ hiển thị</b>, tự dò chứ không bắt người dùng cấu hình:
 * <ul>
 *   <li><b>Thanh tiến trình</b> khi stdout là terminal thật. Vẽ lại tối đa 10
 *       lần/giây — mắt người không phân biệt được nhanh hơn thế, còn terminal
 *       thì phải trả giá cho từng lần vẽ.</li>
 *   <li><b>Từng dòng mốc</b> khi output bị chuyển hướng vào file hoặc pipe
 *       (CI, {@code > log.txt}). Ký tự {@code \r} trong một file log chỉ tạo ra
 *       rác không đọc được, nên ở đây phải in dòng rời.</li>
 *   <li><b>ASCII thay khối Unicode</b> khi bảng mã của stdout không mã hóa nổi
 *       ký tự khối. Không kiểm tra thì trên console dùng bảng mã cũ, thanh tiến
 *       trình hiện ra thành một hàng dấu hỏi.</li>
 * </ul>
 * Ép chế độ bằng {@code -Dcrawl.progress=bar|lines} khi việc tự dò đoán sai.
 *
 * <p><b>An toàn luồng.</b> {@link CrawlerService} phát sự kiện từ MỌI worker
 * thread cùng lúc, nên toàn bộ phần vẽ nằm trong một khối đồng bộ: hai luồng
 * cùng ghi vào một dòng terminal sẽ trộn lẫn thành ký tự vô nghĩa. Khối đồng bộ
 * này không phải nút thắt cổ chai — nó chỉ giữ trong vài chục micro giây, còn
 * mỗi trang crawl mất hàng trăm mili giây vì politeness delay.
 */
public final class ProgressBarCrawlListener implements CrawlListener {

    /** Rộng vừa đủ để thấy tiến triển từng phần trăm mà vẫn lọt terminal 80 cột. */
    private static final int BAR_WIDTH = 28;

    /** 10 khung/giây: dưới ngưỡng này mắt không thấy khác, trên thì phí I/O. */
    private static final long MIN_REPAINT_MS = 100;

    // Escape bát phân "\033" chứ không phải ký tự ESC thô: một ký tự điều khiển
    // vô hình nằm trong mã nguồn là thứ dễ mất khi sao chép và không ai nhìn
    // thấy để sửa.
    private static final String ESC_RESET = "\033[0m";
    private static final String ESC_BOLD = "\033[1m";
    private static final String ESC_GREEN = "\033[32m";

    private final Object lock = new Object();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();

    private final boolean interactive;
    private final boolean unicode;
    private final boolean color;
    private final int everyN;
    private final long startMs;

    /** Độ dài dòng vẽ lần trước, để xóa sạch phần thừa khi dòng mới ngắn hơn. */
    private int lastLineLength;
    private long lastRepaintMs;

    /**
     * @param everyN ở chế độ không tương tác thì bao nhiêu trang in một dòng mốc;
     *               ở chế độ thanh tiến trình tham số này không dùng đến
     */
    public ProgressBarCrawlListener(int everyN) {
        this.everyN = Math.max(1, everyN);
        this.interactive = detectInteractive();
        this.unicode = stdoutCharset().newEncoder().canEncode("█░");
        // Bỏ màu khi output không phải terminal: mã ANSI lọt vào file log biến
        // thành rác. Tôn trọng luôn quy ước NO_COLOR (no-color.org).
        this.color = interactive && System.getenv("NO_COLOR") == null;
        // Mốc thời gian lấy ở constructor chứ không phải trang đầu tiên: giai
        // đoạn phân giải DNS và tải robots.txt trước trang đầu cũng là thời gian
        // người dùng phải chờ, tính sót đi thì tốc độ báo ra cao hơn thực tế.
        this.startMs = System.currentTimeMillis();
    }

    /**
     * {@code -Dcrawl.progress} thắng việc tự dò. Cần có lối thoát này vì
     * {@link System#console()} trả về {@code null} trong nhiều môi trường thật
     * sự có terminal (một số IDE, một số trình bao bọc tiến trình).
     */
    private static boolean detectInteractive() {
        String forced = System.getProperty("crawl.progress");
        if (forced != null) {
            return forced.equalsIgnoreCase("bar");
        }
        return System.console() != null;
    }

    /**
     * Bảng mã THẬT SỰ của {@code System.out}, không phải bảng mã mặc định của
     * JVM: trên Windows hai thứ này khác nhau, và đoán sai thì thanh tiến trình
     * hiện ra thành dấu hỏi.
     */
    private static Charset stdoutCharset() {
        for (String key : new String[] {"stdout.encoding", "native.encoding", "file.encoding"}) {
            String name = System.getProperty(key);
            if (name == null) {
                continue;
            }
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // Tên bảng mã không hợp lệ hoặc JVM không hỗ trợ — thử khóa kế tiếp.
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public void onPageCrawled(CrawlEvent e) {
        synchronized (lock) {
            if (!interactive) {
                if (e.pageNumber() % everyN == 0 || e.pageNumber() == e.maxPages()) {
                    System.out.println(plainLine(e, System.currentTimeMillis()));
                }
                return;
            }
            long now = System.currentTimeMillis();
            // Trang cuối luôn được vẽ dù vừa vẽ xong: nếu bỏ, thanh tiến trình
            // đứng mãi ở 99% rồi biến mất — trông như crawl chết giữa chừng.
            if (now - lastRepaintMs < MIN_REPAINT_MS && e.pageNumber() != e.maxPages()) {
                return;
            }
            lastRepaintMs = now;
            paint(e, now);
        }
    }

    /**
     * Chỉ ĐẾM lỗi chứ không in ra. In một dòng lỗi giữa lúc thanh tiến trình
     * đang chiếm dòng hiện tại sẽ cắt đôi nó thành hai mẩu rác. Tổng số lỗi vẫn
     * hiện ngay trên thanh, còn URL cụ thể thì đăng ký kèm
     * {@link ConsoleCrawlListener} — nó ghi ra log, nơi dành cho chi tiết.
     */
    @Override
    public void onError(String url, Exception error) {
        errors.incrementAndGet();
    }

    @Override
    public void onDuplicateContent(String url) {
        duplicates.incrementAndGet();
    }

    @Override
    public void onFinished(int totalPages, long elapsedMs) {
        synchronized (lock) {
            if (interactive) {
                clearLine();
            }
            double seconds = elapsedMs / 1000.0;
            System.out.printf(Locale.US,
                    "%s %d trang trong %s (%.2f trang/giây) — %d lỗi, %d trùng nội dung%n",
                    unicode ? "✓" : "OK", totalPages, formatDuration(elapsedMs),
                    seconds > 0 ? totalPages / seconds : 0.0,
                    errors.get(), duplicates.get());
        }
    }

    /** Vẽ đè lên dòng hiện tại bằng {@code \r} — sinh ra ảo giác thanh chuyển động. */
    private void paint(CrawlEvent e, long now) {
        String plain = plainLine(e, now);
        String shown = color ? colorLine(e, now) : plain;

        // Đệm khoảng trắng tới độ dài lần vẽ trước. Không có bước này, một dòng
        // ngắn hơn sẽ để lại đuôi của dòng cũ nằm lại trên màn hình. Đo theo
        // `plain` vì mã màu ANSI chiếm ký tự nhưng không chiếm cột nào.
        int padding = Math.max(0, lastLineLength - plain.length());
        System.out.print("\r" + shown + " ".repeat(padding));
        System.out.flush();
        lastLineLength = plain.length();
    }

    private void clearLine() {
        System.out.print("\r" + " ".repeat(lastLineLength) + "\r");
        System.out.flush();
        lastLineLength = 0;
    }

    private String plainLine(CrawlEvent e, long now) {
        return bar(e) + " " + stats(e, now);
    }

    private String colorLine(CrawlEvent e, long now) {
        return ESC_GREEN + bar(e) + ESC_RESET + " " + ESC_BOLD + stats(e, now) + ESC_RESET;
    }

    private String bar(CrawlEvent e) {
        double ratio = e.maxPages() <= 0 ? 0 : Math.min(1.0, (double) e.pageNumber() / e.maxPages());
        int filled = (int) Math.round(ratio * BAR_WIDTH);
        String full = unicode ? "█" : "#";
        String empty = unicode ? "░" : ".";
        return "[" + full.repeat(filled) + empty.repeat(BAR_WIDTH - filled) + "]"
                + String.format(Locale.US, " %3.0f%%", ratio * 100);
    }

    private String stats(CrawlEvent e, long now) {
        long elapsedMs = now - startMs;
        double rate = elapsedMs > 0 ? e.pageNumber() * 1000.0 / elapsedMs : 0.0;
        // ETA ngoại suy tuyến tính từ tốc độ TRUNG BÌNH cả phiên. Tốc độ tức
        // thời phản ứng nhanh hơn nhưng nhảy loạn, còn tốc độ crawl bị chặn
        // trên bởi politeness delay nên khá ổn định — trung bình là lựa chọn
        // đúng ở đây. Con số này vẫn chỉ là ước lượng: frontier cạn sớm thì
        // phiên crawl kết thúc trước ETA.
        int remaining = Math.max(0, e.maxPages() - e.pageNumber());
        String eta = rate > 0 ? formatDuration((long) (remaining / rate * 1000)) : "--:--";

        return String.format(Locale.US,
                "%d/%d  %.1f trang/s  còn ~%s  hàng đợi %s  %d host  %d lỗi  %d trùng",
                e.pageNumber(), e.maxPages(), rate, eta,
                compact(e.frontierSize()), e.domainCount(), errors.get(), duplicates.get());
    }

    /** Rút gọn số lớn: hàng đợi lên tới hàng chục nghìn sẽ đẩy dòng vượt bề rộng terminal. */
    private static String compact(int value) {
        if (value < 10_000) {
            return Integer.toString(value);
        }
        return String.format(Locale.US, "%.1fk", value / 1000.0);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }
}
