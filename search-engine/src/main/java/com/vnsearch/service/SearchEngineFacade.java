package com.vnsearch.service;

import com.vnsearch.crawler.CrawlerService;
import com.vnsearch.datastructure.LRUCache;
import com.vnsearch.datastructure.Trie;
import com.vnsearch.index.IndexPersistence;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.Posting;
import com.vnsearch.model.SearchResponse;
import com.vnsearch.model.SearchResult;
import com.vnsearch.model.WebDocument;
import com.vnsearch.query.PostingListMerger;
import com.vnsearch.query.QueryParser;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.ResultRanker;
import com.vnsearch.ranking.TfIdfScorer;
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
import java.util.stream.Collectors;

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

    @Value("${app.search.cache-size:200}")
    private int cacheSize;

    @Value("${app.ranking.alpha:0.6}")
    private double alpha;

    @Value("${app.ranking.beta:0.3}")
    private double beta;

    @Value("${app.ranking.gamma:0.1}")
    private double gamma;

    private final QueryParser queryParser = new QueryParser();
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
            if (Files.exists(Path.of(indexDataPath))) {
                index = IndexPersistence.load(indexDataPath);
            } else if (Files.exists(Path.of(crawledDataPath))) {
                lastCrawledDocuments = CrawlerService.loadFromJson(crawledDataPath);
                index = buildIndexFrom(lastCrawledDocuments);
            }
        } catch (IOException e) {
            System.err.println("Khong the nap du lieu co san, bat dau voi index rong: " + e.getMessage());
            index = new InvertedIndex();
        }
        recomputePageRank();
        rebuildSuggestTrie();
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

    private void rebuildSuggestTrie() {
        for (WebDocument doc : index.getAllDocuments().values()) {
            if (doc.getTitle() == null || doc.getTitle().isBlank()) {
                continue;
            }
            String normalizedTitle = doc.getTitle().trim().toLowerCase();
            suggestTrie.insert(normalizedTitle);
            for (String word : normalizedTitle.split("\\s+")) {
                String cleaned = word.replaceAll("[^\\p{L}\\p{N}]", "");
                if (cleaned.length() > 1) {
                    suggestTrie.insert(cleaned);
                }
            }
        }
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
        List<String> allRequiredTerms = new ArrayList<>(parsed.mustTerms());
        for (List<String> phrase : parsed.phrases()) {
            allRequiredTerms.addAll(phrase);
        }

        List<Integer> candidates = resolveCandidates(allRequiredTerms, parsed.phrases(), parsed.excludedTerms());

        Map<String, Integer> queryTermFrequency = new HashMap<>();
        for (String term : allRequiredTerms) {
            queryTermFrequency.merge(term, 1, Integer::sum);
        }

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

        if (!normalizedQuery.isBlank()) {
            suggestTrie.insert(normalizedQuery.toLowerCase());
        }
        return response;
    }

    private List<Integer> resolveCandidates(List<String> allRequiredTerms, List<List<String>> phrases,
                                             List<String> excludedTerms) {
        if (allRequiredTerms.isEmpty()) {
            return new ArrayList<>();
        }
        List<List<Posting>> postingLists = new ArrayList<>();
        for (String term : allRequiredTerms) {
            List<Posting> postings = index.getPostings(term);
            if (postings.isEmpty()) {
                return new ArrayList<>(); // 1 term khong xuat hien -> AND ket qua rong
            }
            postingLists.add(postings);
        }
        List<Integer> candidates = PostingListMerger.intersectAll(postingLists);

        if (!candidates.isEmpty() && !phrases.isEmpty()) {
            candidates = candidates.stream()
                    .filter(docId -> phrases.stream().allMatch(phrase -> PostingListMerger.matchesPhrase(index, phrase, docId)))
                    .collect(Collectors.toList());
        }

        if (!candidates.isEmpty() && !excludedTerms.isEmpty()) {
            Set<Integer> excludedDocIds = new HashSet<>();
            for (String excludedTerm : excludedTerms) {
                for (Posting p : index.getPostings(excludedTerm)) {
                    excludedDocIds.add(p.docId());
                }
            }
            candidates = candidates.stream().filter(id -> !excludedDocIds.contains(id)).collect(Collectors.toList());
        }
        return candidates;
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
