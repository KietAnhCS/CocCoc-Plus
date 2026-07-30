package com.vnsearch.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vnsearch.datastructure.BloomFilter;
import com.vnsearch.datastructure.UrlFrontier;
import com.vnsearch.model.WebDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dich vu crawl web, duyet theo BFS (uu tien theo do sau tang dan qua diem
 * uu tien cua {@link UrlFrontier}) bang cach chia viec cho nhieu thread
 * trong mot {@link ExecutorService} co so luong thread co dinh.
 *
 * <p>Dedupe URL bang {@link BloomFilter} (khong luu toan bo chuoi URL nhu
 * HashSet) ket hop {@code ConcurrentHashMap} de luu ket qua crawl thread-safe.
 * Ton trong robots.txt qua {@link RobotsTxtParser}. Fetch HTML bang Jsoup
 * voi timeout 10s, retry toi da 2 lan neu that bai. Trich xuat noi dung
 * bang {@link HtmlExtractor}, dong goi thanh {@link WebDocument}.
 *
 * <p>Log tien do dang: {@code [123/1000] Crawled: <url> (depth=2, 45 links)}
 */
public class CrawlerService {

    private static final String USER_AGENT = "VnSearchBot/1.0 (+do an DSA; hoc thuat)";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RETRIES = 2;

    /** Cau hinh cho mot phien crawl. */
    public static class CrawlConfig {
        public int maxDepth = 3;
        public int maxPages = 100;
        public int threadCount = 4;
        public Set<String> allowedDomains = Set.of(); // rong = khong gioi han
        /** Tran thoi gian cho ca phien crawl (phut). */
        public int maxDurationMinutes = 60;
        /** Chi in log tien do moi N trang - tranh spam console khi crawl hang nghin trang. */
        public int progressEveryN = 1;

        public CrawlConfig maxDepth(int v) { this.maxDepth = v; return this; }
        public CrawlConfig maxPages(int v) { this.maxPages = v; return this; }
        public CrawlConfig threadCount(int v) { this.threadCount = v; return this; }
        public CrawlConfig allowedDomains(Set<String> v) { this.allowedDomains = v; return this; }
        public CrawlConfig maxDurationMinutes(int v) { this.maxDurationMinutes = v; return this; }
        public CrawlConfig progressEveryN(int v) { this.progressEveryN = v; return this; }
    }

    private final UrlFrontier frontier = new UrlFrontier();
    private final ConcurrentHashMap<String, WebDocument> crawled = new ConcurrentHashMap<>();
    private final RobotsTxtParser robotsTxtParser = new RobotsTxtParser();
    private final HtmlExtractor htmlExtractor = new HtmlExtractor();
    private final AtomicInteger docIdCounter = new AtomicInteger(0);
    private final AtomicInteger pagesCrawled = new AtomicInteger(0);
    /** So worker dang THUC SU xu ly mot trang - dung de biet khi nao that su het viec. */
    private final AtomicInteger activeWorkers = new AtomicInteger(0);

    private volatile BloomFilter visited = new BloomFilter(200_000, 0.01);

    /** Chay mot phien crawl BFS day du, tra ve danh sach WebDocument da crawl duoc. */
    public List<WebDocument> crawl(List<String> seedUrls, CrawlConfig config) {
        // Bloom filter phai du lon so voi quy mo crawl: moi trang sinh hang tram
        // outlink deu duoc kiem tra qua filter nay, nen kich thuoc phai tinh theo
        // so URL SE GAP chu khong phai so trang se luu.
        visited = new BloomFilter(Math.max(200_000, config.maxPages * 200), 0.01);

        for (String seed : seedUrls) {
            frontier.addUrl(seed, 0, 10);
        }

        ExecutorService pool = Executors.newFixedThreadPool(config.threadCount);
        CountDownLatch latch = new CountDownLatch(config.threadCount);
        for (int i = 0; i < config.threadCount; i++) {
            pool.submit(() -> {
                try {
                    workerLoop(config);
                } catch (Exception e) {
                    System.out.printf("  [loi] worker dung bat thuong: %s%n", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(config.maxDurationMinutes, TimeUnit.MINUTES)) {
                System.out.printf("Het tran thoi gian %d phut, dung crawl voi %d trang.%n",
                        config.maxDurationMinutes, pagesCrawled.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        return new ArrayList<>(crawled.values());
    }

    /**
     * Vong lap cua mot worker thread.
     *
     * <p><b>Dieu kien dung:</b> frontier rong KHONG dong nghia voi het viec —
     * mot worker khac co the dang fetch mot trang va sap them hang tram
     * outlink moi vao frontier. Neu thoat ngay khi thay frontier rong, cac
     * worker se chet dan trong nhung khoang trong tam thoi va phien crawl
     * dung som hon nhieu so voi maxPages. Vi vay chi thoat khi frontier rong
     * VA khong con worker nao dang xu ly, va dieu do phai dung lien tiep
     * {@code IDLE_CONFIRMATIONS} lan de loai bo truong hop chi la khe hep
     * nhat thoi giua hai lan cap nhat.
     */
    private void workerLoop(CrawlConfig config) {
        final int IDLE_CONFIRMATIONS = 3;
        int idleChecks = 0;

        while (pagesCrawled.get() < config.maxPages) {
            UrlFrontier.Task task = frontier.nextUrl();
            if (task == null) {
                if (activeWorkers.get() == 0 && ++idleChecks >= IDLE_CONFIRMATIONS) {
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
            idleChecks = 0;

            if (task.depth() > config.maxDepth
                    || !isAllowedDomain(task.url(), config.allowedDomains)
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
                if (count % config.progressEveryN == 0 || count == config.maxPages) {
                    System.out.printf("[%d/%d] %s (depth=%d, %d links, frontier=%d, domains=%d)%n",
                            count, config.maxPages, task.url(), task.depth(),
                            doc.getOutlinks().size(), frontier.size(), frontier.domainCount());
                }

                if (task.depth() < config.maxDepth) {
                    for (String outlink : doc.getOutlinks()) {
                        if (isAllowedDomain(outlink, config.allowedDomains) && !visited.mightContain(outlink)) {
                            frontier.addUrl(outlink, task.depth() + 1, 1);
                        }
                    }
                }
            } finally {
                activeWorkers.decrementAndGet();
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

    private WebDocument fetchWithRetry(String url) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Document document = Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .followRedirects(true)
                        .get();
                return htmlExtractor.extract(url, document);
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    System.out.printf("  [loi] khong the fetch %s sau %d lan thu: %s%n",
                            url, MAX_RETRIES + 1, e.getMessage());
                }
            }
        }
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
        List<WebDocument> result = new ArrayList<>();
        for (WebDocument doc : docs) {
            result.add(doc);
        }
        return result;
    }

    /**
     * Demo/kiem chung PHASE 3: crawl that 100 trang tu seed vnexpress.net,
     * in thong ke, luu ra data/crawled-documents.json.
     */
    public static void main(String[] args) throws IOException {
        CrawlerService crawler = new CrawlerService();
        CrawlConfig config = new CrawlConfig()
                .maxDepth(3)
                .maxPages(100)
                .threadCount(4)
                .allowedDomains(Set.of("vnexpress.net"));

        long start = System.currentTimeMillis();
        List<WebDocument> docs = crawler.crawl(List.of("https://vnexpress.net/"), config);
        long elapsedSec = (System.currentTimeMillis() - start) / 1000;

        System.out.println();
        System.out.println("=== THONG KE CRAWL ===");
        System.out.println("Tong so trang crawl duoc: " + docs.size());
        System.out.println("Thoi gian: " + elapsedSec + "s");
        int totalOutlinks = docs.stream().mapToInt(d -> d.getOutlinks().size()).sum();
        System.out.println("Tong so outlink thu duoc: " + totalOutlinks);
        System.out.println("Trung binh outlink/trang: " + (docs.isEmpty() ? 0 : totalOutlinks / docs.size()));
        System.out.println("Vi du 5 trang dau:");
        docs.stream().limit(5).forEach(d -> System.out.println("  - " + d.getTitle() + " (" + d.getUrl() + ")"));

        saveToJson(docs, "data/crawled-documents.json");
        System.out.println("Da luu vao data/crawled-documents.json");
    }
}
