Bản rút gọn dạng cây

```
run-backend.bat  (Docker: backend container)
└─ VnSearchApplication.main → Spring khởi động
   ├─ SearchConfig.tokenizer()          → MỘT bean VietnameseTokenizer dùng chung
   │  └─ new VietnameseWordDictionary()
   │     ├─ load("/vietnamese-words.txt", hasFrequency)    → addWord → SyllableTrie
   │     ├─ load("/vietnamese-bigrams.txt")                → CURATED_FREQUENCY 10 000 000
   │     └─ weightOf(frequency, syllables) = PARAM · log2(...)   MAX_SYLLABLES = 4
   └─ SearchEngineFacade.init()   @PostConstruct                    ★ ĐIỂM VÀO
      ├─ new LRUCache(app.search.cache-size = 200)
      ├─ loadCorpus()
      │  ├─ ĐƯỜNG NHANH: Files.exists("data/index.json")            (402 MB)
      │  │  └─ IndexPersistence.load(path, tokenizer)
      │  │     ├─ Jackson readValue → IndexData
      │  │     │  └─ MismatchedInputException → coi như version 1 → ném IOException dễ hiểu
      │  │     ├─ data.version() ≠ FORMAT_VERSION 3  → IOException "định dạng đời trước"
      │  │     ├─ checkTokenizerMatches(stored, current.name())
      │  │     │  ├─ null   → chỉ CẢNH BÁO (file đời trước, không kiểm chứng được)
      │  │     │  └─ khác   → IOException  ← chặn lỗi rỗng IM LẶNG khi từ điển đổi
      │  │     └─ InvertedIndex.importData
      │  │        ├─ ∀ term: termDictionary.intern(khoá)   ← giữ lại Flyweight sau khi nạp
      │  │        ├─ CompressedPostings.toPostings()       (giải nén, xem cây dưới)
      │  │        ├─ documents / bodyTexts / docLength .putAll
      │  │        └─ recomputeDerivedState → totalTokens, lastDocId
      │  │     ↳ prebuilt.getTotalDocs() == 0 → BỎ QUA, rơi xuống chuỗi nguồn
      │  │     ↳ IOException/RuntimeException → chỉ log.warn (chỉ mục là CACHE dẫn xuất)
      │  ├─ buildStoreChain()                                (Chain of Responsibility)
      │  │  ├─ PostgresDocumentStore   (nếu app.storage.postgres.enabled)
      │  │  ├─ JsonDocumentStore "data/crawled-documents.json"   ← corpus đã crawl, 384 MB
      │  │  └─ JsonDocumentStore "data/seed-documents.json"      ← mẫu đi kèm repo
      │  │     ∀ store: isAvailable() → loadAll() → docs.isEmpty() ? bỏ qua : dùng
      │  ├─ IndexBuilder.build(docs)                              ★ DỰNG CHỈ MỤC
      │  │  ├─ sort theo docId tăng dần                (TIỀN ĐỀ bắt buộc)
      │  │  ├─ CẤP LẠI docId = 0..n-1                  (docId là danh tính TRONG chỉ mục)
      │  │  ├─ n < PARALLEL_THRESHOLD 2 000 → nạp tuần tự
      │  │  └─ buildInBatches, BATCH_SIZE 512
      │  │     ├─ batch.parallelStream().map(tokenize)  ← tách từ SONG SONG
      │  │     │  └─ VietnameseTokenizer.tokenize
      │  │     │     ├─ splitIntoSyllables  (NFC, chữ thường, bỏ dấu câu)
      │  │     │     └─ MaxWeightSegmenter.segment          ← QUY HOẠCH ĐỘNG
      │  │     │        ├─ best[0] = 0, best[i] = −∞
      │  │     │        ├─ relax(i+1, best[i] + UNKNOWN_SYLLABLE_WEIGHT 0.5)  ← luôn cho tách 1 tiếng
      │  │     │        ├─ đi trie MỘT lượt phủ độ dài 1..4, gặp NONE thì cắt nhánh
      │  │     │        ├─ trie.isWord(node) → relax(j+1, best[i] + weightAt)
      │  │     │        └─ traceBack → mảng mốc giới hạn
      │  │     │     └─ ∀ token: từ ghép nối "_", 1 tiếng thì lọc stopword
      │  │     │                 → Token(term, stripDiacritics(term), position)
      │  │     └─ ∀ doc trong lô: InvertedIndex.addDocument(doc, tokens)   ← nạp TUẦN TỰ
      │  │        ├─ docId ≤ lastDocId → IllegalArgumentException (lớp bảo vệ thứ hai)
      │  │        ├─ CompressedText.compress(bodyText) → bodyTexts[docId]
      │  │        ├─ documents[docId] = doc.withoutBodyText()
      │  │        ├─ docLength[docId] = tokens.size();  totalTokens += …
      │  │        ├─ gom vị trí theo term TRƯỚC  (một (term, doc) = MỘT posting)
      │  │        │  ├─ termDictionary.intern(term)              ← Flyweight
      │  │        │  └─ term không dấu ≠ term → intern thêm, CHUNG vị trí
      │  │        └─ ∀ term: List<Integer> → int[] → new Posting(docId, tf, positions)
      │  │                  → index[term].add(posting)           ← APPEND, tự sắp theo docId
      │  └─ persistIndex()                                        ★ GHI CHỈ MỤC
      │     └─ IndexPersistence.save(index, "data/index.json")
      │        └─ InvertedIndex.exportData → IndexData(v3, tokenizer.name(), …)
      │           └─ ∀ term: CompressedPostings.of(postings)
      │              ├─ kiểm bất biến termFrequency == positions.length  (nếu sai → ném)
      │              ├─ docIds[]   → VByteCodec.encodeSorted   (delta + VByte)
      │              ├─ offsets[]  → encodeSorted  (tổng tích luỹ ⇒ suy lại tf, KHÔNG lưu tf)
      │              └─ positions  → encodeSegments
      │                 └─ writeVInt: 7 bit/byte, bit cao = cờ còn byte tiếp
      │           ↳ bodyTexts là byte[] → Jackson tự mã hoá base64
      │        ↳ IOException → chỉ log.warn, ứng dụng VẪN chạy (lần sau khởi động chậm)
      └─ refreshDerivedState()                          ★ TRẠNG THÁI DẪN XUẤT
         ├─ PageRankService.computePageRank(allDocuments)
         │  ├─ DAMPING 0.85, EPSILON 1e-6, MAX_ITERATIONS 100
         │  ├─ dựng ma trận kề từ outlinks (URL → chỉ số)
         │  └─ lặp luỹ thừa đến khi ‖Δ‖ < ε → scores{docId → điểm}
         ├─ ScorerFactory.create(pageRankScores)                   (Factory + Decorator)
         │  ├─ createBase: app.ranking.scorer = tfidf | bm25
         │  ├─ TitleBoostScorer   γ = app.ranking.gamma 0.10
         │  └─ PageRankBoostScorer β = app.ranking.beta  0.30
         ├─ SuggestionService.rebuild(index)      → Trie tiền tố từ term của chỉ mục
         ├─ searchCache = new LRUCache(200)       ← chỉ mục đổi thì cache CŨ phải bỏ
         └─ CorpusStats.from(documents, docId → index.getDocLength(docId))
            ↳ độ dài lấy từ CHỈ MỤC (số token, O(1)), không đo chuỗi bodyText
```

Đường đi thứ hai — dựng lại chỉ mục khi đang chạy:

```
POST /api/admin/reindex → SearchEngineFacade.reindex()
├─ ContentStorage.loadFromJson("data/crawled-documents.json")
├─ rỗng → lùi về buildStoreChain() (để bản demo chỉ có seed không bị xoá sạch chỉ mục)
├─ IndexBuilder.build(docs)      → chỉ mục MỚI, gán vào trường volatile
├─ persistIndex()
└─ refreshDerivedState()
   ↳ search() chụp index / scorer / pageRankScores / cache vào biến cục bộ MỘT lần,
     nên một lần reindex xen giữa không thể ghép chỉ mục CŨ với điểm PageRank MỚI
```

Ba bất biến mà cả hai đường đi đều phải giữ:

```
1. addDocument gọi theo docId TĂNG DẦN
   → posting list tự sắp xếp theo docId, miễn phí
   → merge two-pointer O(m+n) và binary search trong getTermFrequency mới đúng
   ✗ vi phạm: IllegalArgumentException ngay tại addDocument

2. Tầng chỉ mục và tầng truy vấn dùng CÙNG một tokenizer VÀ cùng một từ điển
   → SearchConfig khai một bean duy nhất; IndexPersistence ghi Tokenizer.name()
   ✗ vi phạm: mọi truy vấn trả rỗng IM LẶNG — không ngoại lệ, không log, không test đỏ
     (ví dụ thật: từ điển 154 → 49 793 mục, "không trung thực" đổi cách tách)

3. termFrequency == positions.length
   → dạng nén KHÔNG lưu tf mà suy lại từ mảng offsets
   ✗ vi phạm: giải nén ra kết quả SAI một cách im lặng → CompressedPostings.of ném ngay
```
