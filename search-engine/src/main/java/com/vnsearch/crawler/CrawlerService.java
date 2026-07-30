package com.vnsearch.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vnsearch.datastructure.BloomFilter;
import com.vnsearch.datastructure.UrlFrontier;
import com.vnsearch.model.WebDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dich vu crawl web, duyet theo BFS (uu tien theo diem uu tien cua
 * {@link UrlFrontier}) bang cach chia viec cho nhieu thread trong mot
 * {@link ExecutorService} co so luong thread co dinh.
 *
 * <p>Dedupe URL bang {@link BloomFilter} (khong luu toan bo chuoi URL nhu
 * HashSet) ket hop {@code ConcurrentHashMap} de luu ket qua crawl thread-safe.
 * Ton trong robots.txt qua {@link RobotsTxtParser}. Fetch HTML bang Jsoup voi
 * timeout 10s, retry toi da 2 lan neu that bai. Trich xuat noi dung bang
 * {@link HtmlExtractor}, dong goi thanh {@link WebDocument}.
 *
 * <p><b>Observer:</b> tien do khong con duoc in thang trong vong lap worker
 * ma duoc phat qua {@link CrawlListener}. Xem Javadoc cua giao dien do ve ly do.
 *
 * <p><b>Builder:</b> cau hinh la {@link CrawlConfig} bat bien, kiem tra tinh
 * hop le tap trung trong {@code build()}.
 */
public class CrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerService.class);

    private static final String USER_AGENT = "VnSearchBot/1.0 (+do an DSA; hoc thuat)";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RETRIES = 2;

    /**
     * Uoc luong so URL SE GAP tren moi trang SE LUU.
     *
     * <p>Bloom filter khong chi chua cac trang da luu ma chua MOI URL da kiem
     * tra. Do thuc te: moi trang tin tuc sinh trung binh 78,8 outlink, tat ca
     * deu di qua {@code mightContain}. Cap phat theo {@code maxPages} se khien
     * n that gap ~80 lan n thiet ke -> ty le bit bat vot len gan 100% -> filter
     * bao "da thay" cho MOI URL -> crawler dung sau vai trang.
     */
    private static final int URLS_SEEN_PER_PAGE = 200;

    private final UrlFrontier frontier = new UrlFrontier();
    private final ConcurrentHashMap<String, WebDocument> crawled = new ConcurrentHashMap<>();
    private final RobotsTxtParser robotsTxtParser = new RobotsTxtParser();
    private final HtmlExtractor htmlExtractor = new HtmlExtractor();
    private final AtomicInteger docIdCounter = new AtomicInteger(0);
    private final AtomicInteger pagesCrawled = new AtomicInteger(0);

    /** So worker dang THUC SU xu ly mot trang - dung de biet khi nao that su het viec. */
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    /**
     * Danh sach listener. {@code CopyOnWriteArrayList} vi no duoc DOC tu nhieu
     * worker thread nhung hiem khi ghi (chi luc dang ky) — dung ca su dung ma
     * cau truc nay duoc thiet ke cho.
     */
    private final List<CrawlListener> listeners = new CopyOnWriteArrayList<>();

    private volatile BloomFilter visited = new BloomFilter(200_000, 0.01);

    /** Dang ky mot bo quan sat phien crawl (Observer pattern). */
    public CrawlerService addListener(CrawlListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
        return this;
    }

    /** Chay mot phien crawl BFS day du, tra ve danh sach WebDocument da crawl duoc. */
    public List<WebDocument> crawl(List<String> seedUrls, CrawlConfig config) {
        long start = System.currentTimeMillis();
        visited = new BloomFilter(
                Math.max(200_000, config.maxPages() * URLS_SEEN_PER_PAGE), 0.01);

        for (String seed : seedUrls) {
            frontier.addUrl(seed, 0, 10);
        }

        ExecutorService pool = Executors.newFixedThreadPool(config.threadCount());
        CountDownLatch latch = new CountDownLatch(config.threadCount());
        for (int i = 0; i < config.threadCount(); i++) {
            pool.submit(() -> {
                try {
                    workerLoop(config);
                } catch (Exception e) {
                    log.error("Worker dung bat thuong", e);
                } finally {
                    latch.countDown(); // trong finally: thieu no thi await() cho du 60 phut vo ich
                }
            });
        }

        try {
            if (!latch.await(config.maxDurationMinutes(), TimeUnit.MINUTES)) {
                log.warn("Het tran thoi gian {} phut, dung crawl voi {} trang.",
                        config.maxDurationMinutes(), pagesCrawled.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        long elapsed = System.currentTimeMillis() - start;
        notifyFinished(pagesCrawled.get(), elapsed);
        return new ArrayList<>(crawled.values());
    }

    /**
     * Vong lap cua mot worker thread.
     *
     * <p><b>Dieu kien dung.</b> Frontier rong KHONG dong nghia voi het viec —
     * mot worker khac co the dang fetch mot trang va sap them hang tram outlink
     * moi. Neu thoat ngay khi thay frontier rong, cac worker se chet dan trong
     * nhung khoang trong tam thoi va phien crawl dung som hon nhieu so voi
     * maxPages.
     *
     * <p>Dieu kien dung dung la {@code F = 0 AND A = 0}. Nhung hai phep doc do
     * KHONG nguyen tu voi nhau, nen ton tai mot cua so dua: worker A thay
     * frontier rong dung luc worker B da lay task nhung CHUA kip
     * {@code incrementAndGet}. Yeu cau dieu kien dung {@code IDLE_CONFIRMATIONS}
     * lan lien tiep, cach nhau 200ms, dua xac suat nham xuong khoang
     * {@code (vai us / 200000 us)^3 ~= 10^-15}.
     *
     * <p>Day la mot HEURISTIC, khong phai thuat toan dung dan co chung minh —
     * bai toan "phat hien ket thuc phan tan" co loi giai chinh xac
     * (Dijkstra-Scholten, Safra) nhung phuc tap hon nhieu.
     */
    private void workerLoop(CrawlConfig config) {
        final int idleConfirmations = 3;
        int idleChecks = 0;

        while (pagesCrawled.get() < config.maxPages()) {
            UrlFrontier.Task task = frontier.nextUrl();
            if (task == null) {
                if (activeWorkers.get() == 0 && ++idleChecks >= idleConfirmations) {
                    break; // that su het viec
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            idleChecks = 0; // chi tich luy khi LIEN TUC rong

            // Loc theo thu tu chi phi TANG DAN: so sanh so nguyen -> phan tich
            // URI -> bam Bloom -> co the fetch mang. Java danh gia || ngan mach
            // nen dieu kien dat chi chay khi cac dieu kien re deu khong loai duoc.
            if (task.depth() > config.maxDepth()
                    || !isAllowedDomain(task.url(), config.allowedDomains())
                    || visited.mightContain(task.url())) {
                continue;
            }
            visited.add(task.url());

            if (!robotsTxtParser.isAllowed(USER_AGENT, task.url())) {
                continue;
            }

            activeWorkers.incrementAndGet();
            try {
                WebDocument doc = fetchWithRetry(task.url());
                if (doc == null) {
                    continue;
                }

                doc.setDocId(docIdCounter.getAndIncrement());
                crawled.put(task.url(), doc);
                int count = pagesCrawled.incrementAndGet();
                notifyPageCrawled(new CrawlListener.CrawlEvent(
                        count, config.maxPages(), task.url(), task.depth(),
                        doc.getOutlinks().size(), frontier.size(), frontier.domainCount()));

                if (task.depth() < config.maxDepth()) {
                    for (String outlink : doc.getOutlinks()) {
                        if (isAllowedDomain(outlink, config.allowedDomains())
                                && !visited.mightContain(outlink)) {
                            frontier.addUrl(outlink, task.depth() + 1, 1);
                        }
                    }
                }
            } finally {
                // BAT BUOC trong finally: neu fetch nem ngoai le ma khong giam,
                // activeWorkers khong bao gio ve 0 va dieu kien dung khong bao
                // gio dung -> moi worker ket trong vong lap ngu-thu-lai.
                activeWorkers.decrementAndGet();
            }
        }
    }

    private void notifyPageCrawled(CrawlListener.CrawlEvent event) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onPageCrawled(event);
            } catch (Exception e) {
                // Mot listener hong khong duoc lam chet ca phien crawl.
                log.warn("Listener {} nem ngoai le", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyError(String url, Exception error) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onError(url, error);
            } catch (Exception e) {
                log.warn("Listener {} nem ngoai le", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private void notifyFinished(int totalPages, long elapsedMs) {
        for (CrawlListener listener : listeners) {
            try {
                listener.onFinished(totalPages, elapsedMs);
            } catch (Exception e) {
                log.warn("Listener {} nem ngoai le", listener.getClass().getSimpleName(), e);
            }
        }
    }

    private boolean isAllowedDomain(String url, Set<String> allowedDomains) {
        if (allowedDomains.isEmpty()) {
            return true;
        }
        try {
            String host = URI.create(url).getHost();
            return host != null && allowedDomains.stream().anyMatch(host::endsWith);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Toi da {@code MAX_RETRIES + 1} lan thu, moi lan timeout 10 giay -> chan
     * tren 30 giay cho mot URL chet. Chi bao loi o lan thu CUOI de khong spam.
     *
     * <p>Day la retry don gian, KHONG co exponential backoff. Politeness delay
     * 1 giay da tao mot muc gian toi thieu, nhung khong tang theo so lan loi.
     */
    private WebDocument fetchWithRetry(String url) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Document document = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .followRedirects(true)
                        .get();
                return htmlExtractor.extract(url, document);
            } catch (Exception e) {
                lastError = e;
            }
        }
        notifyError(url, lastError);
        return null;
    }

    public int getPagesCrawledCount() {
        return pagesCrawled.get();
    }

    public int getQueueSize() {
        return frontier.size();
    }

    public int getBloomFilterBits() {
        return visited.getNumBits();
    }

    /** Luu danh sach WebDocument da crawl ra file JSON (Jackson), tao thu muc neu chua co. */
    public static void saveToJson(List<WebDocument> documents, String path) throws IOException {
        Path filePath = Path.of(path);
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(new File(path), documents);
    }

    public static List<WebDocument> loadFromJson(String path) throws IOException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        WebDocument[] docs = mapper.readValue(new File(path), WebDocument[].class);
        return new ArrayList<>(List.of(docs));
    }

    /**
     * Demo/kiem chung: crawl that 100 trang tu seed vnexpress.net, in thong ke,
     * luu ra data/crawled-documents.json.
     */
    public static void main(String[] args) throws IOException {
        CrawlerService crawler = new CrawlerService()
                .addListener(new ConsoleCrawlListener(1)); // Observer

        CrawlConfig config = CrawlConfig.builder()
                .maxDepth(3)
                .maxPages(100)
                .threadCount(4)
                .allowedDomains(Set.of("vnexpress.net"))
                .build();

        List<WebDocument> docs = crawler.crawl(List.of("https://vnexpress.net/"), config);

        System.out.println();
        System.out.println("=== THONG KE CRAWL ===");
        System.out.println("Tong so trang crawl duoc: " + docs.size());
        int totalOutlinks = docs.stream().mapToInt(d -> d.getOutlinks().size()).sum();
        System.out.println("Tong so outlink thu duoc: " + totalOutlinks);
        System.out.println("Trung binh outlink/trang: " + (docs.isEmpty() ? 0 : totalOutlinks / docs.size()));
        System.out.println("Vi du 5 trang dau:");
        docs.stream().limit(5).forEach(d -> System.out.println("  - " + d.getTitle() + " (" + d.getUrl() + ")"));

        saveToJson(docs, "data/crawled-documents.json");
        System.out.println("Da luu vao data/crawled-documents.json");
    }
}
