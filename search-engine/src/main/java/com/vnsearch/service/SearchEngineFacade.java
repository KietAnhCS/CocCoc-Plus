package com.vnsearch.service;

import com.vnsearch.crawler.CrawlerService;
import com.vnsearch.datastructure.LRUCache;
import com.vnsearch.datastructure.Trie;
import com.vnsearch.index.IndexPersistence;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.SearchResponse;
import com.vnsearch.model.SearchResult;
import com.vnsearch.model.WebDocument;
import com.vnsearch.query.CandidateResolver;
import com.vnsearch.query.QueryParser;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.ResultRanker;
import com.vnsearch.ranking.TfIdfScorer;
import com.vnsearch.storage.DocumentRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lop dieu phoi trung tam ("facade"), noi cac phase lai voi nhau thanh
 * mot search engine hoan chinh cho tang REST API (PHASE 6):
 * crawl (PHASE 3) -&gt; index (PHASE 4) -&gt; rank (PHASE 5) -&gt; phuc vu
 * qua controller. Day la lop "keo dan", KHONG chua thuat toan DSA moi -
 * moi logic loi da nam trong cac lop da cai o PHASE 2-5.
 */
@Service
public class SearchEngineFacade {

    @Value("${app.index.data-path}")
    private String indexDataPath;

    @Value("${app.crawler.data-path}")
    private String crawledDataPath;

    @Value("${app.seed.data-path:data/seed-documents.json}")
    private String seedDataPath;

    @Value("${app.search.cache-size:200}")
    private int cacheSize;

    /** Bat/tat viec nap corpus tu PostgreSQL (mac dinh tat de chay duoc khi khong co CSDL). */
    @Value("${app.storage.postgres.enabled:false}")
    private boolean postgresEnabled;

    @Value("${app.storage.postgres.url:" + DocumentRepository.DEFAULT_URL + "}")
    private String postgresUrl;

    @Value("${app.storage.postgres.user:" + DocumentRepository.DEFAULT_USER + "}")
    private String postgresUser;

    @Value("${app.storage.postgres.password:" + DocumentRepository.DEFAULT_PASSWORD + "}")
    private String postgresPassword;

    @Value("${app.ranking.alpha:0.6}")
    private double alpha;

    @Value("${app.ranking.beta:0.3}")
    private double beta;

    @Value("${app.ranking.gamma:0.1}")
    private double gamma;

    private final VietnameseTokenizer tokenizer = new VietnameseTokenizer();
    private final QueryParser queryParser = new QueryParser(tokenizer);
    private final TfIdfScorer tfIdfScorer = new TfIdfScorer();
    private final PageRankService pageRankService = new PageRankService();
    private final Trie suggestTrie = new Trie();
    private final Map<String, CrawlJob> crawlJobs = new ConcurrentHashMap<>();
    private final ExecutorService crawlExecutor = Executors.newCachedThreadPool();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    private volatile InvertedIndex index = new InvertedIndex();
    private volatile Map<Integer, Double> pageRankScores = Map.of();
    private volatile List<WebDocument> lastCrawledDocuments = List.of();
    private volatile ResultRanker resultRanker;
    private volatile LRUCache<String, SearchResponse> searchCache;

    private static final class CrawlJob {
        final CrawlerService crawler;
        volatile String status = "STARTED";
        volatile String errorMessage;

        CrawlJob(CrawlerService crawler) {
            this.crawler = crawler;
        }
    }

    @PostConstruct
    public void init() {
        resultRanker = new ResultRanker(alpha, beta, gamma);
        searchCache = new LRUCache<>(cacheSize);
        try {
            if (postgresEnabled && loadFromPostgres()) {
                System.out.println("Da nap corpus tu PostgreSQL");
            } else if (Files.exists(Path.of(indexDataPath))) {
                index = IndexPersistence.load(indexDataPath);
            } else if (Files.exists(Path.of(crawledDataPath))) {
                lastCrawledDocuments = CrawlerService.loadFromJson(crawledDataPath);
                index = buildIndexFrom(lastCrawledDocuments);
            } else if (Files.exists(Path.of(seedDataPath))) {
                // Chua tung crawl lan nao (vd. vua clone repo ve) -> dung mau seed
                // nho (~40 tai lieu that, da rut gon) di kem trong repo de demo
                // ngay ma khong can crawl mang that.
                lastCrawledDocuments = CrawlerService.loadFromJson(seedDataPath);
                index = buildIndexFrom(lastCrawledDocuments);
                System.out.println("Khong tim thay du lieu da crawl, dung seed mau (" + seedDataPath + ")");
            }
        } catch (IOException e) {
            System.err.println("Khong the nap du lieu co san, bat dau voi index rong: " + e.getMessage());
            index = new InvertedIndex();
        }
        recomputePageRank();
        rebuildSuggestTrie();
    }

    /**
     * Nap corpus tu PostgreSQL roi DUNG LAI chi muc dao trong bo nho.
     *
     * <p>CSDL chi dong vai tro kho luu tru: viec tim kiem van do chi muc dao
     * tu cai dam nhiem. Tra ve false neu khong ket noi duoc hoac CSDL rong,
     * de he thong tu dong lui ve dung file JSON.
     */
    private boolean loadFromPostgres() {
        try (DocumentRepository repo = new DocumentRepository(postgresUrl, postgresUser, postgresPassword)) {
            List<WebDocument> docs = repo.findAll();
            if (docs.isEmpty()) {
                return false;
            }
            lastCrawledDocuments = docs;
            index = buildIndexFrom(docs);
            return true;
        } catch (Exception e) {
            System.err.println("Khong nap duoc tu PostgreSQL (" + e.getMessage() + "), dung file JSON thay the");
            return false;
        }
    }

    private InvertedIndex buildIndexFrom(List<WebDocument> docs) {
        InvertedIndex newIndex = new InvertedIndex();
        List<WebDocument> sorted = new ArrayList<>(docs);
        sorted.sort((a, b) -> Integer.compare(a.getDocId(), b.getDocId()));
        for (WebDocument doc : sorted) {
            newIndex.addDocument(doc);
        }
        return newIndex;
    }

    private void recomputePageRank() {
        pageRankScores = index.getTotalDocs() > 0
                ? pageRankService.computePageRank(index.getAllDocuments()).scores()
                : Map.of();
    }

    /** So lan toi thieu mot cum tu phai xuat hien trong corpus de duoc dem lam goi y. */
    private static final int MIN_SUGGESTION_FREQUENCY = 3;

    /**
     * Dung lai Trie goi y tu cac CUM TU CO NGHIA trich ra tu tieu de tai lieu.
     *
     * <p>Ban dau ham nay chen nguyen ca tieu de lam mot goi y, dong thoi chen
     * tung tieng le. Ca hai deu sai:
     * <ul>
     *   <li>Nguyen tieu de tao ra goi y dai loang ngoang, khong ai go het.</li>
     *   <li>Tieng le trong tieng Viet phan lon KHONG phai tu ("cong", "the",
     *       "kinh" deu vo nghia khi dung mot minh) nen goi y ra toan rac.</li>
     * </ul>
     * Thay vao do, tokenize tieu de bang chinh {@link VietnameseTokenizer} roi
     * lay: (1) cac tu ghep ma tokenizer nhan ra, (2) cac cap token lien tiep.
     * Ca hai deu la don vi ma nguoi dung thuc su go.
     *
     * <p>Loc them hai buoc: bo tieu de khong phai tieng Viet (corpus co lan
     * bai tieng Anh cua VnExpress International, truoc day lam goi y hien ra
     * "the city that helped vietnam..."), va chi giu cum tu xuat hien tu
     * {@link #MIN_SUGGESTION_FREQUENCY} lan tro len de loai nhieu.
     *
     * <p>Moi cum tu duoc chen HAI lan - duoi khoa co dau va khoa khong dau -
     * nhung cung tro toi mot chuoi hien thi co dau, de nguoi go "cong nghe"
     * van nhan duoc goi y "cong nghe" dung chinh ta.
     */
    private void rebuildSuggestTrie() {
        // Phai xoa sach truoc khi dung lai: neu chi insert them, cac tieu de
        // cua corpus CU van con nam trong trie sau moi lan crawl/reindex.
        suggestTrie.clear();

        Map<String, Integer> phraseFrequency = new HashMap<>();
        for (WebDocument doc : index.getAllDocuments().values()) {
            String title = doc.getTitle();
            if (title == null || title.isBlank() || !looksVietnamese(title)) {
                continue;
            }
            List<VietnameseTokenizer.Token> tokens = tokenizer.tokenize(title);
            for (int i = 0; i < tokens.size(); i++) {
                String term = tokens.get(i).term();
                // Tu ghep (tokenizer da noi bang "_") von la mot tu hoan chinh.
                if (term.indexOf('_') >= 0) {
                    phraseFrequency.merge(term.replace('_', ' '), 1, Integer::sum);
                }
                // Cap token lien tiep: bat cac cum nguoi dung hay go ma tu dien
                // tu ghep chua kip co, vi du "bong da Viet Nam".
                if (i + 1 < tokens.size()) {
                    String bigram = (term + " " + tokens.get(i + 1).term()).replace('_', ' ');
                    phraseFrequency.merge(bigram, 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<String, Integer> entry : phraseFrequency.entrySet()) {
            if (entry.getValue() < MIN_SUGGESTION_FREQUENCY) {
                continue;
            }
            String phrase = entry.getKey();
            int frequency = entry.getValue();
            suggestTrie.insert(phrase, phrase, frequency);
            String withoutDiacritics = VietnameseTokenizer.stripDiacritics(phrase);
            if (!withoutDiacritics.equals(phrase)) {
                suggestTrie.insert(withoutDiacritics, phrase, frequency);
            }
        }
    }

    /**
     * Doan xem mot tieu de co phai tieng Viet khong.
     *
     * <p>Dung dau thanh dieu lam dau hieu: van ban tieng Viet that gan nhu
     * luon co it nhat mot nguyen am mang dau trong mot cau day du. Tieu de
     * tieng Anh thi khong bao gio co. Nguong 15 ky tu de khong loai nham cac
     * tieu de rat ngan (vi du "Video") von co the khong co dau nao.
     */
    private boolean looksVietnamese(String title) {
        String trimmed = title.trim();
        if (trimmed.length() < 15) {
            return true;
        }
        return !VietnameseTokenizer.stripDiacritics(trimmed).equals(trimmed);
    }

    public SearchResponse search(String rawQuery, int page, int size) {
        long start = System.currentTimeMillis();
        String normalizedQuery = rawQuery == null ? "" : rawQuery.trim();
        String cacheKey = normalizedQuery.toLowerCase() + "|p" + page + "|s" + size;

        SearchResponse cached = searchCache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();

        QueryParser.ParsedQuery parsed = queryParser.parse(normalizedQuery);
        // Dung CHUNG bo phan giai ung vien voi bo danh gia chat luong (com.vnsearch.eval),
        // de nhung gi duoc do dung bang nhung gi duoc phuc vu.
        CandidateResolver.ResolvedQuery resolved = CandidateResolver.resolve(index, parsed);
        List<Integer> candidates = resolved.candidateDocIds();
        Map<String, Integer> queryTermFrequency = resolved.queryTermFrequency();

        int topN = Math.max(page * size, size);
        List<ResultRanker.RankedResult> ranked = resultRanker.rank(
                candidates, queryTermFrequency, index, tfIdfScorer, pageRankScores, topN);

        int fromIndex = Math.min((Math.max(page, 1) - 1) * size, ranked.size());
        int toIndex = Math.min(fromIndex + size, ranked.size());
        List<SearchResult> pageResults = new ArrayList<>();
        for (ResultRanker.RankedResult r : ranked.subList(fromIndex, toIndex)) {
            pageResults.add(new SearchResult(
                    r.document().getTitle(), r.document().getUrl(), r.snippet(),
                    r.finalScore(), r.tfidfScore(), r.pageRankScore(), r.document().getCrawledAt()));
        }

        long elapsed = System.currentTimeMillis() - start;
        SearchResponse response = new SearchResponse(normalizedQuery, candidates.size(), page, elapsed, pageResults);
        searchCache.put(cacheKey, response);

        // Truy van that cua nguoi dung la nguon goi y tot nhat, nen ghi lai
        // ngay. Chen ca duoi khoa khong dau de lan sau go kieu nao cung ra.
        if (!normalizedQuery.isBlank() && !candidates.isEmpty()) {
            String queryKey = normalizedQuery.toLowerCase();
            suggestTrie.insert(queryKey, queryKey, 1);
            String withoutDiacritics = VietnameseTokenizer.stripDiacritics(queryKey);
            if (!withoutDiacritics.equals(queryKey)) {
                suggestTrie.insert(withoutDiacritics, queryKey, 1);
            }
        }
        return response;
    }

    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return suggestTrie.getSuggestions(prefix.trim().toLowerCase(), limit);
    }

    public String startCrawl(List<String> seedUrls, int maxDepth, int maxPages) {
        String jobId = UUID.randomUUID().toString();
        CrawlerService crawler = new CrawlerService();
        CrawlJob job = new CrawlJob(crawler);
        crawlJobs.put(jobId, job);

        crawlExecutor.submit(() -> {
            job.status = "RUNNING";
            try {
                CrawlerService.CrawlConfig config = new CrawlerService.CrawlConfig()
                        .maxDepth(maxDepth)
                        .maxPages(maxPages)
                        .threadCount(4)
                        .allowedDomains(extractDomains(seedUrls));
                List<WebDocument> docs = crawler.crawl(seedUrls, config);
                lastCrawledDocuments = docs;
                CrawlerService.saveToJson(docs, crawledDataPath);

                index = buildIndexFrom(docs);
                IndexPersistence.save(index, indexDataPath);
                recomputePageRank();
                rebuildSuggestTrie();
                searchCache = new LRUCache<>(cacheSize);

                job.status = "DONE";
            } catch (Exception e) {
                job.status = "FAILED";
                job.errorMessage = e.getMessage();
            }
        });

        return jobId;
    }

    private Set<String> extractDomains(List<String> seedUrls) {
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

    public Map<String, Object> getCrawlStatus(String jobId) {
        CrawlJob job = crawlJobs.get(jobId);
        if (job == null) {
            return null;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", job.status);
        status.put("pagesCrawled", job.crawler.getPagesCrawledCount());
        status.put("queueSize", job.crawler.getQueueSize());
        if (job.errorMessage != null) {
            status.put("error", job.errorMessage);
        }
        return status;
    }

    public void reindex() throws IOException {
        List<WebDocument> docs = lastCrawledDocuments;
        if (docs.isEmpty() && Files.exists(Path.of(crawledDataPath))) {
            docs = CrawlerService.loadFromJson(crawledDataPath);
            lastCrawledDocuments = docs;
        }
        index = buildIndexFrom(docs);
        IndexPersistence.save(index, indexDataPath);
        recomputePageRank();
        rebuildSuggestTrie();
        searchCache = new LRUCache<>(cacheSize);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalDocuments", index.getTotalDocs());
        stats.put("totalTerms", index.getTermCount());

        long indexSizeBytes = 0;
        try {
            Path path = Path.of(indexDataPath);
            if (Files.exists(path)) {
                indexSizeBytes = Files.size(path);
            }
        } catch (IOException ignored) {
            // giu 0 neu khong doc duoc kich thuoc file
        }
        stats.put("indexSizeBytes", indexSizeBytes);

        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        double hitRate = (hits + misses) == 0 ? 0.0 : (double) hits / (hits + misses);
        stats.put("cacheHitRate", hitRate);

        int bloomFilterBits = crawlJobs.values().stream()
                .reduce((first, second) -> second)
                .map(job -> job.crawler.getBloomFilterBits())
                .orElse(0);
        stats.put("bloomFilterBits", bloomFilterBits);

        return stats;
    }
}
