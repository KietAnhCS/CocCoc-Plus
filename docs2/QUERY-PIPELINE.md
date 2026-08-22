Bản rút gọn dạng cây

```
GET /api/search?q=…&page=1&size=10
└─ SearchController.search
   └─ SearchEngineFacade.search(rawQuery, page, size)                ★ ĐIỂM VÀO
      ├─ chụp MỘT lần vào biến cục bộ: index, scorer, pageRankScores, searchCache
      │  ↳ một lần reindex xen giữa không được phép ghép chỉ mục CŨ với PageRank MỚI
      ├─ cacheKey = lower(query) + "|p" + page + "|s" + size
      │  └─ LRUCache.get → trúng thì cacheHits++ và TRẢ NGAY
      ├─ QueryParser.parse(query)                                    ─── PHÂN TÍCH
      │  ├─ Bước 1: PHRASE_PATTERN "\"([^\"]*)\"" cắt cụm RA KHỎI chuỗi
      │  │           phần ngoài ngoặc giữ lại → remaining
      │  │           ↳ nếu không cắt, tiếng trong cụm vừa là phrase vừa là mustTerm
      │  │             → đếm hai lần trong queryTermFrequency, lệch trọng số
      │  ├─ Bước 2: quét từng từ của remaining
      │  │  ├─ "site:vnexpress.net"  → siteFilter
      │  │  ├─ "A OR B OR C"         → gom một nhóm 3 phần tử (orGroups)
      │  │  ├─ "-từ"                 → excludedRaw
      │  │  └─ còn lại               → mustRaw
      │  └─ Bước 3: tokenize từng phần bằng CÙNG tokenizer với tầng chỉ mục
      │     ├─ mỗi cụm ngoặc kép: tokenize RIÊNG (một đơn vị độc lập)
      │     ├─ phần ngoài ngoặc: nối lại rồi tokenize CHUNG (đủ ngữ cảnh ghép từ ghép)
      │     └─ nhóm OR chỉ còn 1 vế → hạ xuống thành mustTerm
      │     → ParsedQuery(mustTerms, phrases, excludedTerms, orGroups, siteFilter)
      ├─ CandidateResolver.resolve(index, parsed)                    ─── TRUY HỒI
      │  ├─ buildQueryTermFrequency(parsed)   ← gồm CẢ term của cụm và của nhóm OR
      │  │  ↳ luôn tính từ truy vấn GỐC, kể cả khi nới lỏng
      │  ├─ QueryParser.buildAst(parsed)                             (Composite)
      │  │  └─ AndNode[ TermNode…, PhraseNode…, OrNode…, NotNode… ]
      │  │     ↳ không mệnh đề khẳng định nào → null → trả rỗng ngay
      │  ├─ ast.evaluate(index)                          ─── GIAI ĐOẠN 1
      │  │  └─ AndNode.evaluate
      │  │     ├─ tách con thành positives / negatives
      │  │     ├─ positives rỗng → UnsupportedOperationException ("chỉ toàn NOT")
      │  │     ├─ sort theo estimatedSize(index)         ← SHORTEST-FIRST
      │  │     │  ├─ TermNode.estimatedSize   = df, O(1)
      │  │     │  ├─ PhraseNode.estimatedSize = min df của các tiếng
      │  │     │  └─ OrNode.estimatedSize     = tổng con (chặn trên)
      │  │     ├─ accumulator = con NHỎ NHẤT .evaluate
      │  │     │  ├─ TermNode   → PostingListMerger.docIdsOf(index.getPostings(term))
      │  │     │  ├─ OrNode     → union hai con, two-pointer O(m+n)
      │  │     │  └─ PhraseNode → AndNode(các tiếng).evaluate   ← lọc THÔ
      │  │     │                  → ∀ docId: matchesPhrase(index, terms, docId)  ← lọc CHÍNH XÁC
      │  │     │                     ↳ so mảng vị trí: pos(t[k+1]) == pos(t[k]) + 1
      │  │     ├─ ∀ con còn lại: intersect(accumulator, con.evaluate)
      │  │     │  ↳ rỗng là phần tử HẤP THỤ của phép giao → dừng ngay
      │  │     └─ ∀ NotNode: evaluateAgainst(accumulator, index)
      │  │        ↳ trừ tập bằng two-pointer O(m+n) — cả hai danh sách đều tăng dần
      │  ├─ applyFilters(candidates)                     (Chain of Responsibility)
      │  │  ├─ DomainFilter        (chỉ chạy khi có site:)  → giữ doc cùng host
      │  │  └─ MaxCandidatesFilter  DEFAULT_MAX_CANDIDATES = 10 000
      │  │     ↳ rỗng là phần tử hấp thụ → gặp rỗng thì dừng cả chuỗi
      │  └─ rỗng → relaxAndRetry                         ─── GIAI ĐOẠN 2 (nới lỏng)
      │     ├─ isUnmatchable? (cụm có tiếng df = 0, hoặc nhóm OR không vế nào tồn tại)
      │     │  → thoát ngay, khỏi thử k lần vô ích
      │     ├─ Bước 1: bỏ MỘT LẦN tất cả term có df = 0 → attempt()
      │     ├─ Bước 2: sort theo df GIẢM DẦN, bỏ dần từng term phổ biến nhất → attempt()
      │     │          (dừng khi còn 1 term)
      │     └─ droppedTerms trả ra ngoài → hiển thị cho người dùng, KHÔNG bỏ qua âm thầm
      │     ↳ điểm vẫn chấm theo truy vấn GỐC: khớp 4/5 term vẫn trên khớp 3/5
      ├─ ResultRanker.rank(candidates, qtf, index, scorer, pageRank, topN)  ─── XẾP HẠNG
      │  ├─ GIAI ĐOẠN 0: scorer.prepare(qtf, index)     ← phần phụ thuộc TRUY VẤN, một lần
      │  │  └─ BM25Scorer.prepare
      │  │     ├─ totalDocs == 0 hoặc avgdl ≤ 0 → docId → 0.0
      │  │     ├─ ∀ term: df == 0 thì bỏ; idf = ln(1 + (N − df + 0.5)/(df + 0.5))
      │  │     └─ trả DocumentScorer:
      │  │        lengthNorm = k1·(1 − b + b·len(d)/avgdl)          k1 = 1.2, b = 0.75
      │  │        Σ idf · tf·(k1 + 1) / (tf + lengthNorm)
      │  │     ↳ Decorator bọc ngoài: + γ·titleBoost, + β·pageRank
      │  ├─ GIAI ĐOẠN 1: ∀ candidate → prepared.score(docId)   (CHƯA sinh snippet)
      │  ├─ GIAI ĐOẠN 2: MinHeap.topK(scored, topN)      O(c·log K) thay vì O(c·log c)
      │  └─ GIAI ĐOẠN 3: chỉ top-K mới sinh snippet
      │     ├─ QuerySyllables.from(qtf.keySet())
      │     └─ SnippetBuilder.build(index.getBodyText(docId), syllables)
      │        ├─ getBodyText → CompressedText.decompress   ← GIẢI NÉN, chỉ trong vòng top-K
      │        ├─ findBestWindow: cửa sổ DEFAULT_WINDOW_SIZE = 25 từ, nhiều khớp nhất
      │        └─ render + escapeHtml + <mark> quanh từ khớp
      ├─ cắt trang: fromIndex = (max(page,1) − 1)·size,  toIndex = từ + size
      ├─ SearchResponse(query, totalHits, page, size ĐÃ ÁP DỤNG, elapsedMs, results, droppedTerms)
      ├─ cache.put(cacheKey, response)
      └─ candidates không rỗng → SuggestionService.learnFromQuery(query)
         ↳ chỉ học từ truy vấn CÓ kết quả, để không học phải lỗi chính tả
```

Cây biểu thức của vài truy vấn mẫu:

```
máy tính xách tay
└─ AndNode[ Term(máy_tính), Term(xách_tay) ]

"biến đổi khí hậu" việt nam -mỹ
└─ AndNode[ Phrase(biến_đổi khí_hậu), Term(việt_nam), Not(Term(mỹ)) ]

hà nội OR sài gòn site:vnexpress.net
└─ AndNode[ Or[ Term(hà_nội), Term(sài_gòn) ] ]  + DomainFilter("vnexpress.net")
```

Vì sao NOT không tự đánh giá được:

```
NotNode.evaluate           → UnsupportedOperationException
NotNode.evaluateAgainst(…) → đường ĐÚNG, luôn trừ trên một tập ứng viên có sẵn
  ↳ phủ định độc lập sẽ trả về gần như TOÀN BỘ corpus — đúng về mặt tập hợp,
    vô dụng về mặt tìm kiếm, và tốn bộ nhớ đúng bằng cỡ corpus
```
