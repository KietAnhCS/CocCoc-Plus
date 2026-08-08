package com.vnsearch.crawler;

import com.vnsearch.model.WebDocument;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Tham số: {@code [maxPages] [maxDepth] [outputPath] [--fresh]} (đều có mặc định).
 *
 * <p><b>Chính sách ngôn ngữ: chỉ tiếng Việt và tiếng Anh.</b> Thi hành ở hai
 * tuyến — {@link UrlFilter#NON_VI_EN_HOST_PREFIXES} loại subdomain ngoại ngữ
 * trước khi tải, {@link LanguageFilter} nhận diện theo nội dung sau khi tải và
 * vứt trang không thuộc hai ngôn ngữ này <b>mà không bóc liên kết của nó</b>.
 * Vì thế tập hạt giống có cả {@link #ENGLISH_SEEDS}: bộ lọc chỉ loại bớt, nó
 * không tự sinh ra trang tiếng Anh cho corpus.
 *
 * <p><b>Công crawl không bị phí, nhờ ba cơ chế:</b>
 * <ul>
 *   <li><b>Nối tiếp mặc định.</b> Nếu {@code outputPath} đã tồn tại, corpus cũ
 *       được nạp lại, các trang trong đó không bị tải lại, và phiên mới đi tiếp
 *       từ liên kết của chúng. Chạy lệnh này ba lần với {@code maxPages=2000}
 *       cho corpus khoảng 6.000 trang, chứ không phải 2.000 trang ba lần.
 *       Dùng {@code --fresh} để bỏ corpus cũ và bắt đầu lại.</li>
 *   <li><b>Ghi điểm kiểm tra mỗi 250 trang</b> ({@link CheckpointCrawlListener}),
 *       nên Ctrl+C hay mất điện chỉ mất phần crawl kể từ điểm gần nhất chứ
 *       không mất cả phiên.</li>
 *   <li><b>Ghi nguyên tử</b> ({@link ContentStorage#saveToJson}), nên một lần
 *       ghi bị cắt ngang không phá hỏng corpus đang có.</li>
 * </ul>
 */
public class MultiDomainCrawlRunner {

    /** Các báo điện tử lớn của Việt Nam, đủ độc lập để có liên kết chéo thật. */
    private static final List<String> VIETNAMESE_SEEDS = List.of(
            "https://vnexpress.net/",
            "https://tuoitre.vn/",
            "https://dantri.com.vn/",
            "https://thanhnien.vn/",
            "https://vietnamnet.vn/",
            "https://nhandan.vn/",
            "https://hanoimoi.vn/",
            "https://baochinhphu.vn/",
            "https://www.vietnamplus.vn/",
            "https://tuyensinhso.vn/",
            "https://hcmiu.edu.vn/");

    /**
     * Bản <b>tiếng Anh</b> của chính các toà soạn trên, cộng ba tờ tiếng Anh
     * độc lập.
     *
     * <p><b>Vì sao phải có hạt giống riêng cho tiếng Anh.</b> Chính sách corpus
     * là "tiếng Việt và tiếng Anh", nhưng {@link LanguageFilter} chỉ <i>lọc</i>
     * chứ không <i>tạo ra</i> trang tiếng Anh: nếu mọi hạt giống đều là báo
     * tiếng Việt thì crawler gần như không bao giờ chạm tới một trang tiếng
     * Anh nào, và phần "tiếng Anh" của chính sách thành vô nghĩa.
     *
     * <p><b>Vì sao là bản tiếng Anh của báo Việt chứ không phải BBC/Reuters.</b>
     * Cụm site này <b>trỏ liên kết qua lại thật</b> với phần tiếng Việt (cùng
     * toà soạn, cùng hệ thống bài liên quan), nên chúng đóng góp cạnh CHÉO cho
     * đồ thị PageRank. Một tờ báo quốc tế thì gần như không có liên kết nào trỏ
     * về cụm Việt Nam, nên nó chỉ tạo ra một thành phần liên thông rời — thứ
     * làm PageRank phân mảnh chứ không làm nó giàu thêm.
     */
    private static final List<String> ENGLISH_SEEDS = List.of(
            "https://e.vnexpress.net/",
            "https://en.vietnamnet.vn/",
            "https://en.nhandan.vn/",
            "https://en.baochinhphu.vn/",
            "https://en.vietnamplus.vn/",
            "https://vietnamnews.vn/",
            // KHONG dung tuoitrenews.vn: chung chi TLS cua site het han, moi
            // lan tai deu nem CertPathValidatorException (do duoc o phien crawl
            // 10.017 trang — 0 trang thu duoc tu domain nay). Bo qua loi chung
            // chi thi phai tat xac thuc TLS cho TOAN BO crawler, cai gia qua
            // dat cho mot hat giong.
            "https://english.vov.vn/",
            "https://vir.com.vn/");

    private static final List<String> DEFAULT_SEEDS = concat(VIETNAMESE_SEEDS, ENGLISH_SEEDS);

    /**
     * Nhãn subdomain chỉ ngôn ngữ, cắt bỏ khi rút domain được phép.
     *
     * <p>Không cắt thì {@code allowedDomains} chứa cả {@code vnexpress.net} lẫn
     * {@code e.vnexpress.net} — hai mục cho cùng một toà soạn, làm
     * {@code threadCount} (tính theo số domain) phồng lên vô ích, còn phép
     * {@code endsWith} trong {@link UrlFilter} thì đằng nào cũng đã bao trùm.
     */
    private static final Set<String> LANGUAGE_LABELS = Set.of("www", "e", "en");

    public static void main(String[] args) throws IOException {
        int maxPages = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        String outputPath = args.length > 2 ? args[2] : "data/crawled-multi.json";
        boolean fresh = args.length > 3 && args[3].equalsIgnoreCase("--fresh");

        // NỐI TIẾP là mặc định khi tệp đầu ra đã có. Mặc định cũ — ghi đè —
        // biến mỗi lần chạy lại thành một lần vứt bỏ toàn bộ phiên trước, kể cả
        // khi người chạy chỉ muốn thêm vài trăm trang. Ai thật sự muốn bắt đầu
        // lại từ đầu thì nói rõ bằng --fresh; nhầm lẫn theo chiều đó sửa được,
        // còn nhầm lẫn theo chiều kia thì corpus đã mất rồi.
        List<WebDocument> previous = List.of();
        if (!fresh && Files.exists(Path.of(outputPath))) {
            previous = ContentStorage.loadFromJson(outputPath);
            System.out.printf("Noi tiep corpus san co: %d tai lieu tu %s%n",
                    previous.size(), outputPath);
        }

        Set<String> allowedDomains = new LinkedHashSet<>();
        for (String seed : DEFAULT_SEEDS) {
            String host = URI.create(seed).getHost();
            if (host != null) {
                allowedDomains.add(stripLanguageLabel(host));
            }
        }

        System.out.println("=== CRAWL DA DOMAIN ===");
        System.out.printf("Seeds      : %d (%d tieng Viet + %d tieng Anh) tren %d domain%n",
                DEFAULT_SEEDS.size(), VIETNAMESE_SEEDS.size(), ENGLISH_SEEDS.size(),
                allowedDomains.size());
        System.out.println("Ngon ngu   : CHI tieng Viet va tieng Anh (LanguageFilter)");
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
                .threadCount(Math.min(32, distinctSeedHosts() * 2))
                .allowedDomains(allowedDomains)
                // Tuyen phong thu THU NHAT cho chinh sach "chi vi/en": loai
                // subdomain ngoai ngu TRUOC khi tai, chi bang phep so chuoi.
                // Tuyen thu hai la LanguageFilter, nhin noi dung that sau khi
                // tai — no bat duoc moi thu danh sach nay bo sot, nhung phai tra
                // gia bang mot luot tai trang.
                .excludedHostPrefixes(UrlFilter.NON_VI_EN_HOST_PREFIXES)
                // Politeness 1 giây/host với 18 host cho thông lượng trần
                // ~18 trang/giây, tức 30.000 trang cần ~30 phút Ở TỐC ĐỘ TRẦN.
                // Thực tế luôn thấp hơn (độ trễ mạng, trang bị robots.txt chặn,
                // trang lỗi), nên mốc 90 phút cũ đủ sát để có thể cắt ngang phiên
                // trước khi đạt maxPages — và khi đó số trang thu được là một con
                // số tuỳ tiện phụ thuộc tốc độ mạng hôm đó, không tái lập được.
                // 180 phút để maxPages mới là thứ quyết định phiên kết thúc.
                .maxDurationMinutes(180)
                .build();

        // HAI listener cùng lúc — đúng thứ Observer pattern sinh ra để làm.
        // Thanh tiến trình trả lời "còn bao lâu nữa" cho người đang ngồi nhìn;
        // log trả lời "chuyện gì đã xảy ra" cho người đọc lại sau phiên crawl.
        // Ở chế độ thanh tiến trình, ConsoleCrawlListener chỉ ghi mỗi 200 trang
        // để không cắt ngang dòng đang vẽ (chi tiết ở ProgressBarCrawlListener).
        // Điểm kiểm tra mỗi 250 trang: đủ thưa để chi phí ghi không đáng kể so
        // với thời gian tải, đủ dày để một lần crash chỉ mất chưa tới mười giây
        // công crawl ở tốc độ thực tế ~30 trang/giây.
        // Khai báo tách khỏi chuỗi addListener: điểm kiểm tra cần tham chiếu tới
        // chính crawler để lấy bản chụp corpus, mà biến chưa gán xong thì chưa
        // dùng được trong biểu thức khởi tạo của nó.
        CrawlerService crawler = new CrawlerService();
        crawler.addListener(new ProgressBarCrawlListener(25))
                .addListener(new ConsoleCrawlListener(200))
                .addListener(new CheckpointCrawlListener(
                        crawler::snapshotDocuments, outputPath, 250));
        long start = System.currentTimeMillis();

        List<WebDocument> docs = crawler.crawl(DEFAULT_SEEDS, config, previous);

        long elapsedMs = System.currentTimeMillis() - start;


        ContentStorage.saveToJson(docs, outputPath);
        printBlockStatistics(crawler);
        printStatistics(docs, elapsedMs, outputPath, allowedDomains);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /** Số host phân biệt trong tập hạt giống — trần thông lượng do politeness. */
    private static int distinctSeedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        for (String seed : DEFAULT_SEEDS) {
            String host = URI.create(seed).getHost();
            if (host != null) {
                hosts.add(host);
            }
        }
        return hosts.size();
    }

    /**
     * {@code e.vnexpress.net} -> {@code vnexpress.net}, {@code www.x.vn} ->
     * {@code x.vn}; các host khác giữ nguyên.
     *
     * <p>Chỉ cắt khi phần còn lại vẫn có ít nhất hai nhãn, để không bao giờ
     * biến một host thành một hậu tố quá rộng như {@code com.vn} — mà
     * {@code endsWith} trong {@link UrlFilter} sẽ hiểu là "cho phép mọi trang
     * .com.vn".
     */
    private static String stripLanguageLabel(String host) {
        int dot = host.indexOf('.');
        if (dot <= 0) {
            return host;
        }
        String first = host.substring(0, dot).toLowerCase(Locale.ROOT);
        String rest = host.substring(dot + 1);
        if (LANGUAGE_LABELS.contains(first) && rest.indexOf('.') > 0) {
            return rest;
        }
        return host;
    }

    /**
     * Số liệu của từng khối trong sơ đồ kiến trúc crawler — đưa thẳng vào
     * báo cáo để chứng minh mỗi khối thật sự có việc để làm.
     */
    private static void printBlockStatistics(CrawlerService crawler) {
        System.out.println();
        System.out.println("=== THONG KE THEO TUNG KHOI ===");

        DnsResolver dns = crawler.getDnsResolver();
        System.out.printf("DNS Resolver   : %d host trong cache, ty le trung %.1f%%, %d host chet bi loai som%n",
                dns.getCachedHostCount(), dns.hitRate() * 100, dns.getResolveFailures());

        HtmlDownloader downloader = crawler.getHtmlDownloader();
        System.out.printf("HTML Downloader: tai %d trang, %d lan thu lai, %d that bai%n",
                downloader.getDownloadedCount(), downloader.getRetryCount(),
                downloader.getFailedCount());

        LanguageFilter language = crawler.getLanguageFilter();
        System.out.printf("Language Filter: GIU %d tieng Viet + %d tieng Anh + %d chua ro, VUT %d ngoai ngu%n",
                language.getAcceptedVietnameseCount(), language.getAcceptedEnglishCount(),
                language.getAcceptedUndeterminedCount(), language.getRejectedCount());
        Map<String, Long> rejectedByLanguage = language.getRejectedByLanguage();
        if (!rejectedByLanguage.isEmpty()) {
            StringBuilder line = new StringBuilder("                 (");
            rejectedByLanguage.forEach((code, count) -> line.append(code).append(' ')
                    .append(count).append(" | "));
            line.setLength(line.length() - 3);
            System.out.println(line.append(')'));
        }

        ContentSeenFilter contentSeen = crawler.getContentSeenFilter();
        System.out.printf("Content Seen?  : %d noi dung phan biet, VUT %d ban trung, %d trang than bai rong%n",
                contentSeen.size(), contentSeen.getDuplicateCount(), contentSeen.getBlankSkippedCount());

        UrlFilter filter = crawler.getUrlFilter();
        System.out.printf("URL Filter     : nhan %d, loai %d%n",
                filter.getAcceptedCount(), filter.getTotalRejectedCount());
        System.out.printf("                 (domain %d | duoi tep %d | do sau %d | scheme %d | robots %d)%n",
                filter.getRejectedByDomainCount(), filter.getRejectedByExtensionCount(),
                filter.getRejectedByDepthCount(), filter.getRejectedBySchemeCount(),
                filter.getRejectedByRobotsCount());

        UrlSeenFilter urlSeen = crawler.getUrlSeenFilter();
        System.out.printf("URL Seen?      : %d URL phan biet, bo loc %d bit (%.1f KB), %d ham bam%n",
                urlSeen.getSeenCount(), urlSeen.getNumBits(),
                urlSeen.getNumBits() / 8192.0, urlSeen.getNumHashes());

        UrlStorage urlStorage = urlSeen.getUrlStorage();
        System.out.printf("URL Storage    : %s%n", urlStorage.isEnabled()
                ? urlStorage.getWrittenCount() + " URL da ghi vao " + urlStorage.getPath()
                : "tat (dung CrawlConfig.urlStoragePath de bat)");
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

        // Thanh phan ngon ngu cua corpus - kiem chung chinh sach chi vi/en.
        Map<String, Integer> perLanguage = new LinkedHashMap<>();
        for (WebDocument doc : docs) {
            String lang = doc.getLanguage() == null || doc.getLanguage().isBlank()
                    ? "(chua gan)" : doc.getLanguage();
            perLanguage.merge(lang, 1, Integer::sum);
        }
        System.out.println("Phan bo theo ngon ngu:");
        perLanguage.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-24s %5d trang (%.1f%%)%n", e.getKey(),
                        e.getValue(), docs.isEmpty() ? 0 : 100.0 * e.getValue() / docs.size()));

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

    /** Dùng lại phép rút host của {@link DnsResolver}, chỉ thêm nhãn cho ca không đọc được. */
    private static String hostOf(String url) {
        String host = DnsResolver.hostOf(url);
        return host != null ? host : "(khong ro)";
    }
}
