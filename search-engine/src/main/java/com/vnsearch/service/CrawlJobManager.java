package com.vnsearch.service;

import com.vnsearch.crawler.ConsoleCrawlListener;
import com.vnsearch.crawler.CrawlConfig;
import com.vnsearch.crawler.CrawlListener;
import com.vnsearch.crawler.CrawlerService;
import com.vnsearch.model.WebDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final Map<String, CrawlJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Mot job crawl, voi trang thai duoc bao ve boi may trang thai. */
    private static final class CrawlJob {

        final CrawlerService crawler;
        private volatile CrawlStatus status = CrawlStatus.STARTED;
        volatile String errorMessage;

        CrawlJob(CrawlerService crawler) {
            this.crawler = crawler;
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
        CrawlerService crawler = new CrawlerService()
                .addListener(new ConsoleCrawlListener(25)); // Observer
        CrawlJob job = new CrawlJob(crawler);
        jobs.put(jobId, job);

        executor.submit(() -> {
            try {
                job.transitionTo(CrawlStatus.RUNNING);
                CrawlConfig config = CrawlConfig.builder() // Builder
                        .maxDepth(maxDepth)
                        .maxPages(maxPages)
                        .threadCount(4)
                        .allowedDomains(extractDomains(seedUrls))
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
            }
        });

        return jobId;
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
        status.put("pagesCrawled", job.crawler.getPagesCrawledCount());
        status.put("queueSize", job.crawler.getQueueSize());
        if (job.errorMessage != null) {
            status.put("error", job.errorMessage);
        }
        return status;
    }

    /** So bit cua Bloom Filter cua job gan nhat — dung cho thong ke. */
    public int lastBloomFilterBits() {
        return jobs.values().stream()
                .reduce((first, second) -> second)
                .map(job -> job.crawler.getBloomFilterBits())
                .orElse(0);
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
