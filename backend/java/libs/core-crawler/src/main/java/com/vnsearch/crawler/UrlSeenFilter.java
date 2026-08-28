package com.vnsearch.crawler;

import com.vnsearch.datastructure.BloomFilter;

/**
 * <b>Khối "URL Seen?"</b> trong sơ đồ kiến trúc crawler, cùng với mũi tên
 * nối nó với {@link UrlStorage}.
 *
 * <p>Trước đây phần này là một trường {@code BloomFilter visited} trần trụi
 * trong {@code CrawlerService}, được đọc/ghi trực tiếp từ vòng lặp worker.
 * Bọc lại thành một lớp vì hai lý do — lý do thứ hai là một lỗi thật sự:
 *
 * <p><b>1. Đúng cấu trúc sơ đồ.</b> Khối {@code URL Seen?} có một kho lưu
 * bền đứng sau; ở đây là {@link UrlStorage}. Mỗi URL được ghi nhận đều được
 * ghi xuống kho, và {@link #replayFromStorage()} dựng lại bộ lọc từ kho khi
 * tiếp tục một phiên crawl dang dở.
 *
 * <p><b>2. Sửa một lỗi tương tranh.</b> {@link BloomFilter} <b>không</b>
 * thread-safe: {@code add} thực hiện {@code bits[i] |= mask} — một phép
 * đọc-sửa-ghi không nguyên tử trên mảng {@code long[]}. Hai worker cùng bật
 * hai bit khác nhau nằm trong <i>cùng một phần tử mảng</i> có thể làm mất
 * một trong hai phép ghi. Bit bị mất nghĩa là bộ lọc <b>false negative</b>:
 * báo "chưa gặp" cho một URL đã gặp. Với bộ lọc Bloom, false positive chỉ
 * làm bỏ sót vài trang, nhưng false negative làm crawler tải lại trang cũ —
 * đúng thứ mà chính Javadoc của {@code BloomFilter} khẳng định không bao giờ
 * xảy ra <i>khi dùng một luồng</i>. Mọi truy cập ở đây đều nằm trong khối
 * {@code synchronized} nên tính chất đó được khôi phục.
 *
 * <p><b>Vì sao kiểm tra và ghi nhận phải nguyên tử.</b>
 * {@link #markSeenIfNew(String)} gộp "hỏi đã gặp chưa" và "ghi nhận đã gặp"
 * thành một thao tác. Tách rời thì hai worker cùng bóc được một liên kết
 * giống nhau có thể cùng thấy "chưa gặp" và cùng xếp URL đó vào hàng đợi.
 *
 * <p>Chi phí của khoá là không đáng kể: thân phương thức chỉ có O(k) phép
 * tính băm với k nhỏ, không có thao tác vào/ra nào bên trong khoá —
 * {@link UrlStorage#append} có đệm nên cũng không chờ đĩa.
 */
public class UrlSeenFilter {

    /**
     * Ước lượng số URL SẼ GẶP trên mỗi trang SẼ LƯU.
     *
     * <p>Bộ lọc không chỉ chứa các trang đã lưu mà chứa MỌI URL đã kiểm tra.
     * Đo thực tế: mỗi trang tin tức sinh trung bình 78,8 liên kết ra, tất cả
     * đều đi qua {@code mightContain}. Cấp phát theo {@code maxPages} sẽ
     * khiến n thật gấp khoảng 80 lần n thiết kế, tỷ lệ bit bật vọt lên gần
     * 100%, bộ lọc báo "đã gặp" cho MỌI URL và crawler dừng sau vài trang.
     */
    public static final int URLS_SEEN_PER_PAGE = 200;

    /** Sàn kích thước, để phiên crawl nhỏ vẫn có bộ lọc đủ thưa. */
    public static final int MIN_EXPECTED_URLS = 200_000;

    /**
     * Trần kích thước, khoảng 60 MB bit.
     *
     * <p>Không có trần thì {@code maxPages} lớn sẽ khiến phép nhân với
     * {@link #URLS_SEEN_PER_PAGE} tràn số nguyên và {@link BloomFilter} nhận
     * một kích thước âm — hỏng ở một chỗ hoàn toàn không liên quan tới nguyên
     * nhân. Vượt quy mô này thì bộ lọc trong bộ nhớ không còn là lời giải
     * đúng nữa, phải chuyển sang bộ lọc phân tán.
     */
    public static final int MAX_EXPECTED_URLS = 50_000_000;

    private static final double FALSE_POSITIVE_RATE = 0.01;

    private final BloomFilter bloomFilter;
    private final UrlStorage urlStorage;
    private final Object lock = new Object();

    private long seenCount;

    /** Cấp phát bộ lọc theo số trang dự kiến crawl, không lưu bền. */
    public static UrlSeenFilter forMaxPages(int maxPages) {
        return forMaxPages(maxPages, UrlStorage.disabled());
    }

    /** Cấp phát bộ lọc theo số trang dự kiến crawl, có kho lưu bền. */
    public static UrlSeenFilter forMaxPages(int maxPages, UrlStorage urlStorage) {
        // Tính bằng long rồi mới kẹp về int: phép nhân này tràn số nguyên với
        // maxPages từ khoảng 10,7 triệu trở lên.
        long expected = Math.max(MIN_EXPECTED_URLS, (long) maxPages * URLS_SEEN_PER_PAGE);
        return new UrlSeenFilter((int) Math.min(expected, MAX_EXPECTED_URLS), urlStorage);
    }

    public UrlSeenFilter(int expectedUrls, UrlStorage urlStorage) {
        this.bloomFilter = new BloomFilter(expectedUrls, FALSE_POSITIVE_RATE);
        this.urlStorage = urlStorage == null ? UrlStorage.disabled() : urlStorage;
    }

    /**
     * Ghi nhận một URL nếu chưa từng gặp — thao tác kiểm tra-và-ghi nguyên tử.
     *
     * @return {@code true} nếu URL này là mới (và vừa được ghi nhận), tức nó
     *         được phép đi tiếp vào hàng đợi; {@code false} nếu đã gặp rồi
     */
    public boolean markSeenIfNew(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        synchronized (lock) {
            if (bloomFilter.mightContain(url)) {
                return false;
            }
            bloomFilter.add(url);
            seenCount++;
            urlStorage.append(url);
            return true;
        }
    }

    /** Chỉ hỏi, không ghi nhận. */
    public boolean seenBefore(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        synchronized (lock) {
            return bloomFilter.mightContain(url);
        }
    }

    /**
     * Nạp lại các URL đã lưu ở phiên trước vào bộ lọc, để phiên này không
     * tải lại những trang đó.
     *
     * @return số URL đã nạp
     */
    public long replayFromStorage() {
        return urlStorage.replay(url -> {
            synchronized (lock) {
                if (!bloomFilter.mightContain(url)) {
                    bloomFilter.add(url);
                    seenCount++;
                }
            }
        });
    }

    /** Số URL phân biệt đã ghi nhận (theo góc nhìn của bộ lọc). */
    public long getSeenCount() {
        synchronized (lock) {
            return seenCount;
        }
    }

    /** Số bit của bộ lọc Bloom — số liệu dùng cho báo cáo bộ nhớ. */
    public int getNumBits() {
        return bloomFilter.getNumBits();
    }

    public int getNumHashes() {
        return bloomFilter.getNumHashes();
    }

    public UrlStorage getUrlStorage() {
        return urlStorage;
    }
}
