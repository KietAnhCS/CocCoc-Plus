package com.vnsearch.service;

import com.vnsearch.crawler.ContentStorage;
import com.vnsearch.datastructure.LRUCache;
import com.vnsearch.index.IndexPersistence;
import com.vnsearch.index.InvertedIndex;
import com.vnsearch.index.SearchIndex;
import com.vnsearch.index.Tokenizer;
import com.vnsearch.model.SearchResponse;
import com.vnsearch.model.SearchResult;
import com.vnsearch.model.WebDocument;
import com.vnsearch.query.CandidateResolver;
import com.vnsearch.query.QueryParser;
import com.vnsearch.ranking.PageRankService;
import com.vnsearch.ranking.RelevanceScorer;
import com.vnsearch.ranking.ResultRanker;
import com.vnsearch.ranking.ScorerFactory;
import com.vnsearch.storage.DocumentStore;
import com.vnsearch.storage.JsonDocumentStore;
import com.vnsearch.storage.PostgresDocumentStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>Facade pattern</b> — lop dieu phoi trung tam, noi cac phase lai thanh mot
 * search engine hoan chinh cho tang REST API:
 * {@code crawl -> index -> rank -> phuc vu}.
 *
 * <p><b>Chi con dieu phoi.</b> Truoc day lop nay dai 420 dong va ganh <i>bay</i>
 * trach nhiem. Nay moi trach nhiem da ve dung cho cua no:
 * <table border="1">
 *   <tr><th>Truoc day trong Facade</th><th>Nay o</th></tr>
 *   <tr><td>Nap du lieu tu 4 nguon (chuoi {@code else if})</td>
 *       <td>{@link DocumentStore} — Strategy</td></tr>
 *   <tr><td>Dung chi muc (lap lai tien de sort o 3 noi)</td>
 *       <td>{@link IndexBuilder}</td></tr>
 *   <tr><td>Quan ly job crawl ({@code String status})</td>
 *       <td>{@link CrawlJobManager} + {@link CrawlStatus} — State</td></tr>
 *   <tr><td>Dung Trie goi y</td><td>{@link SuggestionService}</td></tr>
 *   <tr><td>Doan ngon ngu</td><td>{@link LanguageDetector}</td></tr>
 *   <tr><td>Chon scorer (chon cung {@code new TfIdfScorer()})</td>
 *       <td>{@link ScorerFactory} — Factory + Decorator</td></tr>
 * </table>
 *
 * <p>Lop nay KHONG chua thuat toan DSA nao — moi logic loi nam trong cac lop
 * chuyen trach.
 */
@Service
public class SearchEngineFacade {

    private static final Logger log = LoggerFactory.getLogger(SearchEngineFacade.class);

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

    @Value("${app.storage.postgres.url:jdbc:postgresql://localhost:5432/vnsearch}")
    private String postgresUrl;

    @Value("${app.storage.postgres.user:vnsearch}")
    private String postgresUser;

    @Value("${app.storage.postgres.password:vnsearch}")
    private String postgresPassword;

    // --- Phu thuoc, tiem qua CONSTRUCTOR (khong phai field injection) ---
    private final Tokenizer tokenizer;
    private final QueryParser queryParser;
    private final IndexBuilder indexBuilder;
    private final SuggestionService suggestionService;
    private final CrawlJobManager crawlJobManager;
    private final ScorerFactory scorerFactory;
    private final PageRankService pageRankService;
    private final ResultRanker resultRanker;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    private volatile SearchIndex index;
    private volatile Map<Integer, Double> pageRankScores = Map.of();
    private volatile RelevanceScorer scorer;
    private volatile LRUCache<String, SearchResponse> searchCache;

    public SearchEngineFacade(Tokenizer tokenizer,
                               IndexBuilder indexBuilder,
                               SuggestionService suggestionService,
                               CrawlJobManager crawlJobManager,
                               ScorerFactory scorerFactory,
                               PageRankService pageRankService) {
        this.tokenizer = tokenizer;
        this.indexBuilder = indexBuilder;
        this.suggestionService = suggestionService;
        this.crawlJobManager = crawlJobManager;
        this.scorerFactory = scorerFactory;
        this.pageRankService = pageRankService;
        // BAT BIEN: query parser phai dung CHINH tokenizer da dung luc index.
        this.queryParser = new QueryParser(tokenizer);
        this.resultRanker = new ResultRanker();
        this.index = new InvertedIndex(tokenizer);
    }

    @PostConstruct
    public void init() {
        searchCache = new LRUCache<>(cacheSize);
        try {
            loadCorpus();
        } catch (IOException e) {
            log.error("Khong the nap du lieu co san, bat dau voi index rong", e);
            index = new InvertedIndex(tokenizer);
        }
        refreshDerivedState();
    }

    /**
     * Nap corpus theo chuoi du phong — nay la DU LIEU (mot danh sach) thay vi
     * CAU TRUC DIEU KHIEN (chuoi {@code else if}).
     *
     * <p>Them mot nguon moi = them mot dong vao {@link #buildStoreChain()},
     * khong sua ham nay.
     */
    private void loadCorpus() throws IOException {
        // Chi muc da dung san la duong nhanh nhat: khong phai index lai.
        if (Files.exists(Path.of(indexDataPath))) {
            try {
                SearchIndex prebuilt = IndexPersistence.load(indexDataPath, tokenizer);
                // Mot chi muc RONG khong phai la chi muc dung duoc. Truong hop
                // that da gap: mot lan crawl thu that bai de lai index.json 159
                // byte, va vi duong nhanh nay chi hoi "tep co ton tai khong",
                // ung dung nap tep rong roi RETURN — che mat ca corpus mau di
                // kem repo. Ket qua: moi truy van tra ve 0, /api/health bao 503,
                // va trong Docker thi container vao vong khoi dong lai vo han.
                if (prebuilt.getTotalDocs() > 0) {
                    index = prebuilt;
                    log.info("Da nap chi muc dung san tu {} ({} tai lieu)",
                            indexDataPath, prebuilt.getTotalDocs());
                    return;
                }
                log.warn("Chi muc dung san tai {} khong co tai lieu nao. Bo qua va"
                        + " dung lai tu corpus goc.", indexDataPath);
            } catch (IOException | RuntimeException e) {
                // Chi muc dung san la CACHE dan xuat, khong phai nguon su that:
                // mot file hong hoac ghi boi phien ban dinh dang cu KHONG duoc
                // phep lam sap ung dung. Bo qua no va dung lai tu corpus goc.
                log.warn("Khong doc duoc chi muc dung san tai {} ({}). Se dung lai tu corpus goc;"
                        + " xoa file nay de het canh bao.", indexDataPath, e.toString());
            }
        }
        for (DocumentStore store : buildStoreChain()) {
            if (!store.isAvailable()) {
                continue;
            }
            List<WebDocument> docs = store.loadAll();
            // Nguon RONG khong phai la nguon. `isAvailable()` cua
            // JsonDocumentStore chi hoi "tep co ton tai khong", nen mot tep
            // chua dung `[]` — thu ma mot phien crawl hong de lai — van duoc
            // coi la kha dung va CHAN mat cac tang du phong phia sau. Ca chuoi
            // du phong sinh ra chinh de tranh dieu do.
            if (docs.isEmpty()) {
                log.warn("Bo qua nguon {}: khong co tai lieu nao.", store.describe());
                continue;
            }
            index = indexBuilder.build(docs);
            log.info("Da nap corpus tu {} ({} tai lieu)", store.describe(), docs.size());
            persistIndex();
            return;
        }
        log.warn("Khong tim thay nguon du lieu nao, bat dau voi index rong");
    }

    /**
     * Ghi chi muc vua dung ra dia de <b>lan khoi dong sau khong phai dung lai</b>.
     *
     * <p><b>Vi sao doan nay tung thieu, va thieu no ton bao nhieu.</b> Dau
     * {@link #loadCorpus} co mot duong nhanh: neu tep chi muc ton tai thi nap
     * thang, khoi phai lap chi muc. Nhung khong co cho nao ghi tep do ra ca —
     * chi {@link #reindex} va {@link #startCrawl} moi ghi. Nen voi mot he thong
     * chi crawl bang dong lenh (dung cach dang dung), tep chi muc <b>khong bao
     * gio ton tai</b>, va duong nhanh kia khong bao gio chay.
     *
     * <p>Do duoc tren corpus 30.017 trang: khoi dong mat <b>58,5 giay</b>, va
     * con so do lap lai y het o moi lan khoi dong sau. Bang chung gian tiep nam
     * ngay trong {@link #getStats}: {@code indexSizeBytes} luon bang 0, nghia la
     * tep chi muc khong ton tai.
     *
     * <p><b>Loi ghi khong duoc phep lam hong lan khoi dong.</b> Chi muc dung san
     * la <i>cache dan xuat</i>, khong phai nguon su that — dia day hay khong co
     * quyen ghi thi ung dung van phai phuc vu duoc, chi la lan sau khoi dong lai
     * cham. Vi vay bat het ngoai le tai day thay vi de no noi len.
     */
    private void persistIndex() {
        if (!(index instanceof InvertedIndex invertedIndex)) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            IndexPersistence.save(invertedIndex, indexDataPath);
            log.info("Da ghi chi muc ra {} ({} ms) — lan khoi dong sau se nap thang tu day.",
                    indexDataPath, System.currentTimeMillis() - start);
        } catch (IOException | RuntimeException e) {
            log.warn("Khong ghi duoc chi muc ra {} ({}). He thong van chay binh thuong,"
                    + " nhung lan khoi dong sau se phai lap chi muc lai.", indexDataPath, e.toString());
        }
    }

    private List<DocumentStore> buildStoreChain() {
        List<DocumentStore> chain = new ArrayList<>();
        if (postgresEnabled) {
            chain.add(new PostgresDocumentStore(postgresUrl, postgresUser, postgresPassword));
        }
        chain.add(new JsonDocumentStore(crawledDataPath, "corpus da crawl"));
        // Tang cuoi: mau seed di kem repo, de nguoi vua clone ve chay duoc NGAY.
        chain.add(new JsonDocumentStore(seedDataPath, "seed mau"));
        return chain;
    }

    /** Tinh lai moi thu phu thuoc vao chi muc: PageRank, scorer, Trie goi y, cache. */
    private void refreshDerivedState() {
        pageRankScores = index.getTotalDocs() > 0
                ? pageRankService.computePageRank(index.getAllDocuments()).scores()
                : Map.of();
        scorer = scorerFactory.create(pageRankScores); // Factory + Decorator
        suggestionService.rebuild(index);
        searchCache = new LRUCache<>(cacheSize);
        log.info("Scorer dang dung: {}", scorer.name());
    }

    public SearchResponse search(String rawQuery, int page, int size) {
        long start = System.currentTimeMillis();
        String normalizedQuery = rawQuery == null ? "" : rawQuery.trim();

        // Doc tham chieu cache MOT lan vao bien cuc bo: neu doc lai o cuoi ham,
        // mot lan reindex xen giua co the khien ket qua CU bi ghi vao cache MOI.
        LRUCache<String, SearchResponse> cache = searchCache;
        SearchIndex currentIndex = index;
        RelevanceScorer currentScorer = scorer;
        // pageRankScores cung phai duoc chup, vi dung ly do: mot lan reindex xen
        // giua se doi truong nay, va khi do ket qua tra ve ghep chi muc CU voi
        // diem PageRank MOI. Truoc day ba truong tren duoc chup con truong nay
        // thi doc thang — dung loai bat nhat ma chinh nhan xet o tren canh bao.
        Map<Integer, Double> currentPageRank = pageRankScores;

        String cacheKey = normalizedQuery.toLowerCase(Locale.ROOT) + "|p" + page + "|s" + size;
        SearchResponse cached = cache.get(cacheKey);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();

        QueryParser.ParsedQuery parsed = queryParser.parse(normalizedQuery);
        // Dung CHUNG bo phan giai ung vien voi bo danh gia chat luong, de nhung
        // gi duoc DO dung bang nhung gi duoc PHUC VU.
        CandidateResolver.ResolvedQuery resolved = CandidateResolver.resolve(currentIndex, parsed);
        List<Integer> candidates = resolved.candidateDocIds();

        int topN = Math.max(page * size, size);
        List<ResultRanker.RankedResult> ranked = resultRanker.rank(
                candidates, resolved.queryTermFrequency(), currentIndex,
                currentScorer, currentPageRank, topN);

        int fromIndex = Math.min((Math.max(page, 1) - 1) * size, ranked.size());
        int toIndex = Math.min(fromIndex + size, ranked.size());
        List<SearchResult> pageResults = new ArrayList<>(toIndex - fromIndex);
        for (ResultRanker.RankedResult r : ranked.subList(fromIndex, toIndex)) {
            pageResults.add(new SearchResult(
                    r.document().getTitle(), r.document().getUrl(), r.snippet(),
                    r.finalScore(), r.pageRankScore(), r.document().getCrawledAt()));
        }

        long elapsed = System.currentTimeMillis() - start;
        // Tra ve `size` DA DUOC AP DUNG, khong phai `size` client gui len — xem
        // Javadoc cua SearchResponse.
        SearchResponse response = new SearchResponse(
                normalizedQuery, candidates.size(), page, size, elapsed, pageResults,
                resolved.droppedTerms());
        cache.put(cacheKey, response);

        // Truy van THAT cua nguoi dung la nguon goi y tot nhat. Chi hoc tu truy
        // van CO ket qua, de khong hoc phai loi chinh ta.
        if (!candidates.isEmpty()) {
            suggestionService.learnFromQuery(normalizedQuery);
        }
        return response;
    }

    public List<String> suggest(String prefix, int limit) {
        return suggestionService.suggest(prefix, limit);
    }

    public String startCrawl(List<String> seedUrls, int maxDepth, int maxPages) {
        return crawlJobManager.start(seedUrls, maxDepth, maxPages, docs -> {
            try {
                // Ghi ra dia TRUOC khi dung chi muc: tu day tro di, tep moi la
                // nguon su that cua corpus — khong con ban nao trong bo nho.
                ContentStorage.saveToJson(docs, crawledDataPath);
                index = indexBuilder.build(docs);
                IndexPersistence.save((InvertedIndex) index, indexDataPath);
                refreshDerivedState();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    public Map<String, Object> getCrawlStatus(String jobId) {
        return crawlJobManager.getStatus(jobId);
    }

    /**
     * Dung lai chi muc tu corpus tren dia.
     *
     * <p><b>Doc lai tu dia thay vi giu mot ban trong bo nho.</b> Truoc day lop
     * nay co truong {@code lastCrawledDocuments} giu NGUYEN ca corpus — ke ca
     * {@code bodyText} day du cua moi trang — chi de phuc vu ham nay. Do la mot
     * cai gia rat dat cho mot thao tac quan tri hiem khi duoc goi: tren corpus
     * 2.518 trang, rieng phan van ban do la 34 MB, va no ton tai suot vong doi
     * ung dung.
     *
     * <p>Te hon, no lam vo hieu chinh phep toi uu ma chi muc vua ap dung: chi
     * muc luu than bai o dang NEN, nhung neu mot truong khac van giu ban nguyen
     * van thi tong bo nho khong giam mot byte nao.
     *
     * <p>Doi lai la mot lan doc dia moi khi goi {@code /api/admin/reindex}. Do
     * la danh doi dung: reindex khong nam tren duong chay cua truy van.
     */
    public void reindex() throws IOException {
        List<WebDocument> docs = List.of();
        if (Files.exists(Path.of(crawledDataPath))) {
            docs = ContentStorage.loadFromJson(crawledDataPath);
        }
        if (docs.isEmpty()) {
            // Khong co corpus da crawl thi lui ve chuoi nguon nhu luc khoi dong,
            // de reindex tren ban demo (chi co seed) khong xoa sach chi muc.
            for (DocumentStore store : buildStoreChain()) {
                if (store.isAvailable()) {
                    List<WebDocument> candidate = store.loadAll();
                    if (!candidate.isEmpty()) {
                        docs = candidate;
                        break;
                    }
                }
            }
        }
        index = indexBuilder.build(docs);
        IndexPersistence.save((InvertedIndex) index, indexDataPath);
        refreshDerivedState();
    }

    /**
     * So tai lieu dang co trong chi muc — nguon du lieu cho
     * {@code /api/health} va cho thang do Micrometer.
     *
     * <p>Tach rieng khoi {@link #getStats} vi hai thu phuc vu hai doi tuong
     * khac nhau: ham nay tra loi cau hoi cong khai "he thong co phuc vu duoc
     * khong", con {@code getStats} phoi bay chi tiet van hanh va can xac thuc.
     */
    public int getIndexedDocumentCount() {
        SearchIndex current = index;
        return current == null ? 0 : current.getTotalDocs();
    }

    /**
     * Mot tai lieu theo docId, hoac {@code null} neu id nam ngoai chi muc.
     *
     * <p>Mo ra cho {@code FeedController} dung — no can DUYET chi muc chu khong
     * phai truy van no. Doc khong co truy van thi khong co diem lien quan, nen
     * moi duong di qua {@link #search} deu khong dung duoc.
     *
     * <p>Doc mot lan vao bien cuc bo: {@link #index} la {@code volatile} va co
     * the bi thay the giua chung boi mot lan lap chi muc lai. Khong lam vay thi
     * hai lenh doc lien tiep co the roi vao HAI chi muc khac nhau, va docId cua
     * chi muc nay tro thanh mot tai lieu hoan toan khac o chi muc kia.
     */
    public WebDocument getDocumentAt(int docId) {
        SearchIndex current = index;
        if (current == null || docId < 0 || docId >= current.getTotalDocs()) {
            return null;
        }
        return current.getDocument(docId);
    }

    /** Ty le trung cache tim kiem — thang do cho Micrometer. */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long total = hits + cacheMisses.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public int getTermCount() {
        SearchIndex current = index;
        return current == null ? 0 : current.getTermCount();
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
        } catch (IOException e) {
            log.debug("Khong doc duoc kich thuoc file chi muc", e);
        }
        stats.put("indexSizeBytes", indexSizeBytes);

        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        stats.put("cacheHitRate", (hits + misses) == 0 ? 0.0 : (double) hits / (hits + misses));
        stats.put("bloomFilterBits", crawlJobManager.lastBloomFilterBits());
        stats.put("scorer", scorer == null ? "(chua khoi tao)" : scorer.name());
        return stats;
    }
}
