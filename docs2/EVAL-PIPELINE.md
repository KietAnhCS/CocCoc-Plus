Bản rút gọn dạng cây

Bốn công cụ đo, chạy bằng dòng lệnh, ghi ra báo cáo Markdown trong `docs/`:

```
EvaluationRunner       [corpus] [numQueries=200] [report=../docs/EVALUATION.md]   so cấu hình xếp hạng
QrelsEvaluationRunner  [pool|score] [corpus]                                      đo bằng nhãn NGƯỜI gán
GinBaselineRunner      [numQueries=200] [report=../docs/GIN-BASELINE.md]          đối chứng PostgreSQL GIN
TokenizerBenchmark / MemoryBreakdown                                              đo tách từ và bộ nhớ
```

Đường chính — so các cấu hình xếp hạng trên truy vấn known-item:

```
EvaluationRunner.main
├─ ContentStorage.loadFromJson(corpus)
├─ buildIndex(docs)                          → in số term, token/tài liệu, thời gian dựng
├─ PageRankService.computePageRank           → in số vòng hội tụ, thời gian
├─ KnownItemQueryGenerator.generate(index, numQueries, TERMS_PER_QUERY 3, SEED 42)
│  ├─ sort docIds RỒI shuffle(new Random(42))    ← sắp trước thì seed cho KẾT QUẢ LẶP LẠI được
│  ├─ ∀ tài liệu: pickDistinctiveTerms
│  │  ├─ tokenize(title + metaDescription + bodyText), đếm tần suất
│  │  ├─ bỏ term có df < MIN_DF 3 hoặc df > maxDf (MAX_DF_RATIO 10% corpus)
│  │  │  ↳ term quá hiếm = rác/lỗi chính tả; term quá phổ biến = không phân biệt được tài liệu
│  │  ├─ điểm = tf · idf , term nằm trong TIÊU ĐỀ nhân TITLE_BOOST 2.0
│  │  └─ lấy 3 term điểm cao nhất
│  ├─ thiếu 3 term → BỎ tài liệu (quá ngắn hoặc không đủ term phân biệt)
│  └─ truy vấn trùng chuỗi → BỎ    ← hai tài liệu cùng sinh một truy vấn thì chân lý nhập nhằng
│     → KnownItemQuery(queryText, targetUrl, targetDocId, terms)
│       ↳ "known-item": mỗi truy vấn có ĐÚNG MỘT tài liệu đúng, không cần người gán nhãn
├─ buildConfigs(pageRankScores)              → 11 cấu hình, chia 4 nhóm có chủ đích:
│  ├─ nhóm 1 (2)  TF-IDF thuần / BM25 thuần                  ← so MÔ HÌNH, tắt hết tín hiệu phụ
│  ├─ nhóm 2 (3)  TF-IDF + title / + PageRank / + cả hai     ← tách ĐÓNG GÓP của từng tín hiệu
│  ├─ nhóm 3 (5)  TF-IDF beta ∈ {0.05, 0.10, 0.20, 0.50, 0.80} ← quét trọng số PageRank, gamma cố định 0.1
│  └─ nhóm 4 (1)  BM25 + PR + title                          ← xem hai thứ có cộng hưởng không
├─ ∀ cấu hình: evaluate(harness, config, queries)
│  └─ ∀ truy vấn:
│     ├─ EvaluationHarness.search  → QueryParser → CandidateResolver → ResultRanker
│     │  ↳ dùng ĐÚNG các lớp phục vụ request thật: cái được ĐO là cái được PHỤC VỤ
│     ├─ reciprocalRank(ranked, targetUrl)   1/thứ hạng của tài liệu đúng, 0 nếu không có
│     ├─ successAtK(ranked, targetUrl, 1 | 5 | 10)
│     └─ đo System.nanoTime cho từng truy vấn
│     → ConfigResult(label, MRR, S@1, S@5, S@10, avgQueryMs, avgCandidates, mảng RR từng truy vấn)
│       ↳ giữ MẢNG RR từng truy vấn chứ không chỉ trung bình — kiểm định theo cặp cần nó
├─ analyseScoreScales  → đo độ lớn TB và lớn nhất của TF-IDF trần so với PageRank trần
│  ↳ điểm cuối = alpha·tfidf + beta·pageRank + gamma·titleBonus (CỘNG có trọng số); trọng số
│    chỉ phản ánh đúng mức đóng góp khi ba đại lượng cùng thang đo — mục này kiểm tra giả định đó
└─ ghi báo cáo Markdown + in bảng ra màn hình
```

Kiểm định ý nghĩa thống kê — hai phép, phải ĐỒNG Ý với nhau:

```
SignificanceTest.pairedTest(a[], b[])        hai mảng RR của CÙNG tập truy vấn
├─ khác độ dài → IllegalArgumentException    ← so theo cặp thì bắt buộc cùng tập
├─ differences[i] = a[i] − b[i] ; mean = trung bình hiệu
├─ n < 2 → trả p = 1.0 (không kết luận được)
├─ standardError == 0 → mọi hiệu bằng nhau: p = 1.0 nếu mean = 0, ngược lại 0.0
├─ t-test theo cặp     t = mean / standardError , df = n − 1 → pValueTTest
├─ khoảng tin cậy 95%  mean ± t₀.₉₇₅(df) · standardError
├─ randomization test  PERMUTATIONS 100 000, seed 42 → pValueRandomization
│  ↳ không giả định phân phối chuẩn — RR chỉ nhận các giá trị 1, 1/2, 1/3 … nên rất lệch chuẩn
├─ isSignificant()  = CẢ HAI p < ALPHA 0.05
└─ testsDisagree()  = hai phép cho kết luận khác nhau
   ↳ báo cáo in ra cả hai p: khi chúng bất đồng thì đó là dấu hiệu mẫu quá nhỏ hoặc quá lệch,
     không phải chuyện để chọn con số nào dễ nhìn hơn
```

Đường thứ hai — đo bằng nhãn do NGƯỜI gán (pooling, chuẩn TREC):

```
QrelsEvaluationRunner pool [corpus]                    ← bước 1: dựng bể tài liệu cần gán nhãn
├─ 30 truy vấn TỰ NHIÊN viết tay (có dấu và không dấu: "công nghệ" và "cong nghe")
│  ↳ khác hẳn truy vấn known-item sinh máy — chúng phản ánh cách người thật gõ
├─ PoolBuilder: gộp top POOL_DEPTH 10 của NHIỀU cấu hình xếp hạng
│  └─ mỗi mục ghi `foundBy` = những cấu hình đã tìm ra nó
│     ↳ pooling: không ai đọc hết 30 000 tài liệu; chỉ gán nhãn phần hợp của các top-10
└─ ghi data/eval/pool-to-label.json  → người gán trường `relevance`

QrelsEvaluationRunner score [corpus]                   ← bước 2: chấm điểm theo nhãn
└─ đọc data/eval/qrels.json → EvaluationMetrics
   ├─ precisionAtK / recallAtK / f1AtK
   ├─ averagePrecision → meanAveragePrecision (MAP)
   ├─ ndcgAtK          ← có tính MỨC ĐỘ liên quan, không chỉ có/không
   └─ RELEVANT_THRESHOLD 1: nhãn ≥ 1 được coi là liên quan
```

Đường thứ ba — đối chứng với một hệ thống có sẵn:

```
GinBaselineRunner
├─ đọc corpus từ PostgreSQL, dựng chỉ mục TỰ CÀI
├─ làm nóng JVM 2 vòng, chạy CẢ hai bên               ← nếu chỉ hâm một bên thì so sánh vô nghĩa
└─ ∀ truy vấn: EvaluationHarness.search  ⟷  repo.searchWithGin (ts_rank + plainto_tsquery)
   → so chất lượng và thời gian, ghi ../docs/GIN-BASELINE.md
```

Ba quy tắc giữ cho con số đo được là con số thật:

```
1. Đo đúng thứ đang phục vụ
   EvaluationHarness gọi QueryParser + CandidateResolver + ResultRanker — chính các lớp
   mà /api/search dùng. Một bản sao "rút gọn cho tiện đo" sẽ đo một hệ thống không tồn tại.

2. Lặp lại được
   SEED 42 ở cả sinh truy vấn lẫn randomization test; sort trước khi shuffle. Chạy lại
   hôm sau phải ra đúng con số hôm nay, nếu không thì không so được hai lần chạy.

3. Nói cả điều bất lợi
   Báo cáo có sẵn mục hạn chế, mục "cách đọc bảng", và in cả hai giá trị p kể cả khi
   chúng bất đồng — cùng với ghi chú rằng known-item là bài dễ hơn truy vấn người thật.
```
