package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chạy một phiên crawl quy mô lớn trên NHIỀU domain, dùng để dựng corpus
 * cho phần đánh giá của đồ án.
 *
 * <p><b>Vì sao phải đa domain:</b> corpus cũ chỉ gồm 150 trang của riêng
 * vnexpress.net, khiến PageRank gần như vô nghĩa — liên kết nội bộ trong
 * một tờ báo phản ánh cấu trúc điều hướng (menu, chuyên mục, bài liên
 * quan) chứ không phản ánh uy tín trang. PageRank chỉ có ý nghĩa khi đồ
 * thị chứa liên kết CHÉO giữa các site độc lập. Crawl nhiều báo cùng lúc
 * còn làm tỷ lệ thưa {@code nnz/n²} của ma trận liên kết giảm mạnh, đúng
 * như dự đoán lý thuyết trong báo cáo DSA.
 *
 * <p>Chạy bằng:
 * <pre>
 *   mvnw exec:java -Dexec.mainClass=com.vnsearch.crawler.MultiDomainCrawlRunner \
 *        -Dexec.args="5000 3 data/crawled-multi.json"
 * </pre>
 * Tham số: {@code [maxPages] [maxDepth] [outputPath]} (đều có mặc định).
 */
public class MultiDomainCrawlRunner {

    /** Các báo điện tử lớn của Việt Nam, đủ độc lập để có liên kết chéo thật. */
    private static final List<String> DEFAULT_SEEDS = List.of(
            "https://vnexpress.net/",
            "https://tuoitre.vn/",
            "https://dantri.com.vn/",
            "https://thanhnien.vn/",
            "https://vietnamnet.vn/",
            "https://nhandan.vn/");

    public static void main(String[] args) throws IOException {
        int maxPages = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String outputPath = args.length > 2 ? args[2] : "data/crawled-multi.json";

        Set<String> allowedDomains = new LinkedHashSet<>();
        for (String seed : DEFAULT_SEEDS) {
            String host = URI.create(seed).getHost();
            if (host != null) {
                allowedDomains.add(host.startsWith("www.") ? host.substring(4) : host);
            }
        }

        System.out.println("=== CRAWL DA DOMAIN ===");
        System.out.println("Seeds      : " + DEFAULT_SEEDS.size() + " domain");
        System.out.println("maxPages   : " + maxPages);
        System.out.println("maxDepth   : " + maxDepth);
        System.out.println("Output     : " + outputPath);
        System.out.println();

        // Politeness delay 1s/domain nghia la thong luong toi da = so domain
        // (trang/giay). Dung so thread gap doi so domain de thread khong phai
        // la nut that co, phan con lai da bi politeness khong che.
        CrawlConfig config = CrawlConfig.builder()
                .maxDepth(maxDepth)
                .maxPages(maxPages)
                .threadCount(allowedDomains.size() * 2)
                .allowedDomains(allowedDomains)
                .maxDurationMinutes(90)
                .build();

        CrawlerService crawler = new CrawlerService()
                .addListener(new ConsoleCrawlListener(25)); // Observer
        long start = System.currentTimeMillis();
        List<WebDocument> docs = crawler.crawl(DEFAULT_SEEDS, config);
        long elapsedMs = System.currentTimeMillis() - start;

        CrawlerService.saveToJson(docs, outputPath);
        printStatistics(docs, elapsedMs, outputPath, allowedDomains);
    }

    private static void printStatistics(List<WebDocument> docs, long elapsedMs,
                                         String outputPath, Set<String> allowedDomains) {
        System.out.println();
        System.out.println("=== THONG KE CRAWL ===");
        System.out.printf("Tong so trang    : %d%n", docs.size());
        System.out.printf("Thoi gian        : %.1f phut%n", elapsedMs / 60000.0);
        System.out.printf("Thong luong      : %.2f trang/giay%n", docs.size() / (elapsedMs / 1000.0));

        long totalOutlinks = docs.stream().mapToInt(d -> d.getOutlinks().size()).sum();
        System.out.printf("Tong outlink     : %d (trung binh %.1f/trang)%n",
                totalOutlinks, docs.isEmpty() ? 0 : (double) totalOutlinks / docs.size());

        // Phan bo theo domain - kiem chung crawler khong bi lech han ve mot site.
        Map<String, Integer> perDomain = new LinkedHashMap<>();
        for (WebDocument doc : docs) {
            perDomain.merge(hostOf(doc.getUrl()), 1, Integer::sum);
        }
        System.out.println("Phan bo theo domain:");
        perDomain.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-24s %5d trang%n", e.getKey(), e.getValue()));

        // Lien ket CHEO giua cac domain - chinh la thu lam PageRank co y nghia.
        Set<String> crawledUrls = new LinkedHashSet<>();
        for (WebDocument doc : docs) {
            crawledUrls.add(doc.getUrl());
        }
        long crossDomainLinks = 0;
        long internalLinks = 0;
        for (WebDocument doc : docs) {
            String from = hostOf(doc.getUrl());
            for (String outlink : doc.getOutlinks()) {
                if (!crawledUrls.contains(outlink)) {
                    continue; // chi tinh lien ket noi bo corpus (canh cua do thi PageRank)
                }
                if (hostOf(outlink).equals(from)) {
                    internalLinks++;
                } else {
                    crossDomainLinks++;
                }
            }
        }
        long edges = internalLinks + crossDomainLinks;
        System.out.printf("Canh do thi (nnz): %d (noi bo %d, CHEO domain %d)%n",
                edges, internalLinks, crossDomainLinks);
        if (!docs.isEmpty()) {
            double density = (double) edges / ((double) docs.size() * docs.size());
            System.out.printf("Ty le thua       : %.4f%% (nnz/n^2)%n", density * 100);
        }
        System.out.println("Da luu vao " + outputPath);

        List<String> missing = new ArrayList<>();
        for (String domain : allowedDomains) {
            if (perDomain.keySet().stream().noneMatch(h -> h.endsWith(domain))) {
                missing.add(domain);
            }
        }
        if (!missing.isEmpty()) {
            System.out.println("CANH BAO: khong crawl duoc trang nao tu " + missing);
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : "(khong ro)";
        } catch (Exception e) {
            return "(khong ro)";
        }
    }
}
