Bản rút gọn dạng cây

Bốn kho, bốn vòng đời khác nhau:

```
data/crawled-documents.json         384 MB   NGUỒN SỰ THẬT  — crawler ghi, chỉ mục đọc
data/crawled-documents.images.json   14 MB   nguồn sự thật của kho ảnh
data/index.json                     402 MB   CACHE DẪN XUẤT — xoá đi vẫn dựng lại được
PostgreSQL (documents + outlinks)            nguồn thay thế + đối chứng GIN
```

Đường GHI — crawler đẩy corpus xuống đĩa:

```
MultiDomainCrawlRunner.main
├─ CrawlerService.crawl(...)
│  └─ ∀ trang: ContentStorage.save(doc)
│     └─ ConcurrentHashMap.putIfAbsent(url, doc)   ← trùng URL thì GIỮ bản cũ
│  └─ ∀ trang: ContentStorage.applyOutlinks(url, outlinks)
│     ↳ outlinks tới SAU nội dung: UrlExtractor là Modular Service, gửi ngược qua bus
│       (in-process: đồng bộ ngay; Kafka: sau vài chục mili-giây)
├─ CheckpointCrawlListener  mỗi 250 trang → saveToJson             ← ghi đè HÀNG CHỤC lần/phiên
└─ ContentStorage.saveToJson(documents, "data/crawled-documents.json")   ★ GHI NGUYÊN TỬ
   ├─ Files.createDirectories(parent)
   ├─ ObjectMapper + JavaTimeModule, INDENT_OUTPUT, KHÔNG ghi ngày dạng timestamp
   ├─ writeValue(<path>.tmp)                       ← ghi ra tệp TẠM trước
   └─ Files.move(tmp → path, REPLACE_EXISTING, ATOMIC_MOVE)
      ├─ AtomicMoveNotSupportedException (ổ mạng) → move thường
      │  ↳ vẫn tốt hơn ghi đè thẳng: cửa sổ nguy hiểm rút từ cả giây xuống một
      │    thao tác siêu dữ liệu
      └─ ↳ vì sao phải vậy: mất điện giữa lúc ghi đè trực tiếp để lại JSON CỤT —
           mất luôn corpus CŨ vốn đang hoàn chỉnh, đổi lấy corpus mới cũng hỏng

ImageStorage.saveToJson(images, ImageStorage.pathFor(corpusPath))
└─ pathFor("data/crawled-documents.json") → "data/crawled-documents.images.json"
   ↳ hai tệp buộc cùng gốc tên, để không bao giờ ghép nhầm ảnh của phiên khác
```

Đường ĐỌC — chuỗi nguồn dự phòng lúc khởi động:

```
SearchEngineFacade.loadCorpus()
└─ buildStoreChain() : List<DocumentStore>                (Chain of Responsibility)
   ├─ [1] PostgresDocumentStore      chỉ thêm khi app.storage.postgres.enabled = true
   │      ├─ isAvailable() → DocumentRepository.countDocuments() > 0
   │      │   ↳ mọi Exception → log.info rồi trả false (DB chết KHÔNG được làm sập app)
   │      ├─ loadAll()     → repo.findAll()
   │      └─ describe()    → "PostgreSQL @ jdbc:postgresql://…"
   ├─ [2] JsonDocumentStore("data/crawled-documents.json", "corpus đã crawl")
   │      ├─ isAvailable() → path khác rỗng VÀ Files.exists
   │      └─ loadAll()     → ContentStorage.loadFromJson
   └─ [3] JsonDocumentStore("data/seed-documents.json", "seed mẫu")
          ↳ tầng cuối để người vừa clone repo chạy được NGAY

   vòng lặp:  !isAvailable() → bỏ qua
              docs.isEmpty() → bỏ qua, ĐI TIẾP    ← nguồn RỖNG không phải là nguồn
              ↳ isAvailable() chỉ hỏi "tệp có tồn tại không", nên tệp chứa `[]` do một
                phiên crawl hỏng để lại sẽ CHẶN mất các tầng dự phòng phía sau nếu
                không có kiểm tra này
              còn lại       → IndexBuilder.build → persistIndex → return
```

Kho thứ ba — chỉ mục đã dựng, một cache dẫn xuất:

```
IndexPersistence.save(index, "data/index.json")
└─ InvertedIndex.exportData → IndexData(version 3, tokenizer.name(), index, documents,
                                        bodyTexts, docLength)
   ├─ posting list  → CompressedPostings.of  → VByteCodec (delta + VByte)
   ├─ bodyTexts     → CompressedText.compress (Deflater thô, KHÔNG bọc GZIP)
   │                  ↳ GZIP thêm 10 byte header + 8 byte trailer cho MỖI tài liệu
   │                  ↳ deflater.end() bắt buộc: bộ đệm nằm NGOÀI heap, GC không thấy
   └─ byte[] → Jackson mã hoá base64

IndexPersistence.load(path, tokenizer)
├─ version ≠ 3        → IOException nói đúng việc phải làm (không phải MismatchedInputException)
├─ tokenizer khác     → IOException  ← chặn lỗi "mọi truy vấn trả rỗng" IM LẶNG
├─ tokenizer null     → chỉ cảnh báo (định dạng đời trước, không kiểm chứng được)
└─ getTotalDocs() == 0 → bỏ qua tệp, dựng lại từ corpus gốc
   ↳ ca thật đã gặp: một phiên crawl hỏng để lại index.json 159 byte, đường nhanh nạp
     trót lọt rồi RETURN — che mất corpus mẫu, mọi truy vấn về 0, /api/health trả 503,
     và trong Docker container vào vòng khởi động lại vô hạn
```

Kho thứ tư — PostgreSQL, nạp và đối chứng:

```
PostgresImportRunner.main [corpusPath]
├─ ContentStorage.loadFromJson(corpusPath)
└─ DocumentRepository.connectDefault()      jdbc:postgresql://localhost:5432/vnsearch
   ├─ deleteAll()   → TRUNCATE TABLE documents CASCADE
   ├─ saveAll(docs)                                        ★ MỘT giao dịch
   │  ├─ setAutoCommit(false)
   │  ├─ insertDocuments  INSERT … ON CONFLICT (doc_id) DO UPDATE   (upsert)
   │  │  └─ addBatch, executeBatch mỗi BATCH_SIZE = 500
   │  ├─ insertOutlinks   INSERT INTO outlinks (from_doc_id, to_url)
   │  ├─ commit()   /   SQLException → rollback() rồi ném lại
   │  └─ finally: trả autoCommit về trạng thái cũ
   ├─ đo: countDocuments, countOutlinks,
   │      pg_total_relation_size('documents'), pg_relation_size('idx_documents_tsv')
   └─ đọc lại findAll() để kiểm chứng
      ├─ SELECT … FROM documents ORDER BY doc_id       → LinkedHashMap giữ thứ tự
      └─ SELECT … FROM outlinks ORDER BY from_doc_id   → gắn vào doc tương ứng
         ↳ hai truy vấn RỜI thay vì một JOIN — một JOIN sẽ nhân bản mỗi tài liệu lên đúng
             bằng số liên kết ra của nó, kéo cả body_text theo

GinBaselineRunner.main [numQueries=200] [reportPath=../docs/GIN-BASELINE.md]
├─ repo.findAll() → InvertedIndex tự cài
├─ PageRankService.computePageRank
├─ KnownItemQueryGenerator.generate(index, n, 3, seed 42)
├─ làm nóng JVM 2 vòng (chạy CẢ hai bên)
└─ so từng truy vấn:  EvaluationHarness.search  ⟷  repo.searchWithGin
                      (ts_rank + plainto_tsquery('simple', ?) trên cột tsv)
   → EvaluationMetrics + báo cáo Markdown
```

Bốn nguyên tắc chạy suốt tầng lưu trữ:

```
1. Ghi qua tệp TẠM rồi đổi tên     → không bao giờ có tệp corpus cụt
2. Nguồn RỖNG không phải là nguồn  → tồn tại ≠ dùng được, luôn kiểm số bản ghi
3. Cache dẫn xuất không được sập app → index.json hỏng thì log.warn, dựng lại từ corpus
4. Nguồn sự thật thì được phép sập  → không nguồn nào có tài liệu = chỉ mục rỗng, và
                                      /api/health nói thẳng điều đó
```
