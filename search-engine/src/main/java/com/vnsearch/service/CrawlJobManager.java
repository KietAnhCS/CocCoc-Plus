package com.vnsearch.service;

import com.vnsearch.crawler.ConsoleCrawlListener;
import com.vnsearch.crawler.CrawlConfig;
import com.vnsearch.crawler.CrawlListener;
import com.vnsearch.crawler.CrawlerService;
import com.vnsearch.crawler.UrlFilter;
import com.vnsearch.crawler.bus.CrawlEventBus;
import com.vnsearch.crawler.bus.DiscoveredUrl;
import com.vnsearch.crawler.bus.OutlinksExtracted;
import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Quan ly vong doi cac job crawl chay nen.
 *
 * <p>Tach khoi {@code SearchEngineFacade} vi day la mot trach nhiem doc lap:
 * no co trang thai rieng (bang job), tai nguyen rieng (thread pool), va vong
 * doi rieng (job song lau hon mot request HTTP).
 *
 * <p><b>State pattern:</b> trang thai job la {@link CrawlStatus} — mot enum co
 * may trang thai, khong con la {@code String} nhu truoc. Chuyen trang thai sai
 * nem ngoai le NGAY tai cho sai, thay vi lam hong tang UI mot cach im lang.
 *
 * <p><b>Observer pattern:</b> job dang ky {@link CrawlListener} de theo doi
 * tien do, thay vi crawler tu in ra console.
 */
@Component
public class CrawlJobManager {

    private static final Logger log = LoggerFactory.getLogger(CrawlJobManager.class);

    /** Thoi gian giu mot job da ket thuc trong bang truoc khi don di. */
    private static final int JOB_RETENTION_MINUTES = 30;

    /**
     * Tran so job chay dong thoi.
     *
     * <p>Truoc day day la {@code newCachedThreadPool()} — khong gioi han. Moi
     * job crawl tu no da mo mot pool 4 luong rieng va giu ca corpus trong bo
     * nho, nen "khong gioi han so job" nghia la khong gioi han bo nho lan so
     * luong. Hai job cung luc la du cho mot he thong mot may.
     */
    private static final int MAX_CONCURRENT_JOBS = 2;

    private final Map<String, CrawlJob> jobs = new ConcurrentHashMap<>();

    /**
     * Pool co tran, hang doi co tran.
     *
     * <p>{@code newFixedThreadPool} dung hang doi KHONG gioi han, nen job thu
     * 1000 van duoc nhan va nam cho — doi lai mot dang tich luy khac. Hang doi
     * co tran cong chinh sach {@code AbortPolicy} khien job vuot tran bi tu
     * choi NGAY bang mot ngoai le, va nguoi goi biet ma thu lai sau.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_JOBS, MAX_CONCURRENT_JOBS,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_CONCURRENT_JOBS * 2),
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * Job duoc khoi dong gan nhat.
     *
     * <p>Thay cho phep {@code jobs.values().stream().reduce((a, b) -> b)} truoc
     * day: {@code ConcurrentHashMap.values()} <b>khong co thu tu xac dinh</b>,
     * nen phep do tra ve mot job TUY Y chu khong phai job cuoi — con so
     * {@code bloomFilterBits} trong {@code /api/admin/stats} vi the khong dang
     * tin.
     */
    private volatile String lastJobId;

    /**
     * Bus sự kiện, hoặc {@code null} khi chạy ở chế độ mặc định.
     *
     * <p>{@link ObjectProvider} chứ không phải tiêm thẳng: bean
     * {@code CrawlEventBus} chỉ tồn tại khi {@code app.crawler.bus=kafka} (xem
     * {@code KafkaCrawlConfig}). Tiêm thẳng một bean có thể vắng mặt sẽ làm
     * ứng dụng <b>không khởi động được</b> ở cấu hình mặc định — đúng thứ mà
     * cả thiết kế này cố tránh.
     */
    private final CrawlEventBus sharedBus;

    /** Sự kiện quay về mà không tìm thấy job tương ứng — xem {@link #resolve}. */
    private final AtomicLong unroutableEvents = new AtomicLong();

    public CrawlJobManager(ObjectProvider<CrawlEventBus> busProvider) {
        this.sharedBus = busProvider.getIfAvailable();
        if (sharedBus != null) {
            log.info("CrawlJobManager dung bus dung chung: {}",
                    sharedBus.getClass().getSimpleName());
        }
    }

    /**
     * Mot job crawl, voi trang thai duoc bao ve boi may trang thai.
     *
     * <p><b>Tham chieu toi crawler duoc BO khi job ket thuc.</b> Truoc day
     * truong nay la {@code final} va bang job song mai trong bang {@link #jobs},
     * nen moi {@link CrawlerService} — kem {@code ContentStorage} chua TOAN BO
     * tai lieu cua phien do — nam lai trong bo nho vinh vien. Voi corpus 30.000
     * trang (367 MB), chi vai job la het bo nho; ket hop voi mot endpoint truoc
     * day khong can xac thuc, do la mot don tu choi dich vu chi ton vai request.
     *
     * <p>Nay khi job ket thuc, cac con so can cho API trang thai duoc <b>chup
     * lai</b> vao vai truong {@code int}, roi tham chieu crawler duoc bo de bo
     * thu gom rac lay lai toan bo corpus.
     */
    private static final class CrawlJob {

        private volatile CrawlerService crawler;
        private volatile CrawlStatus status = CrawlStatus.STARTED;
        volatile String errorMessage;
        volatile long finishedAtMillis;

        // Anh chup so lieu, giu lai sau khi crawler da duoc buong.
        private volatile int finalPagesCrawled;
        private volatile int finalQueueSize;
        private volatile int finalBloomFilterBits;

        CrawlJob(CrawlerService crawler) {
            this.crawler = crawler;
        }

        int pagesCrawled() {
            CrawlerService c = crawler;
            return c != null ? c.getPagesCrawledCount() : finalPagesCrawled;
        }

        int queueSize() {
            CrawlerService c = crawler;
            return c != null ? c.getQueueSize() : finalQueueSize;
        }

        int bloomFilterBits() {
            CrawlerService c = crawler;
            return c != null ? c.getBloomFilterBits() : finalBloomFilterBits;
        }

        /** Chup so lieu roi buong crawler — xem Javadoc lop. */
        void releaseCrawler() {
            CrawlerService c = crawler;
            if (c == null) {
                return;
            }
            finalPagesCrawled = c.getPagesCrawledCount();
            finalQueueSize = c.getQueueSize();
            finalBloomFilterBits = c.getBloomFilterBits();
            crawler = null;
            finishedAtMillis = System.currentTimeMillis();
        }

        /**
         * Chuyen trang thai, KIEM TRA tinh hop le.
         *
         * @throws IllegalStateException neu chuyen tiep khong hop le
         */
        synchronized void transitionTo(CrawlStatus next) {
            if (!status.canTransitionTo(next)) {
                throw new IllegalStateException("Khong the chuyen tu " + status + " sang " + next);
            }
            status = next;
        }

        CrawlStatus status() {
            return status;
        }
    }

    /**
     * Bat dau mot phien crawl chay nen.
     *
     * @param onSuccess duoc goi voi danh sach tai lieu khi crawl xong — de
     *                  Facade dung lai chi muc ma lop nay khong phai biet gi
     *                  ve chi muc
     * @return id cua job, dung de hoi trang thai
     */
    public String start(List<String> seedUrls, int maxDepth, int maxPages,
                         Consumer<List<WebDocument>> onSuccess) {
        String jobId = UUID.randomUUID().toString();
        // sharedBus null ở chế độ mặc định -> CrawlerService tự dựng bus
        // in-process và tự đăng ký ba Modular Service. Không có nhánh if nào
        // cần thiết ở đây: constructor đã nhận null làm "về mặc định".
        CrawlerService crawler = new CrawlerService(sharedBus);
        crawler.setJobId(jobId); // TRƯỚC khi crawl, để mọi sự kiện mang đúng id
        crawler.addListener(new ConsoleCrawlListener(25)); // Observer
        CrawlJob job = new CrawlJob(crawler);
        jobs.put(jobId, job);
        lastJobId = jobId; // dat NGAY, de job dang chay cung bao cao duoc so lieu

        executor.submit(() -> {
            try {
                job.transitionTo(CrawlStatus.RUNNING);
                CrawlConfig config = CrawlConfig.builder() // Builder
                        .maxDepth(maxDepth)
                        .maxPages(maxPages)
                        .threadCount(4)
                        .allowedDomains(extractDomains(seedUrls))
                        // Cung chinh sach ngon ngu voi crawl bang dong lenh:
                        // corpus chi gom tieng Viet va tieng Anh.
                        .excludedHostPrefixes(UrlFilter.NON_VI_EN_HOST_PREFIXES)
                        .build();
                List<WebDocument> docs = crawler.crawl(seedUrls, config);
                onSuccess.accept(docs);
                job.transitionTo(CrawlStatus.DONE);
            } catch (Exception e) {
                log.error("Job crawl {} that bai", jobId, e);
                job.errorMessage = e.getMessage();
                try {
                    job.transitionTo(CrawlStatus.FAILED);
                } catch (IllegalStateException ignored) {
                    // Da o trang thai cuoi roi — khong ghi de.
                }
            } finally {
                // BAT BUOC trong finally: job that bai cung phai buong corpus,
                // neu khong thi mot job loi con giu bo nho lau hon job thanh cong.
                job.releaseCrawler();
                evictExpiredJobs();
            }
        });

        return jobId;
    }

    /**
     * Xoa cac job da ket thuc qua han khoi bang.
     *
     * <p>Bang {@link #jobs} truoc day <b>khong bao gio</b> duoc don, nen no lon
     * len vinh vien theo so lan goi API. Ngay ca sau khi crawler da duoc buong,
     * moi muc con lai van ton bo nho va lam {@link #getStatus} cham dan.
     *
     * <p>Giu lai {@value #JOB_RETENTION_MINUTES} phut sau khi ket thuc: du lau
     * de nguoi goi kip hoi ket qua cua job vua chay, du ngan de bang khong phinh.
     * Don theo kieu <b>co hoi</b> — moi lan mot job ket thuc — thay vi chay mot
     * luong hen gio rieng: khong co job moi thi cung khong co gi de don.
     */
    private void evictExpiredJobs() {
        long cutoff = System.currentTimeMillis() - JOB_RETENTION_MINUTES * 60_000L;
        jobs.entrySet().removeIf(entry -> {
            CrawlJob job = entry.getValue();
            return job.status().isTerminal()
                    && job.finishedAtMillis > 0
                    && job.finishedAtMillis < cutoff;
        });
    }

    // --- Đường về từ Kafka --------------------------------------------

    /**
     * Nạp một URL do {@code URL Extractor} tìm được vào frontier của <b>đúng
     * phiên crawl đã sinh ra nó</b>.
     *
     * <p>Đây là chặng cuối của vòng lặp trong sơ đồ kiến trúc, ở chế độ nhiều
     * tiến trình: URL đi từ crawler ra Kafka, qua URL Extractor, quay lại đây,
     * rồi vào {@code UrlFrontier}.
     */
    public boolean feedDiscoveredUrl(DiscoveredUrl url) {
        CrawlerService crawler = resolve(url == null ? null : url.jobId());
        return crawler != null && crawler.acceptDiscoveredUrl(url);
    }

    /** Ghi danh sách liên kết ra vào Content Storage của đúng phiên crawl. */
    public void feedOutlinks(OutlinksExtracted outlinks) {
        CrawlerService crawler = resolve(outlinks == null ? null : outlinks.jobId());
        if (crawler != null) {
            crawler.acceptOutlinks(outlinks);
        }
    }

    /**
     * Tìm crawler đang chạy ứng với một {@code jobId}.
     *
     * <p><b>Trả về {@code null} là chuyện bình thường</b>, không phải lỗi: một
     * sự kiện có thể quay về sau khi job đã kết thúc và crawler đã được buông
     * (xem {@code CrawlJob.releaseCrawler}). Với Kafka thì điều này chắc chắn
     * xảy ra — thông điệp còn nằm trên topic lâu hơn vòng đời của job đã sinh
     * ra nó, và khởi động lại ứng dụng là đọc lại chúng.
     *
     * <p>Bỏ qua là đúng, nhưng phải <b>đếm</b>: nếu con số này bằng đúng tổng
     * số sự kiện thì nghĩa là định tuyến hỏng hoàn toàn — frontier không bao
     * giờ được nạp và mọi phiên crawl dừng ngay sau các seed. Một hệ thống
     * hỏng theo kiểu "im lặng không làm gì" cần có đúng một con số để lộ ra.
     */
    private CrawlerService resolve(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            unroutableEvents.incrementAndGet();
            return null;
        }
        CrawlJob job = jobs.get(jobId);
        if (job == null) {
            unroutableEvents.incrementAndGet();
            return null;
        }
        CrawlerService crawler = job.crawler;
        if (crawler == null) {
            unroutableEvents.incrementAndGet();
        }
        return crawler;
    }

    /** Số sự kiện quay về không tìm được job — xem {@link #resolve}. */
    public long getUnroutableEventCount() {
        return unroutableEvents.get();
    }

    /** Trang thai cua mot job, hoac {@code null} neu khong co job do. */
    public Map<String, Object> getStatus(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job == null) {
            return null;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", job.status().name());
        status.put("terminal", job.status().isTerminal()); // UI biet khi nao ngung hoi lai
        status.put("pagesCrawled", job.pagesCrawled());
        status.put("queueSize", job.queueSize());
        if (job.errorMessage != null) {
            status.put("error", job.errorMessage);
        }
        return status;
    }

    /** So bit cua Bloom Filter cua job gan nhat — dung cho thong ke. */
    public int lastBloomFilterBits() {
        String id = lastJobId;
        if (id == null) {
            return 0;
        }
        CrawlJob job = jobs.get(id);
        return job == null ? 0 : job.bloomFilterBits();
    }

    private static Set<String> extractDomains(List<String> seedUrls) {
        Set<String> domains = new HashSet<>();
        for (String url : seedUrls) {
            try {
                String host = URI.create(url).getHost();
                if (host != null) {
                    domains.add(host);
                }
            } catch (Exception ignored) {
                // bo qua seed URL khong hop le
            }
        }
        return domains;
    }
}
