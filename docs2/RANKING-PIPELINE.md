Bản rút gọn dạng cây

```
SearchEngineFacade.search  →  ResultRanker.rank(candidates, qtf, index, scorer, pageRank, topN)
└─ GIAI ĐOẠN 0 — chuẩn bị phần phụ thuộc TRUY VẤN, đúng MỘT lần
   └─ scorer.prepare(queryTermFrequency, index) → DocumentScorer  (một hàm docId → điểm)
      ↳ trước đây idf, trọng số truy vấn và tập tiếng của truy vấn bị tính lại cho TỪNG ứng viên
      │
      ├─ [lớp trong cùng] TfIdfScorer.prepare              app.ranking.scorer = tfidf  (mặc định)
      │  ├─ ∀ term của truy vấn:
      │  │  ├─ idf = log₁₀(N / df);  idf ≤ 0 → BỎ term (term có mặt ở mọi tài liệu)
      │  │  ├─ tf(x) = 1 + log₁₀(x)                      ← làm trơn, lần xuất hiện thứ 10 ≠ 10×
      │  │  └─ queryWeight[i] = tf(qtf) · idf;  queryNormSq += w²
      │  ├─ count == 0 hoặc queryNorm == 0 → docId → 0.0
      │  └─ trả docId →
      │        dot = Σ queryWeight[i] · tf(tfᵢ,d) · idf[i]      ← getTermFrequency: nhị phân O(log n)
      │        dot == 0 → 0.0
      │        docNorm = √max(docLength, 1)                     ← max(·,1) chống chia 0
      │        COSINE = dot / (queryNorm · docNorm)
      │
      ├─ [lớp trong cùng, thay thế] BM25Scorer.prepare     app.ranking.scorer = bm25
      │  ├─ k1 = app.ranking.bm25.k1 = 1.2   (k1 < 0 → IllegalArgumentException)
      │  ├─ b  = app.ranking.bm25.b  = 0.75  (b ∉ [0,1] → IllegalArgumentException)
      │  ├─ totalDocs == 0 hoặc avgdl ≤ 0 → docId → 0.0
      │  ├─ idf = ln(1 + (N − df + 0.5)/(df + 0.5))        ← dạng "probabilistic", luôn > 0
      │  └─ trả docId →
      │        lengthNorm = k1 · (1 − b + b · docLength/avgdl)
      │        Σ idf[i] · tf·(k1 + 1) / (tf + lengthNorm)
      │        ↳ tf BÃO HOÀ: tf → ∞ thì số hạng → idf·(k1+1), không tăng vô hạn như TF-IDF
      │
      ├─ [lớp bọc 1] PageRankBoostScorer                   β = app.ranking.beta = 0.30
      │  ├─ dựng MỘT lần trong ScorerFactory.create:
      │  │  ├─ minPageRank = giá trị dương nhỏ nhất (không có → 1e-9)
      │  │  └─ logRange = max(log1p(max/min), 1e-9)
      │  ├─ weight == 0 → TRẢ THẲNG base, không bọc lớp nào
      │  └─ trả docId →
      │        base == 0 → thoát sớm  ← uy tín KHÔNG cứu được tài liệu không liên quan
      │        normalized = log1p(pr/min) / logRange ∈ [0, 1]   ← thang log, vì PageRank lệch nặng
      │        base · (1 + β · normalized)             ← NHÂN, không cộng: giữ đúng thứ nguyên
      │
      └─ [lớp bọc 2] TitleBoostScorer                      γ = app.ranking.gamma = 0.10
         ├─ weight == 0 hoặc truy vấn không còn tiếng nào → trả thẳng base
         ├─ QuerySyllables.from(qtf.keySet())              ← MỘT lần, không phải mỗi tài liệu
         │  ├─ tách term theo "_" → tập `exact` (chữ thường)
         │  └─ tiếng vốn KHÔNG dấu → thêm vào tập `loose`  ← chỉ khớp lỏng một chiều
         └─ trả docId →
               base == 0 → thoát sớm
               bonus = titleMatchRatio(title) = min(1, số từ tiêu đề khớp / |exact|) ∈ [0, 1]
               base · (1 + γ · bonus)

   ⇒ điểm cuối = base(q, d) · (1 + β·PR̂(d)) · (1 + γ·title(q, d))
      tên scorer hiện hành in ra log:  "TF-IDF cosine + PR x0.30 + title x0.10"

├─ GIAI ĐOẠN 1 — CHỈ chấm điểm, chưa sinh snippet
│  ∀ docId ∈ candidates:
│  ├─ index.getDocument(docId) == null → bỏ qua
│  ├─ pageRank = pageRankScores.getOrDefault(docId, 0.0)     ← trả ra ngoài để hiển thị
│  └─ ScoredCandidate(doc, prepared.score(docId), pageRank)

├─ GIAI ĐOẠN 2 — top-K bằng MinHeap                          O(c·log K) thay vì O(c·log c)
│  └─ MinHeap.topK(scored, topN, so sánh theo finalScore)
│     ├─ gom K phần tử đầu rồi heapify MỘT lần: O(K), không phải O(K log K)
│     ├─ ∀ phần tử sau: cmp(item, peek()) > 0 → extractMin + insert
│     │  ↳ dấu ">" CHẶT: phần tử BẰNG ngưỡng bị bỏ, tiết kiệm 2·log K mà kết quả vẫn hợp lệ
│     └─ đảo ngược khi lấy ra → điểm giảm dần
│     ↳ topN = max(page·size, size): phải đủ sâu cho trang đang xin, không chỉ `size`

└─ GIAI ĐOẠN 3 — sinh snippet CHỈ cho top-K
   ├─ QuerySyllables.from(qtf.keySet())
   └─ SnippetBuilder.build(index.getBodyText(docId), syllables)     DEFAULT_WINDOW_SIZE = 25
      ├─ getBodyText → CompressedText.decompress   ← GIẢI NÉN, mỗi lời gọi một tài liệu
      │  ↳ nên nó nằm ở đây, trong vòng lặp top-K, chứ KHÔNG ở giai đoạn chấm điểm
      ├─ ∀ từ: isMatch[i] = syllables.matches(stripPunctuation(words[i]))
      │  ├─ khớp CHÍNH XÁC theo tập `exact`
      │  └─ khớp LỎNG (bỏ dấu) chỉ khi truy vấn vốn viết không dấu
      │     ↳ truyền từ NGUYÊN DẤU vào matches(): chính nó quyết định khớp chặt hay lỏng
      ├─ findBestWindow: cửa sổ trượt O(n), không phải O(n·w)
      │  └─ ra khỏi bên TRÁI thì trừ, vào bên PHẢI thì cộng
      └─ render: <mark> quanh từ khớp + escapeHtml + "..." khi cửa sổ không ở đầu/cuối
         ↳ escapeHtml là chống XSS thật: bài viết có VĂN BẢN "<script>alert(1)</script>"
           vẫn lọt qua ContentParser, và client render bằng innerHTML sẽ thực thi nó
```

Tín hiệu thứ ba — PageRank, tính MỘT lần cho cả corpus (không nằm trên đường request):

```
SearchEngineFacade.refreshDerivedState → PageRankService.computePageRank(allDocuments)
├─ DAMPING d = 0.85, EPSILON = 1e-6, MAX_ITERATIONS = 100
├─ urlToIndex: URL → chỉ số 0..n-1        ← outlink là URL, đồ thị cần chỉ số
├─ outDegree[i] : chỉ đếm liên kết TỚI tài liệu có trong corpus, BỎ tự trỏ (target != idx)
├─ SparseMatrix incoming(n, n)
│  ├─ outDegree == 0 → dangling[i] = true, không có hàng nào
│  ├─ set(target, i, 1/outDegree[i])
│  └─ freeze() → chuyển adjacency list sang CSR cho phép nhân nhanh
├─ khởi tạo pr[i] = 1/n
├─ lặp luỹ thừa:
│  ├─ danglingSum   = Σ pr[i] của các nút cụt
│  ├─ danglingContribution = d · danglingSum / n     ← rải đều, nếu không thì TỔNG pr rò rỉ dần
│  ├─ linkContribution = incoming.multiply(pr)       (CSR)
│  ├─ newPr[j] = (1 − d)/n + d·linkContribution[j] + danglingContribution
│  └─ diff = Σ|newPr − pr| ;  lặp tới khi diff < ε HOẶC đủ 100 vòng
└─ log: số vòng hội tụ, diff cuối, nnz, độ thưa (%)
   → Map{docId → điểm}, dùng cho PageRankBoostScorer VÀ trả ra SearchResult.pageRankScore
```

Ba quyết định thiết kế đáng nói nhất:

```
1. prepare() tách khỏi score()
   RelevanceScorer.prepare mặc định chỉ gọi lại score, nên lớp cũ vẫn chạy;
   nhưng mọi scorer thật đều cài lại để phần phụ thuộc TRUY VẤN tính đúng một lần.

2. Decorator thay vì thêm tham số vào công thức
   Thêm một tín hiệu = thêm một lớp bọc, KHÔNG sửa BM25/TF-IDF.
   Trọng số = 0 → lớp bọc bị bỏ hẳn, không trả chi phí cho tín hiệu đang tắt.
   ↳ vì vậy `app.ranking.alpha` từng có đã bị xoá: nó thuộc về công thức, không
     thuộc về cấu hình.

3. Nhân chứ không cộng, và thoát sớm khi base == 0
   base·(1 + β·PR̂) giữ nguyên thứ nguyên của điểm liên quan;
   cộng thẳng PageRank sẽ đẩy trang uy tín nhưng LẠC ĐỀ lên đầu bảng.
```
