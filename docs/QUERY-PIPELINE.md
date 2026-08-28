# QUERY PIPELINE — Giải phẫu toàn bộ một truy vấn tìm kiếm

### Từ `GET /api/search?q=…` đến `SearchResponse` trả về người dùng

> **Tài liệu tham chiếu kỹ thuật đầy đủ.**
> Mỗi file, mỗi hàm, mỗi hằng số, mỗi nhánh `if` mà một truy vấn chạm tới — theo đúng
> thứ tự thực thi, kèm sơ đồ Mermaid, bảng đối chiếu và trace dữ liệu thật trên corpus
> `backend/data/seed-documents.json`.

---

## MỤC LỤC

### PHẦN I — TỔNG QUAN
- [0. Cách đọc tài liệu này](#0-cách-đọc-tài-liệu-này)
- [1. Điểm vào và vòng đời một request](#1-điểm-vào-và-vòng-đời-một-request)
- [2. Bản đồ toàn hệ thống](#2-bản-đồ-toàn-hệ-thống)
- [3. Danh mục toàn bộ file tham gia](#3-danh-mục-toàn-bộ-file-tham-gia)
- [4. Sơ đồ tuần tự tổng quát](#4-sơ-đồ-tuần-tự-tổng-quát)
- [5. Vòng đời của một truy vấn: chuỗi → `ParsedQuery` → cây AST → tập ứng viên](#5-vòng-đời-của-một-truy-vấn-chuỗi--parsedquery--cây-ast--tập-ứng-viên)
- [6. `SearchEngineFacade` — vì sao chụp trạng thái một lần](#6-searchenginefacade--vì-sao-chụp-trạng-thái-một-lần)
- [7. LRU cache truy vấn](#7-lru-cache-truy-vấn)

### PHẦN II — PHÂN TÍCH CÚ PHÁP: `QueryParser`
- [8. Ba bước của `QueryParser.parse`](#8-ba-bước-của-queryparserparse)
- [9. Bước 1 — cắt cụm từ trong ngoặc kép](#9-bước-1--cắt-cụm-từ-trong-ngoặc-kép)
- [10. Bước 2 — quét token: `site:`, `OR`, `-loại_trừ`, còn lại](#10-bước-2--quét-token-site-or--loại_trừ-còn-lại)
- [11. Bước 3 — tokenize bằng chung tokenizer với tầng chỉ mục](#11-bước-3--tokenize-bằng-chung-tokenizer-với-tầng-chỉ-mục)
- [12. `ParsedQuery` — cấu trúc dữ liệu kết quả](#12-parsedquery--cấu-trúc-dữ-liệu-kết-quả)
- [13. `buildAst` — dựng cây từ `ParsedQuery`](#13-buildast--dựng-cây-từ-parsedquery)
- [14. Trace 3 truy vấn mẫu qua `QueryParser`](#14-trace-3-truy-vấn-mẫu-qua-queryparser)

### PHẦN III — CÂY CÚ PHÁP: GÓI `query.ast`
- [15. `QueryNode` — giao diện chung](#15-querynode--giao-diện-chung)
- [16. `TermNode` — nút lá](#16-termnode--nút-lá)
- [17. `PhraseNode` — nút cụm từ](#17-phrasenode--nút-cụm-từ)
- [18. `AndNode` — nút giao](#18-andnode--nút-giao)
- [19. `OrNode` — nút hợp](#19-ornode--nút-hợp)
- [20. `NotNode` — nút loại trừ và vì sao nó không đứng một mình được](#20-notnode--nút-loại-trừ-và-vì-sao-nó-không-đứng-một-mình-được)
- [21. `estimatedSize()` — chi phí ước lượng của từng loại nút](#21-estimatedsize--chi-phí-ước-lượng-của-từng-loại-nút)

### PHẦN IV — TRUY HỒI ỨNG VIÊN: `CandidateResolver`
- [22. `resolve()` — tổng quan hai giai đoạn](#22-resolve--tổng-quan-hai-giai-đoạn)
- [23. `buildQueryTermFrequency`](#23-buildqueryterfrequency)
- [24. GIAI ĐOẠN 1 — đánh giá cây AST](#24-giai-đoạn-1--đánh-giá-cây-ast)
- [25. `PostingListMerger` — trái tim thuật toán của tầng truy vấn](#25-postinglistmerger--trái-tim-thuật-toán-của-tầng-truy-vấn)
- [26. `matchesPhrase` — khớp cụm từ theo vị trí](#26-matchesphrase--khớp-cụm-từ-theo-vị-trí)
- [27. Bộ lọc ứng viên: `CandidateFilter`, `DomainFilter`, `MaxCandidatesFilter`](#27-bộ-lọc-ứng-viên-candidatefilter-domainfilter-maxcandidatesfilter)
- [28. GIAI ĐOẠN 2 — nới lỏng truy vấn khi rỗng](#28-giai-đoạn-2--nới-lỏng-truy-vấn-khi-rỗng)

### PHẦN V — TỪ ỨNG VIÊN ĐẾN PHẢN HỒI
- [29. Bàn giao cho `ResultRanker`](#29-bàn-giao-cho-resultranker)
- [30. Cắt trang và `SearchResponse`](#30-cắt-trang-và-searchresponse)
- [31. Ghi cache và học gợi ý truy vấn](#31-ghi-cache-và-học-gợi-ý-truy-vấn)

### PHẦN VI — ĐỐI CHIẾU OUTPUT THẬT
- [32. Corpus dùng để trace](#32-corpus-dùng-để-trace)
- [33. Truy vấn 1: `trump iran` — AND hai term phổ biến](#33-truy-vấn-1-trump-iran--and-hai-term-phổ-biến)
- [34. Truy vấn 2: `"tơi bời" trump -mỹ` — rỗng vì loại trừ, không vì thiếu](#34-truy-vấn-2-tơi-bời-trump--mỹ--rỗng-vì-loại-trừ-không-vì-thiếu)
- [35. Truy vấn 3: `trump OR iran` — hợp](#35-truy-vấn-3-trump-or-iran--hợp)
- [36. Truy vấn 4: `sài gòn site:vnexpress.net` — lọc domain](#36-truy-vấn-4-sài-gòn-sitevnexpressnet--lọc-domain)
- [37. Truy vấn 5: `trump khủng long iran` — nới lỏng thật](#37-truy-vấn-5-trump-khủng-long-iran--nới-lỏng-thật)

### PHẦN VII — PHỤ LỤC
- [38. Bảng hằng số toàn hệ thống](#38-bảng-hằng-số-toàn-hệ-thống)
- [39. Bảng tra nhanh khối ↔ file ↔ hàm](#39-bảng-tra-nhanh-khối--file--hàm)
- [40. Câu hỏi thường gặp](#40-câu-hỏi-thường-gặp)
- [41. Chẩn đoán sự cố](#41-chẩn-đoán-sự-cố)
- [42. Thuật ngữ](#42-thuật-ngữ)
- [43. Toàn cảnh một trang](#43-toàn-cảnh-một-trang)

---
---

# PHẦN I — TỔNG QUAN

---

## 0. Cách đọc tài liệu này

Tài liệu này viết theo nguyên tắc **một chiều, không nhảy cóc**, giống hệt
`docs2/CRAWLER-PIPELINE.md`: mọi mục xuất hiện theo đúng thứ tự mà CPU thực sự
chạy qua khi xử lý một truy vấn tìm kiếm — từ chuỗi ký tự người dùng gõ vào ô tìm
kiếm, tới JSON `SearchResponse` trả về trình duyệt.

### Quy ước ký hiệu

| Ký hiệu | Nghĩa |
|---|---|
| **File:** `abc/Xyz.java` | Đường dẫn tính từ `backend/libs/core-search/src/main/java/com/vnsearch/` trừ khi ghi rõ module khác |
| **Hàm:** `foo()` | Tên phương thức trong file vừa nêu |
| ① ② ③ | Số thứ tự bước trong một chuỗi xử lý |
| ★ | Điểm mấu chốt, dễ hiểu sai |
| ⚠ | Cạm bẫy đã từng gây lỗi thật hoặc hạn chế đã biết |
| ↺ | Vòng lặp khép kín (feedback loop) |
| 🔒 | Điểm đồng bộ hoá (lock / cache / trạng thái chia sẻ) |

### Ba mức chi tiết

1. **Mức sơ đồ** — một hình Mermaid, hiểu trong 10 giây.
2. **Mức mã** — trích đoạn mã thật, đã lược bỏ getter/log cho gọn.
3. **Mức lập luận** — vì sao viết như vậy, viết khác thì hỏng ở đâu.

### Quan hệ với các tài liệu khác trong `docs2/`

Tài liệu này là bản **dệt liền mạch** của mười hai tài liệu per-class đã có sẵn
tại `docs2/main/java/com/vnsearch/query/**/*.md` (chặng 5 trong
`docs2/main/roadmap.md`, mục #68–#79). Nếu tài liệu per-class mô tả **một lớp**
theo chiều sâu, tài liệu này mô tả **một truy vấn thật** đi xuyên qua tất cả các
lớp đó theo đúng trình tự thực thi — giống quan hệ giữa `docs2/CRAWLER-PIPELINE.md`
và các tài liệu per-class của gói `crawler`.

Tầng truy vấn đọc **ngược chiều** với tầng chỉ mục (`docs2/INDEX-PIPELINE.md`):
tầng chỉ mục biến hàng nghìn tài liệu thành danh sách posting; tầng này biến
một chuỗi người dùng gõ thành phép toán tập hợp trên chính các danh sách posting
đó. Việc xếp hạng kết quả (`docs2/RANKING-PIPELINE.md`) là chặng kế tiếp, nhận
đầu vào là tập ứng viên do tài liệu này tạo ra.

---

## 1. Điểm vào và vòng đời một request

```
GET /api/search?q=máy%20tính&page=1&size=10
```

**File:** `services/search-service/src/main/java/com/vnsearch/controller/SearchController.java`
(gói `com.vnsearch.controller`). `SearchController` nhận request HTTP, chặn
`page` vào khoảng `[1, MAX_PAGE=1000]` và `size` vào khoảng `[1, MAX_SIZE=100]`
(ngoài khoảng thì dùng `DEFAULT_SIZE=20`), rồi gọi thẳng một hàm duy nhất trên
`SearchEngineFacade` (gói `com.vnsearch.service`, module `libs/core-search`):

```java
int safePage = Math.min(Math.max(page, 1), MAX_PAGE);
int safeSize = size < 1 || size > MAX_SIZE ? DEFAULT_SIZE : size;
return facade.search(q, safePage, safeSize);
```

★ `MAX_PAGE = 1000` tồn tại để chặn một phép nhân `int` có thể tràn:
`SearchEngineFacade.search` tính `topN = page * size`; với `page` rất lớn và
`size = 100`, phép nhân này tràn `int` và cho ra một giá trị vô nghĩa (có thể
âm). Hậu quả thực tế bằng không — `MinHeap.topK` không bao giờ giữ nhiều hơn
số ứng viên thật có — nhưng đó là một bất biến được một lớp **khác** giữ hộ,
và Javadoc của hằng số này gọi thẳng đó là "loại phụ thuộc ngầm nguy hiểm mà
phần còn lại của dự án cần tránh". Chặn tại đây, nơi người dùng nhập vào, là
chặn tại nguồn.

`SearchController` **không** chứa logic nghiệp vụ nào — nó chỉ là lớp chuyển đổi
HTTP ↔ Java (đọc query param, set content-type, bắt exception thành mã lỗi HTTP).
Toàn bộ "bộ não" của việc tìm kiếm nằm trong `SearchEngineFacade.search`, đúng
tinh thần **Facade pattern**: một điểm vào duy nhất che giấu năm, sáu lớp con bên
dưới (`QueryParser`, `CandidateResolver`, `PostingListMerger`, `ResultRanker`,
`LRUCache`, `SuggestionService`) mà tầng gọi (HTTP controller, hoặc test, hoặc
CLI) không cần biết chi tiết.

### 1.1 Bảng tham số

| Tham số | Kiểu | Mặc định | Ảnh hưởng |
|---|---|---|---|
| `q` | `String` | *(bắt buộc, rỗng → không kết quả)* | Chuỗi thô đưa thẳng vào `QueryParser.parse` |
| `page` | `int` | `1`, trần `1000` | Cắt trang: `fromIndex = (max(page,1)-1) * size`; cũng nhân vào `topN` |
| `size` | `int` | `20`, trần `100` | Số kết quả mỗi trang, cũng là một phần của `topN` cho `ResultRanker` |

### 1.2 Vì sao `SearchEngineFacade` là lớp trung tâm của toàn bộ tài liệu này

Mọi mục còn lại của tài liệu — phân tích cú pháp, truy hồi ứng viên, xếp hạng,
cache — đều là các bước **bên trong thân hàm `search()`** của lớp này. Nắm được
khung xương của hàm này trước, rồi mới đi sâu từng bước, là cách đọc hiệu quả
nhất (giống cách `CrawlerService.crawl()` là khung xương của toàn bộ
`CRAWLER-PIPELINE.md`).

---

## 2. Bản đồ toàn hệ thống

### 2.1 Sơ đồ khối chính thức

```mermaid
flowchart TD
    REQ(["GET /api/search?q=…&page=&size="]) --> CTRL["SearchController"]
    CTRL --> FACADE["SearchEngineFacade.search"]

    FACADE --> SNAP["chụp 1 lần: index, scorer,<br/>pageRankScores, searchCache"]
    SNAP --> KEY["cacheKey = lower(q)+'|p'+page+'|s'+size"]
    KEY --> HIT{"LRUCache.get(cacheKey)"}
    HIT -->|"trúng"| RETCACHE(["trả ngay, cacheHits++"])
    HIT -->|"trượt"| PARSE

    PARSE["QueryParser.parse(q)<br/>→ ParsedQuery"] --> RESOLVE["CandidateResolver.resolve<br/>(index, parsed)"]

    subgraph AST["Cây AST — Composite pattern"]
        direction LR
        TN["TermNode"]
        PN["PhraseNode"]
        ON["OrNode"]
        NN["NotNode"]
        AN["AndNode"]
    end

    RESOLVE --> AST
    AST --> MERGE["PostingListMerger<br/>union / intersect / matchesPhrase"]
    MERGE --> FILTERS["DomainFilter → MaxCandidatesFilter<br/>(Chain of Responsibility)"]
    FILTERS --> EMPTY{"rỗng?"}
    EMPTY -->|"có"| RELAX["GIAI ĐOẠN 2: relaxAndRetry<br/>bỏ df=0 → bỏ dần theo df giảm"]
    RELAX --> RANK
    EMPTY -->|"không"| RANK

    RANK["ResultRanker.rank<br/>(candidates, qtf, index, scorer,<br/>pageRank, topN)"] --> PAGE["cắt trang: fromIndex..toIndex"]
    PAGE --> RESP["SearchResponse(query, totalHits,<br/>page, size, elapsedMs, results, droppedTerms)"]
    RESP --> CACHEPUT["cache.put(cacheKey, response)"]
    CACHEPUT --> LEARN{"candidates không rỗng?"}
    LEARN -->|"có"| SUGGEST["SuggestionService.learnFromQuery"]
    LEARN --> OUT(["trả về SearchResponse"])
    SUGGEST --> OUT

    style FACADE fill:#2d6cdf,color:#fff
    style AST fill:#f3e8fd
    style RESP fill:#0b7a3b,color:#fff
    style RETCACHE fill:#0b7a3b,color:#fff
```

<details><summary>Xem bản chữ (ASCII)</summary>

```
GET /api/search?q=..&page=..&size=..
  -> SearchController
     -> SearchEngineFacade.search
        [chup 1 lan: index, scorer, pageRankScores, searchCache]
        -> cacheKey
        -> LRUCache.get  --trung--> tra ngay
                         --truot-->
           -> QueryParser.parse           => ParsedQuery
           -> CandidateResolver.resolve
              -> buildAst (Composite: TermNode/PhraseNode/OrNode/NotNode/AndNode)
              -> ast.evaluate              (PostingListMerger: union/intersect/matchesPhrase)
              -> applyFilters              (DomainFilter -> MaxCandidatesFilter)
              -> rong? --co--> relaxAndRetry (GIAI DOAN 2)
           -> ResultRanker.rank
           -> cat trang
           -> SearchResponse
           -> cache.put
           -> khong rong? -> SuggestionService.learnFromQuery
        <- SearchResponse
```

</details>

### 2.2 Bản đồ gói (package)

```mermaid
flowchart TB
    subgraph API["com.vnsearch.controller + com.vnsearch.service"]
        SC["SearchController"]
        SEF["SearchEngineFacade"]
        SR["SearchResponse (com.vnsearch.model)"]
    end

    subgraph QP["com.vnsearch.query"]
        QPR["QueryParser"]
        CR["CandidateResolver"]
        PLM["PostingListMerger"]
    end

    subgraph AST2["com.vnsearch.query.ast"]
        QN["QueryNode «interface»"]
        TN2["TermNode"]
        PN2["PhraseNode"]
        AN2["AndNode"]
        ON2["OrNode"]
        NN2["NotNode"]
    end

    subgraph FLT["com.vnsearch.query.filter"]
        CF["CandidateFilter «interface»"]
        DF["DomainFilter"]
        MCF["MaxCandidatesFilter"]
    end

    subgraph IDX["com.vnsearch.index (tầng chỉ mục, đọc)"]
        SI["SearchIndex"]
        TOK["VietnameseTokenizer"]
    end

    subgraph RANK["com.vnsearch.rank (chặng kế tiếp)"]
        RR["ResultRanker"]
    end

    SC --> SEF
    SEF --> QPR
    SEF --> CR
    SEF --> RR
    QPR --> AST2
    QPR -.->|"tokenizer chung"| TOK
    CR --> QPR
    CR --> AST2
    CR --> FLT
    AST2 --> PLM
    PLM --> SI
    TN2 --> SI
    CR --> SI

    style API fill:#e8f0fe
    style QP fill:#e6f4ea
    style AST2 fill:#f3e8fd
    style FLT fill:#fef7e0
    style IDX fill:#fce8e6
    style RANK fill:#e8eaed
```

---

## 3. Danh mục toàn bộ file tham gia

Một truy vấn chạm tới khoảng **17 file** chính (không tính `SearchIndex` và
`Tokenizer` — thuộc chặng chỉ mục, chỉ được *đọc*, không được sửa bởi tầng này).

### 3.1 Gói `com.vnsearch.query` — 3 file

| File | Vai trò |
|---|---|
| `query/QueryParser.java` | Phân tích cú pháp chuỗi → `ParsedQuery`, dựng cây AST |
| `query/CandidateResolver.java` | Điều phối: xây tần suất từ khoá, đánh giá AST, áp bộ lọc, nới lỏng khi rỗng |
| `query/PostingListMerger.java` | Thuật toán nền: `union`, `intersect`, `intersectAll`, `matchesPhrase` |

### 3.2 Gói `com.vnsearch.query.ast` — 6 file

| File | Vai trò |
|---|---|
| `query/ast/QueryNode.java` | Giao diện `sealed`, hợp đồng chung của cây biểu thức |
| `query/ast/TermNode.java` | Nút lá — một term đơn |
| `query/ast/PhraseNode.java` | Nút lá — cụm từ liên tiếp |
| `query/ast/AndNode.java` | Nút trong — giao |
| `query/ast/OrNode.java` | Nút trong — hợp |
| `query/ast/NotNode.java` | Nút trong — loại trừ (chỉ hợp lệ trong `AndNode`) |

### 3.3 Gói `com.vnsearch.query.filter` — 3 file

| File | Vai trò |
|---|---|
| `query/filter/CandidateFilter.java` | Giao diện Chain of Responsibility |
| `query/filter/DomainFilter.java` | Lọc theo `site:` |
| `query/filter/MaxCandidatesFilter.java` | Trần số ứng viên `10 000` |

### 3.4 Tầng gọi và tầng kế tiếp (đọc để hiểu ranh giới, không thuộc tài liệu này)

| File | Vai trò | Tài liệu chi tiết |
|---|---|---|
| `controller/SearchController.java` (module `services/search-service`) | Điểm vào HTTP, chặn `page`/`size` | — |
| `service/SearchEngineFacade.java` (module `libs/core-search`) | Điều phối toàn bộ pipeline, cache, học gợi ý | Mục 6–7, 28–30 của tài liệu này |
| `index/SearchIndex.java` | Chỉ mục ngược — nguồn `getPostings`, `getDocumentFrequency`, `getTotalDocs` | `docs2/INDEX-PIPELINE.md` |
| `index/VietnameseTokenizer.java` | Tokenizer dùng chung index-time và query-time | `docs2/INDEX-PIPELINE.md` |
| `rank/ResultRanker.java` | Xếp hạng tập ứng viên | `docs2/RANKING-PIPELINE.md` |

---

## 4. Sơ đồ tuần tự tổng quát

```mermaid
sequenceDiagram
    participant U as Người dùng
    participant C as SearchController
    participant F as SearchEngineFacade
    participant P as QueryParser
    participant R as CandidateResolver
    participant M as PostingListMerger
    participant K as ResultRanker

    U->>C: GET /api/search?q=...
    C->>F: search(q, page, size)
    F->>F: cacheKey; LRUCache.get
    alt cache trúng
        F-->>C: SearchResponse (từ cache)
    else cache trượt
        F->>P: parse(q)
        P-->>F: ParsedQuery
        F->>R: resolve(index, parsed)
        R->>P: buildAst(parsed)
        P-->>R: QueryNode (hoặc null)
        R->>R: ast.evaluate(index)
        R->>M: intersect / union / matchesPhrase
        M-->>R: List<Integer> docId
        R->>R: applyFilters (Domain, MaxCandidates)
        opt candidates rỗng
            R->>R: relaxAndRetry (bỏ term dần)
        end
        R-->>F: ResolvedQuery(candidates, qtf, droppedTerms)
        F->>K: rank(candidates, qtf, index, scorer, pageRank, topN)
        K-->>F: List<ScoredResult>
        F->>F: cắt trang, build SearchResponse
        F->>F: cache.put
        opt candidates không rỗng
            F->>F: SuggestionService.learnFromQuery
        end
        F-->>C: SearchResponse
    end
    C-->>U: JSON
```

---

## 5. Vòng đời của một truy vấn: chuỗi → `ParsedQuery` → cây AST → tập ứng viên

Bốn dạng biểu diễn kế tiếp nhau của cùng một truy vấn:

```
"biến đổi khí hậu" việt nam -mỹ
        │  (chuỗi thô, người dùng gõ)
        ▼  QueryParser.parse
ParsedQuery(
  mustTerms     = [việt_nam]
  phrases       = [[biến_đổi, khí_hậu]]
  excludedTerms = [mỹ]
  orGroups      = []
  siteFilter    = null
)
        │  (cấu trúc phẳng, ba/năm danh sách)
        ▼  QueryParser.buildAst
AndNode[
  TermNode(việt_nam),
  PhraseNode([biến_đổi, khí_hậu]),
  NotNode(TermNode(mỹ))
]
        │  (cây, Composite pattern, lồng nhau được)
        ▼  CandidateResolver.resolve → ast.evaluate(index)
[12, 47, 205, ...]           (danh sách docId, sắp tăng dần)
```

Mỗi mũi tên là một PHẦN riêng trong tài liệu này: PHẦN II là mũi tên thứ nhất,
PHẦN III là cấu trúc dữ liệu đích của mũi tên thứ hai, PHẦN IV là mũi tên thứ ba.

---

## 6. `SearchEngineFacade` — vì sao chụp trạng thái một lần

★ Ngay khi vào `search()`, facade đọc bốn trường có thể bị thay đổi bởi một
lần **reindex chạy nền** (`index`, `scorer`, `pageRankScores`, `searchCache`) và
gán chúng vào **biến cục bộ**, chỉ một lần duy nhất, trước khi làm bất cứ việc gì
khác:

```
SearchIndex   localIndex   = this.index;
Scorer        localScorer  = this.scorer;
Map<Integer,Double> localPageRank = this.pageRankScores;
LRUCache<...> localCache   = this.searchCache;
```

Đây không phải là chi tiết trang trí — nó ngăn một lớp lỗi cụ thể: nếu một luồng
reindex hoán các trường `index`/`pageRankScores` của facade **giữa lúc** một
request đang xử lý (ví dụ đọc xong `index` mới nhưng `pageRankScores` vẫn đọc
lại — không qua biến cục bộ — thì có thể vô tình lấy bản **cũ**, đã bị thay ngay
sau đó), request đó sẽ tính điểm cho một tài liệu bằng **PageRank của một đồ thị
liên kết khác** với chỉ mục đang dùng để tra posting. Hai cấu trúc dữ liệu lệch
pha nhau âm thầm — không ném exception, chỉ cho điểm sai một cách khó phát hiện.
Chụp một lần vào biến cục bộ biến toàn bộ phần còn lại của `search()` thành một
**khung nhìn nhất quán** (consistent snapshot) của một thế hệ chỉ mục duy nhất,
bất kể việc gán lại trường `this.index` ở nơi khác xảy ra bất cứ lúc nào.

---

## 7. LRU cache truy vấn

```
cacheKey = lower(query) + "|p" + page + "|s" + size
```

| Thành phần | Vì sao có mặt |
|---|---|
| `lower(query)` | Tìm kiếm không phân biệt hoa/thường — `"Hà Nội"` và `"hà nội"` phải trúng cùng cache entry |
| `"|p" + page` | Trang 1 và trang 2 của cùng một truy vấn là hai response khác nhau |
| `"|s" + size` | `size=10` và `size=20` cắt trang khác nhau, không thể dùng chung |

`LRUCache.get(cacheKey)` trúng thì trả **ngay** `SearchResponse` đã lưu, tăng bộ
đếm `cacheHits`, và toàn bộ phần còn lại của `search()` (phân tích, truy hồi, xếp
hạng) **không chạy**. Vì `SearchResponse.timeTakenMs` là thời gian của lần tính
**gốc** (`System.currentTimeMillis() - start` được tính TRƯỚC khi `cache.put`),
một response trả từ cache vẫn mang thời gian xử lý **cũ** — đây là điều cần biết
khi đọc số liệu độ trễ từ client: `timeTakenMs` không phản ánh độ trễ của
request hiện tại nếu nó là cache hit.

---


---

# PHẦN II — PHÂN TÍCH CÚ PHÁP: `QueryParser`

**File:** `query/QueryParser.java` (249 dòng)

---

## 8. Ba bước của `QueryParser.parse`

```java
private static final Pattern PHRASE_PATTERN = Pattern.compile("\"([^\"]*)\"");
private static final String OR_KEYWORD = "OR";
private static final String SITE_PREFIX = "site:";
```

Javadoc của lớp ghi rõ một **bất biến quyết định** (dòng 32-38), lý do vì sao
constructor nhận `Tokenizer` từ bên ngoài thay vì tự khởi tạo:

> **BAT BIEN QUYET DINH.** Truy van phai duoc tokenize bang CHINH `Tokenizer` da
> dung luc index. Neu luc index tao ra `máy_tính` ma luc truy van tao ra `máy` +
> `tính` thi **khong bao gio khop** — va loi nay IM LANG, khong nem ngoai le
> nao, chi la ket qua rong mot cach kho hieu.

★ Đây là mối liên kết trực tiếp với `docs2/INDEX-PIPELINE.md`: `QueryParser` và
tầng chỉ mục **phải** dùng chung một thực thể lớp `VietnameseTokenizer` (hoặc ít
nhất một cấu hình tokenizer tương đương tuyệt đối), nếu không toàn bộ tìm kiếm
im lặng trả về rỗng cho mọi từ ghép — không có ngoại lệ, không có log cảnh báo,
chỉ là "không tìm thấy gì" khó chẩn đoán.

`parse(rawQuery)` chạy ba bước tuần tự trên **cùng một chuỗi**, mỗi bước tiêu thụ
đầu ra của bước trước:

```mermaid
flowchart LR
    A["rawQuery"] --> B["Bước 1: PHRASE_PATTERN<br/>cắt cụm '...' ra khỏi chuỗi"]
    B --> C["remaining (không còn cặp ngoặc kép)"]
    C --> D["Bước 2: split theo \\s+<br/>site: / OR / -loại_trừ / còn lại"]
    D --> E["mustRaw, excludedRaw,<br/>orGroupsRaw, siteFilter"]
    E --> F["Bước 3: tokenizeToTerms<br/>(mỗi phần dùng Tokenizer chung)"]
    F --> G["ParsedQuery"]

    style A fill:#2d6cdf,color:#fff
    style G fill:#0b7a3b,color:#fff
```

<details><summary>Xem bản chữ (ASCII)</summary>

```
rawQuery
  -> Buoc 1: PHRASE_PATTERN cat cum "..." ra khoi chuoi
     -> remaining (khong con cap ngoac kep) + phrasesRaw
  -> Buoc 2: split theo \s+  =>  site: / OR / -loai_tru / con lai
     -> mustRaw, excludedRaw, orGroupsRaw, siteFilter
  -> Buoc 3: tokenizeToTerms (moi phan dung Tokenizer chung voi index)
     -> ParsedQuery
```

</details>

Query `null` hoặc blank thoát sớm, trả `ParsedQuery(List.of(), List.of(), List.of())`
(dòng 80-82) — không chạy ba bước, không lãng phí một lần biên dịch regex.

---

## 9. Bước 1 — cắt cụm từ trong ngoặc kép

```java
// dòng 88-99
List<String> phrasesRaw = new ArrayList<>();
Matcher matcher = PHRASE_PATTERN.matcher(rawQuery);
StringBuilder remaining = new StringBuilder();
int lastEnd = 0;
while (matcher.find()) {
    remaining.append(rawQuery, lastEnd, matcher.start());
    if (!matcher.group(1).isBlank()) {
        phrasesRaw.add(matcher.group(1));
    }
    lastEnd = matcher.end();
}
remaining.append(rawQuery.substring(lastEnd));
```

Thuật toán: duyệt từng match của regex `"([^"]*)"`, **chỉ nối phần TRƯỚC match**
vào `remaining` (bỏ qua chính đoạn nằm trong ngoặc — nó đi vào `phrasesRaw`
riêng), rồi sau vòng lặp nối nốt phần đuôi còn lại sau match cuối cùng.

★ Comment ngay trong mã (dòng 85-87) giải thích lý do phải cắt **trước** khi làm
bất kỳ điều gì khác:

> Giu lai phan NGOAI ngoac vao `remaining`; neu khong, cac tieng cua cum se VUA
> la phrase VUA la mustTerm, bi dem hai lan trong `queryTermFrequency` va lam
> sai trong so truy van.

Nếu bước này không tồn tại, truy vấn `"biến đổi khí hậu"` sẽ vừa sinh ra
`PhraseNode([biến_đổi, khí_hậu])` (nếu có bước xử lý ngoặc riêng ở chỗ khác) vừa
để nguyên các tiếng `biến`, `đổi`, `khí`, `hậu` lẫn vào dòng `mustRaw` — khiến
`buildQueryTermFrequency` (mục 23) đếm hai lần cho cùng khái niệm, làm lệch
trọng số khi xếp hạng.

### 9.1 Trường hợp biên

| Đầu vào | Kết quả |
|---|---|
| `""` (cặp ngoặc rỗng) | `matcher.group(1).isBlank()` → **bị bỏ qua**, không thêm vào `phrasesRaw`, nhưng đoạn `""` vẫn bị cắt khỏi `remaining` |
| `"a` (ngoặc không đóng) | Regex `"([^"]*)"` không khớp (thiếu ký tự `"` đóng) → **không cắt gì cả**, cả cụm kể cả dấu `"` còn nguyên trong `remaining`, bị bước 2/3 xử lý như văn bản thường |
| `"a" "b"` (hai cụm) | Hai match độc lập → `phrasesRaw = ["a", "b"]`, mỗi cụm tokenize **riêng** ở bước 3 |

---

## 10. Bước 2 — quét token: `site:`, `OR`, `-loại_trừ`, còn lại

```java
// dòng 107-146
String[] words = remaining.toString().trim().split("\\s+");
for (int i = 0; i < words.length; i++) {
    String word = words[i];
    if (word.isEmpty()) continue;

    if (word.toLowerCase(Locale.ROOT).startsWith(SITE_PREFIX)) {
        String host = word.substring(SITE_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        if (!host.isEmpty()) siteFilter = host;
        continue;
    }

    if (OR_KEYWORD.equals(word) && !mustRaw.isEmpty() && i + 1 < words.length) {
        String left = mustRaw.remove(mustRaw.size() - 1);
        List<String> group = new ArrayList<>();
        group.add(left);
        while (i + 1 < words.length) {
            group.add(words[i + 1]);
            i++;
            if (i + 1 < words.length && OR_KEYWORD.equals(words[i + 1])) {
                i++; // bỏ qua từ khoá OR tiếp theo
            } else {
                break;
            }
        }
        orGroupsRaw.add(group);
        continue;
    }

    if (word.startsWith("-") && word.length() > 1) {
        excludedRaw.add(word.substring(1));
    } else if (!word.equals("-")) {
        mustRaw.add(word);
    }
}
```

Mỗi từ (đã tách bởi khoảng trắng) rơi vào đúng **một** trong bốn nhóm:

```mermaid
flowchart TD
    W["một từ trong remaining.split"] --> Q1{"bắt đầu bằng<br/>'site:' (không phân biệt hoa/thường)?"}
    Q1 -->|"có, host không rỗng"| SITE["siteFilter = host (lowercase)"]
    Q1 -->|"không"| Q2{"== 'OR' và<br/>mustRaw không rỗng và<br/>còn từ phía sau?"}
    Q2 -->|"có"| ORG["pop từ cuối mustRaw làm left<br/>gom liên tiếp 'OR' → orGroupsRaw"]
    Q2 -->|"không"| Q3{"bắt đầu bằng '-'<br/>và dài hơn 1 ký tự?"}
    Q3 -->|"có"| EXC["excludedRaw += (bỏ dấu '-')"]
    Q3 -->|"không"| Q4{"== '-' đơn độc?"}
    Q4 -->|"có"| DROP(["bị bỏ hẳn — không must, không excluded"])
    Q4 -->|"không"| MUST["mustRaw += word"]
```

<details><summary>Xem bản chữ (ASCII)</summary>

```
tu word
  bat dau "site:" (khong phan biet hoa/thuong)?
    co, host khong rong -> siteFilter = host (lowercase)
    khong ->
      == "OR" va mustRaw khong rong va con tu phia sau?
        co -> pop tu cuoi mustRaw lam left; gom lien tiep OR -> orGroupsRaw
        khong ->
          bat dau "-" va dai hon 1 ky tu?
            co -> excludedRaw += (bo dau "-")
            khong ->
              == "-" don doc? co -> bo han (khong must, khong excluded)
              khong -> mustRaw += word
```

</details>

### 10.1 `site:` — điều kiện host rỗng

`site:` theo sau bởi chuỗi rỗng (ví dụ gõ `site:` một mình, hoặc `site: `) thì
`host.isEmpty()` đúng và dòng `if (!host.isEmpty())` **không set** `siteFilter`
(dòng 116-118) — từ `site:` bị nuốt (nhánh `continue`) mà không để lại dấu vết
nào trong `ParsedQuery`, không rơi xuống `mustRaw`.

### 10.2 ★ `OR` — chỉ gom được **một từ đơn** mỗi bên, không phải cụm từ

Điều kiện `!mustRaw.isEmpty() && i + 1 < words.length` (dòng 123) có nghĩa `OR`
đứng **đầu câu** hoặc **cuối câu** không được nhận diện như từ khoá — nó rơi
thẳng xuống nhánh "còn lại" và trở thành một `mustTerm` bình thường (tokenize ra
chữ `or` — vì `VietnameseTokenizer` không phân biệt hoa/thường ở bước sau).

Khi hợp lệ: `mustRaw.remove(mustRaw.size() - 1)` lấy phần tử **cuối cùng vừa mới
được thêm** vào `mustRaw` làm vế trái. ★ Điều này kéo theo một hệ quả rất dễ bị
hiểu lầm: **`OR` chỉ gộp được từ đơn liền kề ngay trước và ngay sau nó theo ranh
giới khoảng trắng, không phải theo ranh giới từ ghép tiếng Việt.** Ví dụ
`laptop OR máy tính giá rẻ` — vế phải của `OR` chỉ là từ thô `máy` (một "word"
theo `split("\\s+")`), còn `tính` bị coi là một mustTerm độc lập tách rời khỏi
`máy`. Xem trace thật ở mục 14.2.

Vòng `while` (dòng 128-136) gom dây `OR` liên tiếp: mỗi lần lặp thêm từ kế tiếp
vào `group`, rồi kiểm tra xem từ **sau** nữa có phải `OR` không — nếu có thì nuốt
luôn từ khoá đó và lặp tiếp; nếu không thì dừng. Nhờ vậy `a OR b OR c` gom thành
**một** nhóm ba phần tử `[a, b, c]` chứ không phải lồng nhau `(a OR b) OR c`.

### 10.3 `-loại_trừ` và dấu `-` đơn độc

`word.startsWith("-") && word.length() > 1` loại trừ trường hợp `word` chỉ là
chính dấu `-` (length == 1) — điều kiện `else if (!word.equals("-"))` ở nhánh cuối
đảm bảo một dấu gạch ngang đứng một mình **không rơi vào `mustRaw` lẫn
`excludedRaw`**, nó bị bỏ hẳn, im lặng.

---

## 11. Bước 3 — tokenize bằng chung tokenizer với tầng chỉ mục

```java
// dòng 152-174
List<String> mustTerms = tokenizeToTerms(String.join(" ", mustRaw));
List<String> excludedTerms = tokenizeToTerms(String.join(" ", excludedRaw));

List<List<String>> phrases = new ArrayList<>();
for (String phraseRaw : phrasesRaw) {
    List<String> phraseTerms = tokenizeToTerms(phraseRaw);
    if (!phraseTerms.isEmpty()) phrases.add(phraseTerms);
}

List<List<String>> orGroups = new ArrayList<>();
for (List<String> groupRaw : orGroupsRaw) {
    List<String> alternatives = new ArrayList<>();
    for (String alternative : groupRaw) {
        alternatives.addAll(tokenizeToTerms(alternative));
    }
    if (alternatives.size() > 1) {
        orGroups.add(alternatives);
    } else if (alternatives.size() == 1) {
        mustTerms = new ArrayList<>(mustTerms);
        mustTerms.add(alternatives.get(0)); // OR một vế thì thành AND
    }
}
```

Ba cách gọi `tokenizeToTerms` khác nhau về **phạm vi ngữ cảnh** đưa vào tokenizer
— đây là chỗ dễ nhầm nhất của cả file:

| Phần | Cách nối trước khi tokenize | Vì sao |
|---|---|---|
| `mustRaw` | `String.join(" ", mustRaw)` — nối **toàn bộ** rồi tokenize **một lần chung** | Đủ ngữ cảnh để bộ tokenize ghép từ (longest-matching) nhận ra từ ghép nằm vắt qua ranh giới hai "word" thô, ví dụ `việt` + `nam` gộp thành `việt_nam` |
| `excludedRaw` | Giống `mustRaw` — nối chung rồi tokenize một lần | Cùng lý do — loại trừ cũng cần ghép từ đúng |
| mỗi `phraseRaw` | Tokenize **riêng biệt từng cụm** (KHÔNG nối các cụm với nhau, KHÔNG nối với `mustRaw`) | Mỗi cặp ngoặc kép là một đơn vị độc lập về mặt ngữ nghĩa — cụm này không được phép mượn ngữ cảnh của cụm khác |
| mỗi `alternative` trong một `orGroup` | Tokenize **riêng từng alternative** rồi gộp list | Mỗi vế OR là một khái niệm độc lập, không ghép từ chéo giữa `laptop` và `máy` |

### 11.1 ★ "OR một vế" hạ xuống thành `mustTerm`

Khi `alternatives.size() == 1` sau khi tokenize (dòng 171-174) — nghĩa là dù
`orGroupsRaw` từng có ≥ 2 "word" thô, sau khi tokenize hoá chúng lại **gộp về
đúng một term** (ví dụ do một bên rỗng, hoặc do tokenizer coi hai word là cùng
một khái niệm) — nhóm OR không còn ý nghĩa phân nhánh, code hạ nó thành một
`mustTerm` bình thường thay vì tạo một `OrNode` chỉ có một con. Dòng
`mustTerms = new ArrayList<>(mustTerms)` (dòng 172) tạo bản sao mới trước khi
`add` — vì `mustTerms` tại đây vẫn đang trỏ tới kết quả `List` trả về từ
`tokenizeToTerms`, an toàn hơn là sửa tại chỗ một list có thể bất biến.

---

## 12. `ParsedQuery` — cấu trúc dữ liệu kết quả

```java
public record ParsedQuery(List<String> mustTerms, List<List<String>> phrases,
                           List<String> excludedTerms, List<List<String>> orGroups,
                           String siteFilter) {

    public ParsedQuery(List<String> mustTerms, List<List<String>> phrases, List<String> excludedTerms) {
        this(mustTerms, phrases, excludedTerms, List.of(), null);
    }

    public boolean isEmpty() {
        return mustTerms.isEmpty() && phrases.isEmpty() && orGroups.isEmpty();
    }
}
```

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `mustTerms` | `List<String>` | Term đơn, AND ngầm định với nhau và với mọi phrase/orGroup |
| `phrases` | `List<List<String>>` | Mỗi phần tử là một cụm — danh sách term theo đúng thứ tự phải xuất hiện liên tiếp |
| `excludedTerms` | `List<String>` | Term sau dấu `-`, tài liệu chứa chúng bị loại |
| `orGroups` | `List<List<String>>` | Mỗi phần tử là một nhóm lựa chọn — các nhóm AND với nhau, bên trong mỗi nhóm là OR |
| `siteFilter` | `String` (nullable) | Host phải khớp, `null` nếu không có `site:` |

★ Có **constructor rút gọn** ba tham số (dòng 70-72) để tương thích ngược với mã
cũ chưa biết `orGroups`/`siteFilter` — gọi nó tự động điền `orGroups = List.of()`
và `siteFilter = null`.

⚠ `isEmpty()` (dòng 74-76) **không** kiểm tra `excludedTerms` lẫn `siteFilter`.
Một truy vấn chỉ gồm `-spam` (chỉ có excludedTerms) hoặc chỉ có `site:abc.vn`
(chỉ có siteFilter) được `isEmpty()` báo là **rỗng** — điều này nhất quán với
hành vi của `buildAst` (mục 13): cả hai trường hợp đều không có "mệnh đề khẳng
định" nào để truy hồi, nên coi là truy vấn rỗng là đúng ngữ nghĩa tìm kiếm dù có
vẻ ngược trực giác khi đọc code lần đầu.

---

## 13. `buildAst` — dựng cây từ `ParsedQuery`

```java
// dòng 194-217
public QueryNode buildAst(ParsedQuery parsed) {
    List<QueryNode> children = new ArrayList<>();

    for (String term : parsed.mustTerms())
        children.add(new TermNode(term));
    for (List<String> phrase : parsed.phrases())
        children.add(new PhraseNode(phrase));
    for (List<String> group : parsed.orGroups()) {
        List<QueryNode> alternatives = new ArrayList<>(group.size());
        for (String alternative : group)
            alternatives.add(new TermNode(alternative));
        children.add(new OrNode(alternatives));
    }
    if (children.isEmpty()) {
        return null; // không có mệnh đề khẳng định -> không truy hồi được
    }
    for (String excluded : parsed.excludedTerms())
        children.add(new NotNode(new TermNode(excluded)));
    return new AndNode(children);
}
```

★★ Điểm mấu chốt nhất của toàn hàm: **`excludedTerms` chỉ được thêm vào
`children` SAU khi đã kiểm tra `children.isEmpty()`.** Trình tự này không phải
ngẫu nhiên — nó là cơ chế phòng thủ khiến một truy vấn **chỉ toàn dấu trừ** (ví
dụ người dùng chỉ gõ `-quảng_cáo`) không bao giờ đi tới việc tạo ra một `AndNode`
mà mọi con đều là `NotNode`. Tại thời điểm kiểm tra `isEmpty()`, các `NotNode`
còn chưa được thêm, nên nếu `mustTerms`/`phrases`/`orGroups` đều rỗng thì hàm trả
`null` ngay — `CandidateResolver` nhận `null` sẽ trả về tập ứng viên rỗng **mà
không hề gọi `ast.evaluate()`**.

Đây chính là lý do, khi đọc mã `AndNode.evaluate()` (PHẦN III), việc
`positives.isEmpty()` ném `UnsupportedOperationException` gần như **không bao
giờ xảy ra trên đường đi qua `buildAst` bình thường** — `AndNode` tự vệ hai lớp:
lớp ngoài (`buildAst`, trả `null` sớm) và lớp trong (`AndNode` tự ném exception
nếu vẫn lọt vào tình huống toàn NOT, ví dụ khi ai đó dựng `AndNode` trực tiếp mà
không qua `buildAst`, như trong test).

---

## 14. Trace 3 truy vấn mẫu qua `QueryParser`

Ba lần chạy **thật** dưới đây được lấy bằng cách biên dịch và chạy trực tiếp
`QueryParser.parse()` + `buildAst()` (cùng `VietnameseTokenizer` thật, cùng
từ điển từ ghép 185.000 mục thật của repo) — không phải suy đoán tay.

### 14.1 `trump iran` — AND ngầm định giữa hai term đơn

```
must=[trump, iran]
phrases=[]
excluded=[]
orGroups=[]
site=null
ast=(trump AND iran)
```

Không có ký tự đặc biệt nào, cả hai "word" đều là một tiếng đơn nên không có gì
để ghép — kết quả `mustTerms` giữ nguyên hai token gốc.

### 14.2 `"tơi bời" trump -mỹ` — cụm từ, must, và loại trừ cùng lúc

```
must=[trump]
phrases=[[tơi_bời]]
excluded=[mỹ]
orGroups=[]
site=null
ast=(trump AND "tơi_bời" AND NOT mỹ)
```

Ba quan sát đối chiếu trực tiếp với mã ở mục 9–13:

- Cụm `"tơi bời"` bị **Bước 1** cắt khỏi chuỗi trước khi Bước 2 chạy, nên nó
  không bao giờ lẫn vào `mustRaw` — đúng như lời giải thích ở mục 9.
- Hai tiếng `tơi` và `bời` được tokenize **thành một cụm riêng** (mục 11) và bộ
  ghép từ của `VietnameseTokenizer` nhận ra đây là một từ ghép có trong từ điển
  185.000 mục, cho ra đúng **một** token `tơi_bời` — `phrases=[[tơi_bời]]` chỉ
  có một phần tử trong danh sách trong, không phải hai token rời `tơi`, `bời`.
- `-mỹ` đi thẳng vào `excludedRaw` rồi `excludedTerms=[mỹ]`; `buildAst` đặt
  `NotNode(TermNode(mỹ))` **sau cùng** trong `AndNode`, đúng thứ tự dòng
  795-796 của mã.

★ Truy vấn này minh hoạ đúng cảnh báo ★★ ở mục 13: nếu người dùng gõ **chỉ**
`-mỹ` (không `trump`, không cụm nào), `children` rỗng tại thời điểm kiểm tra,
`buildAst` trả `null`, và `CandidateResolver` không bao giờ chạm tới
`ast.evaluate()`. Xem thêm hệ quả thật của truy vấn này (nó trả về **rỗng** vì
một lý do khác hẳn — loại trừ, không phải thiếu term) ở
[mục 35](#35-truy-vấn-2-tơi-bời-trump--mỹ--rỗng-vì-loại-trừ-không-vì-thiếu).

### 14.3 `sài gòn site:vnexpress.net` — cụm ghép qua ranh giới "word" và `site:`

```
must=[sài_gòn]
phrases=[]
excluded=[]
orGroups=[]
site=vnexpress.net
ast=(sài_gòn)
```

Hai "word" thô `sài` và `gòn` (không có dấu ngoặc kép nào) vẫn được ghép thành
đúng **một** token `sài_gòn` vì bước 3 tokenize **toàn bộ** `mustRaw` đã nối
bằng khoảng trắng trong **một lần gọi** (`String.join(" ", mustRaw)`, mục 11) —
bộ ghép từ có đủ ngữ cảnh để nhận ra từ ghép nằm vắt qua ranh giới hai "word".
`site:vnexpress.net` bị Bước 2 nuốt trọn thành `siteFilter`, không để lại dấu
vết trong `mustRaw`, nên cây AST chỉ còn đúng một `TermNode(sài_gòn)` — ràng
buộc domain được `CandidateResolver` áp ở **tầng lọc riêng**, không nằm trong
cây (xem [mục 6 phần đầu tài liệu](#6-hai-mẫu-thiết-kế-chia-nhau-công-việc)
và [mục 27](#27-bộ-lọc-ứng-viên-candidatefilter-domainfilter-maxcandidatesfilter)).

### 14.4 Bảng đối chiếu cả bốn truy vấn (kèm `trump OR iran`)

| Truy vấn | mustTerms | phrases | excludedTerms | orGroups | siteFilter |
|---|---|---|---|---|---|
| `trump iran` | `[trump, iran]` | `[]` | `[]` | `[]` | `null` |
| `"tơi bời" trump -mỹ` | `[trump]` | `[[tơi_bời]]` | `[mỹ]` | `[]` | `null` |
| `trump OR iran` | `[]` | `[]` | `[]` | `[[trump, iran]]` | `null` |
| `sài gòn site:vnexpress.net` | `[sài_gòn]` | `[]` | `[]` | `[]` | `vnexpress.net` |

Dòng `trump OR iran` xác nhận đúng mục 10.2: `OR` giữa hai từ đơn hợp lệ,
`mustRaw` (đã có `trump`) bị pop phần tử cuối làm vế trái, `iran` làm vế phải —
kết quả rơi thẳng vào `orGroups`, `mustTerms` trở lại rỗng.

---
---

# PHẦN III — CÂY CÚ PHÁP: GÓI `query.ast`

**Thư mục:** `query/ast/` — 6 file, tổng cộng dưới 300 dòng mã thực (phần lớn
là Javadoc). Đây là cấu trúc dữ liệu **Composite pattern**: một cây, mỗi nút là
một `record` cài `QueryNode`, đệ quy tự nhiên — nút lá trả posting list, nút
trong ghép kết quả của các con.

---

## 15. `QueryNode` — giao diện chung

**File:** `query/ast/QueryNode.java`

```java
public sealed interface QueryNode
        permits TermNode, PhraseNode, AndNode, OrNode, NotNode {

    List<Integer> evaluate(SearchIndex index);
    int estimatedSize(SearchIndex index);
    String describe();
}
```

Ba điểm thiết kế đáng chú ý:

- **`sealed` + `permits`** — Java 17 cho phép khai báo kín tập cài đặt. Một
  `switch` trên `QueryNode` (nếu có nơi nào cần phân biệt loại nút) sẽ được
  trình biên dịch kiểm tra **đầy đủ nhánh**: thêm một loại nút thứ bảy mà quên
  xử lý ở một `switch` nào đó sẽ là lỗi biên dịch, không phải lỗi runtime im
  lặng.
- ★ **Bất biến bắt buộc:** mọi cài đặt `evaluate()` phải trả về danh sách
  docId **sắp xếp tăng dần**. Đây không phải quy ước phong cách — nó là điều
  kiện để các nút cha (`AndNode`, `OrNode`, `NotNode.evaluateAgainst`) ghép
  kết quả con lại bằng thuật toán hai-con-trỏ `O(m+n)` thay vì phải `sort` lại
  `O(n log n)` ở mỗi tầng của cây. Bất biến này bắt nguồn từ `SearchIndex`
  (Javadoc của chính `getPostings`): posting list gốc đã sắp tăng dần theo
  docId, và mọi phép hợp/giao two-pointer đều **bảo toàn** thứ tự đó ở đầu ra.
- **`estimatedSize()` tách biệt hoàn toàn khỏi `evaluate()`** — ước lượng
  không được phép tốn kém hơn việc thực sự đánh giá, nếu không mục đích "sắp
  xếp trước khi làm việc nặng" sẽ tự triệt tiêu lợi ích của chính nó. Với
  `TermNode`, ước lượng là `getDocumentFrequency` — `O(1)` — trong khi
  `evaluate()` phải lấy hẳn posting list `O(df)`.

### 15.1 Sơ đồ cây tổng quát

```mermaid
classDiagram
    class QueryNode {
        <<sealed interface>>
        +evaluate(SearchIndex) List~Integer~
        +estimatedSize(SearchIndex) int
        +describe() String
    }
    class TermNode {
        +String term
    }
    class PhraseNode {
        +List~String~ terms
    }
    class AndNode {
        +List~QueryNode~ children
    }
    class OrNode {
        +List~QueryNode~ children
    }
    class NotNode {
        +QueryNode inner
        +evaluateAgainst(List~Integer~, SearchIndex) List~Integer~
    }
    QueryNode <|.. TermNode
    QueryNode <|.. PhraseNode
    QueryNode <|.. AndNode
    QueryNode <|.. OrNode
    QueryNode <|.. NotNode
    AndNode o-- QueryNode : children
    OrNode o-- QueryNode : children
    NotNode o-- QueryNode : inner
```

Ví dụ cây thật cho `"tơi bời" trump -mỹ` (đối chiếu mục 14.2):

```
AndNode
 ├── TermNode(trump)
 ├── PhraseNode([tơi_bời])
 └── NotNode(TermNode(mỹ))
```

---

## 16. `TermNode` — nút lá

**File:** `query/ast/TermNode.java`

```java
public record TermNode(String term) implements QueryNode {
    @Override
    public List<Integer> evaluate(SearchIndex index) {
        return PostingListMerger.docIdsOf(index.getPostings(term));
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        return index.getDocumentFrequency(term); // df chính là số kết quả, O(1)
    }

    @Override
    public String describe() {
        return term;
    }
}
```

Nút đơn giản nhất trong cả cây: `evaluate` chỉ lấy thẳng posting list của
`term` từ chỉ mục rồi rút ra danh sách docId — không có logic gì thêm.
`estimatedSize` **chính xác tuyệt đối**, không phải ước lượng gần đúng: với
term đơn, document frequency **là** số kết quả thật, nên sắp xếp shortest-first
dựa trên `TermNode.estimatedSize` không bao giờ sai thứ tự.

---

## 17. `PhraseNode` — nút cụm từ

**File:** `query/ast/PhraseNode.java`

```java
public record PhraseNode(List<String> terms) implements QueryNode {
    @Override
    public List<Integer> evaluate(SearchIndex index) {
        if (terms.isEmpty()) return List.of();
        List<QueryNode> asTerms = new ArrayList<>(terms.size());
        for (String term : terms) asTerms.add(new TermNode(term));
        List<Integer> rough = new AndNode(asTerms).evaluate(index); // lọc THÔ

        List<Integer> exact = new ArrayList<>(rough.size());
        for (int docId : rough) {
            if (PostingListMerger.matchesPhrase(index, terms, docId)) { // lọc CHÍNH XÁC
                exact.add(docId);
            }
        }
        return exact;
    }

    @Override
    public int estimatedSize(SearchIndex index) {
        int min = Integer.MAX_VALUE;
        for (String term : terms) min = Math.min(min, index.getDocumentFrequency(term));
        return min == Integer.MAX_VALUE ? 0 : min;
    }
}
```

★ **Filter-and-refine — hai tầng lọc với chi phí rất khác nhau.** Javadoc của
lớp giải thích rõ lý do có hai bước thay vì một:

> Điều kiện "liên tiếp" KÉO THEO điều kiện "cùng có mặt", nên ta dùng điều kiện
> yếu hơn nhưng RẺ hơn (giao posting list) để thu hẹp tập trước, rồi mới kiểm
> tra "liên tiếp" (đắt, phải tìm kiếm nhị phân trên danh sách vị trí) trên tập
> nhỏ còn lại.

Cụ thể: `evaluate()` **tái sử dụng `AndNode`** để tạo bước lọc thô — dựng một
`AndNode` tạm gồm mỗi tiếng của cụm bọc trong `TermNode`, rồi gọi
`evaluate()` của nó. Điều này có nghĩa `PhraseNode` được lợi **miễn phí** từ
mọi tối ưu shortest-first mà `AndNode` đã có (mục 20) — không phải cài đặt lại
logic giao posting list riêng cho trường hợp cụm từ.

⚠ Nếu bỏ bước lọc thô này và chạy thẳng `matchesPhrase` trên **toàn bộ**
corpus, Javadoc ước tính chậm hơn khoảng **100 lần** trên corpus tham chiếu
5.011 tài liệu (so với chạy trên vài chục ứng viên đã qua giao posting list).

`estimatedSize` lấy **`min`** document frequency của các tiếng trong cụm — một
chặn trên hợp lý: một cụm không thể khớp nhiều tài liệu hơn tiếng hiếm nhất
trong nó xuất hiện.

---

## 18. `AndNode` — nút giao

**File:** `query/ast/AndNode.java`

```java
@Override
public List<Integer> evaluate(SearchIndex index) {
    if (children.isEmpty()) return List.of();

    List<QueryNode> positives = new ArrayList<>(children.size());
    List<NotNode> negatives = new ArrayList<>();
    for (QueryNode child : children) {
        if (child instanceof NotNode not) negatives.add(not);
        else positives.add(child);
    }
    if (positives.isEmpty()) {
        throw new UnsupportedOperationException(
                "AND chi gom cac menh de NOT thi khong danh gia duoc; "
                        + "can it nhat mot menh de khang dinh.");
    }

    positives.sort(Comparator.comparingInt(node -> node.estimatedSize(index))); // shortest-first

    List<Integer> accumulator = positives.get(0).evaluate(index);
    for (int i = 1; i < positives.size(); i++) {
        if (accumulator.isEmpty()) return List.of(); // rỗng là phần tử HẤP THỤ
        accumulator = PostingListMerger.intersect(accumulator, positives.get(i).evaluate(index));
    }

    for (NotNode negative : negatives) {
        if (accumulator.isEmpty()) break;
        accumulator = negative.evaluateAgainst(accumulator, index);
    }
    return accumulator;
}
```

### 18.1 Ba việc `evaluate()` làm theo đúng thứ tự

```mermaid
flowchart TD
    A["children"] --> B["Tách: positives (mọi thứ không phải NotNode)<br/>negatives (mọi NotNode)"]
    B --> C{"positives rỗng?"}
    C -->|"có"| D(["UnsupportedOperationException<br/>— toàn NOT, không có gì để đánh giá"])
    C -->|"không"| E["sort positives theo estimatedSize<br/>SHORTEST-FIRST"]
    E --> F["accumulator = con NHỎ NHẤT .evaluate()"]
    F --> G["với từng con còn lại:<br/>accumulator = intersect(accumulator, con.evaluate())<br/>rỗng thì DỪNG NGAY"]
    G --> H["với từng NotNode:<br/>accumulator = not.evaluateAgainst(accumulator, index)"]
    H --> I(["accumulator cuối cùng"])

    style D fill:#b3261e,color:#fff
    style E fill:#0b7a3b,color:#fff
```

<details><summary>Xem bản chữ (ASCII)</summary>

```
children
  -> tach: positives (khong phai NotNode) / negatives (NotNode)
  -> positives rong? -> UnsupportedOperationException
  -> sort positives theo estimatedSize TANG DAN (shortest-first)
  -> accumulator = con NHO NHAT .evaluate()
  -> voi tung con con lai:
       rong -> dung ngay, tra ve List.of()
       khong -> accumulator = intersect(accumulator, con.evaluate())
  -> voi tung NotNode: accumulator = not.evaluateAgainst(accumulator, index)
  -> tra ve accumulator
```
</details>

### 18.2 ★ Vì sao shortest-first, bằng con số cụ thể

Cơ sở toán học: `|A ∩ B| <= min(|A|, |B|)` — giao không bao giờ lớn hơn tập
nhỏ hơn. Nếu bắt đầu từ con có **ít** kết quả nhất, `accumulator` nhỏ ngay từ
bước đầu, và mọi bước giao sau đó tốn `O(|accumulator hiện tại| + |con kế
tiếp|)` — rẻ hơn hẳn so với việc lỡ bắt đầu từ con có nhiều kết quả nhất trước.

Với dữ liệu thật của [mục 34](#34-truy-vấn-1-trump-iran--and-hai-term-phổ-biến)
(`trump` có `df=9`, `iran` có `df=4` trên corpus 40 tài liệu): `AndNode` sắp
`iran` (nhỏ hơn) lên trước, `accumulator` khởi đầu chỉ 4 phần tử thay vì 9.
Trên corpus nhỏ khác biệt không đáng kể, nhưng cơ chế giữ nguyên khi một trong
hai term có `df` chênh lệch hàng nghìn lần trên corpus lớn — đúng chỗ tối ưu
phát huy tác dụng rõ nhất.

### 18.3 Xử lý `NotNode` — tách riêng, áp sau cùng

`AndNode` **không coi `NotNode` như một `positive` bình thường** — nó lọc
riêng ra thành danh sách `negatives`, không đưa vào bước sort/intersect. Lý do
nằm trọn ở [mục 20](#20-notnode--nút-loại-trừ-và-vì-sao-nó-không-đứng-một-mình-được): `NotNode`
không đánh giá độc lập được, nên nó chỉ có thể được áp dụng **sau khi** đã có
một `accumulator` khẳng định làm nền — đúng thứ tự "trừ trên một tập ứng viên
có sẵn" mà Javadoc của `NotNode` yêu cầu.

```java
@Override
public int estimatedSize(SearchIndex index) {
    int min = Integer.MAX_VALUE;
    for (QueryNode child : children) {
        if (child instanceof NotNode) continue; // NOT không thu hẹp ước lượng
        min = Math.min(min, child.estimatedSize(index));
    }
    return min == Integer.MAX_VALUE ? 0 : min;
}
```

`estimatedSize` của chính `AndNode` cũng bỏ qua `NotNode` cùng lý do: một
`NotNode` đứng riêng chỉ có thể LÀM GIẢM kết quả, không bao giờ làm tăng, nên
nó không đóng góp thông tin gì cho việc ước lượng chặn trên.

---

## 19. `OrNode` — nút hợp

**File:** `query/ast/OrNode.java`

```java
@Override
public List<Integer> evaluate(SearchIndex index) {
    List<Integer> accumulator = List.of();
    for (QueryNode child : children) {
        accumulator = PostingListMerger.union(accumulator, child.evaluate(index));
    }
    return accumulator;
}

@Override
public int estimatedSize(SearchIndex index) {
    long sum = 0;
    for (QueryNode child : children) sum += child.estimatedSize(index);
    return (int) Math.min(sum, Integer.MAX_VALUE);
}
```

★ Javadoc của lớp này kể một chi tiết lịch sử quan trọng: `PostingListMerger.union`
**đã tồn tại và đã có test** từ trước khi cây AST ra đời, nhưng **không có
đường nào gọi tới nó** từ tầng truy vấn — ngôn ngữ truy vấn cũ không hỗ trợ
`OR`. `OrNode` là nơi đầu tiên hàm này thực sự được dùng trong đường chạy sản
phẩm, không chỉ trong test.

`estimatedSize` là **chặn trên**, không phải số chính xác: `sum` cộng dồn kích
thước từng con — nếu các con có tài liệu trùng nhau, kết quả hợp thực tế nhỏ
hơn tổng. Nhưng với mục đích duy nhất của `estimatedSize` — sắp xếp
shortest-first ở `AndNode` cha — một chặn trên là đủ chính xác, không cần
`OrNode` tự đánh giá thật để biết số chính xác.

---

## 20. `NotNode` — nút loại trừ và vì sao nó không đứng một mình được

**File:** `query/ast/NotNode.java`

```java
@Override
public List<Integer> evaluate(SearchIndex index) {
    throw new UnsupportedOperationException(
            "NOT chi hop le trong ngu canh AND (vi du 'A AND NOT B'); "
                    + "phu dinh doc lap se tra ve gan nhu toan bo corpus.");
}

public List<Integer> evaluateAgainst(List<Integer> candidates, SearchIndex index) {
    List<Integer> excluded = inner.evaluate(index);
    if (excluded.isEmpty()) return candidates;
    List<Integer> result = new ArrayList<>(candidates.size());
    int j = 0;
    for (int candidate : candidates) {
        while (j < excluded.size() && excluded.get(j) < candidate) j++;
        if (j >= excluded.size() || excluded.get(j) != candidate) result.add(candidate);
    }
    return result;
}

@Override
public int estimatedSize(SearchIndex index) {
    return index.getTotalDocs(); // chặn trên thô
}
```

### 20.1 ★★ Vì sao `evaluate()` tự ném ngoại lệ thay vì cố trả về gì đó

Đây là quyết định thiết kế quan trọng nhất của cả gói `query.ast`. Javadoc của
lớp giải thích lý do bằng chính con số của corpus tham chiếu:

> Phủ định thuần túy cho ra *tập bù*: với truy vấn `NOT quảng_cáo` trên corpus
> 5.011 tài liệu, kết quả là gần 5.000 tài liệu — vừa vô nghĩa với người dùng,
> vừa đắt (phải liệt kê toàn bộ corpus rồi trừ đi). Mọi hệ thống tìm kiếm thực
> tế đều yêu cầu phủ định đi kèm một mệnh đề khẳng định: `A AND NOT B`, không
> phải `NOT B` đơn độc.

Vì vậy lớp này cố tình **không cài `evaluate()` theo hợp đồng thông thường** —
nó ném `UnsupportedOperationException` có thông điệp rõ ràng, buộc bất kỳ ai
gọi sai (kể cả chính tác giả sau này quên mất quy tắc) nhận một lỗi ồn ào ngay
lập tức, thay vì âm thầm tính ra một tập gần bằng cả corpus rồi để nó trôi mãi
xuống các tầng sau — nơi lỗi loại đó cực khó lần ngược nguyên nhân vì không có
exception nào cả, chỉ có kết quả "hơi lạ" nhưng không sai cú pháp.

`evaluateAgainst(candidates, index)` mới là con đường đúng — luôn được gọi bởi
`AndNode` (mục 18.3), không bao giờ được gọi trực tiếp từ `CandidateResolver`
hay bất kỳ đâu khác trong mã sản phẩm.

### 20.2 Two-pointer trừ tập — vì sao `O(m+n)` chứ không `O(m·n)`

```mermaid
flowchart LR
    C["candidates (sắp tăng dần)"] --> LOOP["với mỗi candidate:<br/>tiến con trỏ j trong excluded<br/>tới khi excluded[j] >= candidate"]
    E["excluded (sắp tăng dần)"] --> LOOP
    LOOP --> CHECK{"excluded[j] == candidate?"}
    CHECK -->|"có"| SKIP(["bỏ candidate này"])
    CHECK -->|"không"| KEEP(["giữ candidate này"])
```

Điểm mấu chốt: con trỏ `j` **chỉ tiến, không bao giờ lùi**, vì cả `candidates`
và `excluded` đều sắp tăng dần (bất biến từ `QueryNode.evaluate`, mục 15) —
đúng cấu trúc thuật toán hai-con-trỏ đã thấy ở `intersect`/`union` (mục 26).
Tổng số bước tiến của `j` trong suốt toàn bộ vòng lặp bị chặn bởi
`excluded.size()`, nên tổng chi phí là `O(|candidates| + |excluded|)`, không
phải `O(|candidates| * |excluded|)` như cách kiểm tra "có nằm trong danh sách
loại trừ không" bằng `List.contains` bên trong vòng lặp sẽ tốn.

⚠ `estimatedSize()` trả `index.getTotalDocs()` — chặn trên **tệ nhất có thể**,
không giúp gì cho việc sắp xếp shortest-first (đây chính xác là lý do
`AndNode.estimatedSize` phải bỏ qua `NotNode` khi tính `min`, mục 18.3: nếu
không bỏ qua, một `AndNode` có cả `NotNode` sẽ luôn ước lượng bằng
`getTotalDocs()`, vô hiệu hoá hoàn toàn việc sắp xếp).

---

## 21. `estimatedSize()` — chi phí ước lượng của từng loại nút

| Loại nút | `estimatedSize()` trả về | Độ phức tạp | Chính xác hay chặn trên? |
|---|---|---|---|
| `TermNode` | `index.getDocumentFrequency(term)` | `O(1)` | **Chính xác tuyệt đối** |
| `PhraseNode` | `min` document frequency của các tiếng | `O(k)`, k = số tiếng trong cụm | Chặn trên (cụm không thể khớp nhiều hơn tiếng hiếm nhất) |
| `AndNode` | `min` của các con `positive` (bỏ qua `NotNode`) | `O(k)` đệ quy | Chặn trên (giao luôn ≤ min các tập con) |
| `OrNode` | tổng `estimatedSize` của mọi con | `O(k)` đệ quy | Chặn trên (hợp có thể trùng lặp, nên tổng thực tế thường CAO hơn kết quả thật) |
| `NotNode` | `index.getTotalDocs()` | `O(1)` | Chặn trên **tệ nhất có thể** — không mang thông tin phân biệt |

Bảng này giải thích trực tiếp vì sao `AndNode.evaluate()` luôn dồn `NotNode`
xuống cuối và loại nó khỏi phép sort shortest-first: một ước lượng bằng
`getTotalDocs()` sẽ luôn bị coi là "lớn nhất", nên tự nhiên bị xếp cuối ngay
cả khi không có logic loại trừ tường minh nào — nhưng logic đó vẫn tồn tại
tường minh (tách `positives`/`negatives`) để không phụ thuộc vào việc `sort`
tình cờ xếp đúng chỗ.

---
---

# PHẦN IV — TRUY HỒI ỨNG VIÊN: `CandidateResolver`

**File:** `query/CandidateResolver.java` (lớp `final`, không thể kế thừa, mọi
phương thức `static`)

---

## 22. `resolve()` — tổng quan hai giai đoạn

```java
public static ResolvedQuery resolve(SearchIndex index, QueryParser.ParsedQuery parsed) {
    Map<String, Integer> queryTermFrequency = buildQueryTermFrequency(parsed);

    // --- Giai đoạn 1: truy hồi boolean bằng cây biểu thức (Composite) ---
    QueryNode ast = AST_BUILDER.buildAst(parsed);
    if (ast == null) {
        return new ResolvedQuery(List.of(), queryTermFrequency);
    }

    List<Integer> candidates = applyFilters(ast.evaluate(index), index, parsed);
    if (!candidates.isEmpty()) {
        return new ResolvedQuery(candidates, queryTermFrequency);
    }

    // --- Giai đoạn 2: không có gì khớp -> nới lỏng truy vấn ---
    return relaxAndRetry(index, parsed, queryTermFrequency);
}
```

### 22.1 Vì sao lớp này tồn tại tách biệt khỏi `SearchEngineFacade`

Javadoc của lớp kể lại nguyên nhân — logic này từng nằm trong
`SearchEngineFacade` dưới dạng một phương thức `private`:

> Bộ đánh giá chất lượng không gọi lại được và buộc phải viết lại một bản sao.
> Hai bản sao chắc chắn sẽ trôi lệch nhau theo thời gian, và khi đó mọi con số
> trong báo cáo đánh giá đều mất giá trị vì chúng đo một đường đi KHÁC với
> đường đi mà hệ thống thực sự phục vụ người dùng.

Nói cách khác: tách `CandidateResolver` thành lớp `public static` riêng không
chỉ là refactor cho gọn — nó là điều kiện để **"cái được ĐO bằng cái được
PHỤC VỤ"**, một bất biến quan trọng cho bất kỳ hệ thống nào có script đánh giá
chất lượng chạy song song với đường chạy sản phẩm.

### 22.2 ★ "Lùi dần về AND-của-tập-con" — vấn đề gốc mà cả lớp giải quyết

AND ngầm định giữa các term đúng về mặt ngữ nghĩa cho truy vấn ngắn, nhưng với
truy vấn dài nó biến một kết quả tốt thành **không có kết quả nào**: chỉ cần
một tiếng vắng mặt khỏi corpus là giao của mọi posting list bằng rỗng. Ví dụ
Javadoc đưa ra: truy vấn `"máy tính xách tay giá rẻ cho sinh viên"` hầu như
chắc chắn trả về rỗng dù corpus có đầy đủ tài liệu về máy tính xách tay — chỉ
cần một tiếng như `sinh_viên` hiếm hoặc vắng mặt là đủ để giao rỗng lan
truyền qua toàn bộ cây `AndNode`.

`CandidateResolver` giải quyết vấn đề này bằng **hai giai đoạn tuần tự**, chỉ
chạy giai đoạn 2 khi giai đoạn 1 cho ra rỗng — không đánh đổi độ chính xác của
trường hợp phổ biến (khớp đầy đủ) để đổi lấy khả năng chịu lỗi của trường hợp
hiếm (không khớp gì).

```mermaid
flowchart TD
    START(["resolve(index, parsed)"]) --> QTF["buildQueryTermFrequency(parsed)"]
    QTF --> AST["buildAst(parsed)"]
    AST --> NULLCHECK{"ast == null?"}
    NULLCHECK -->|"có"| EMPTY1(["ResolvedQuery([], qtf)<br/>KHÔNG gọi evaluate"])
    NULLCHECK -->|"không"| EVAL["ast.evaluate(index)"]
    EVAL --> FILTER["applyFilters(candidates, index, parsed)"]
    FILTER --> CHECK{"candidates rỗng?"}
    CHECK -->|"không"| RETURN1(["ResolvedQuery(candidates, qtf)<br/>droppedTerms = [] (đầy đủ)"])
    CHECK -->|"có"| RELAX["relaxAndRetry(index, parsed, qtf)<br/>GIAI ĐOẠN 2"]
    RELAX --> RETURN2(["ResolvedQuery(…, droppedTerms có thể khác rỗng)"])

    style EMPTY1 fill:#c9720b,color:#fff
    style RETURN1 fill:#0b7a3b,color:#fff
    style RELAX fill:#6b21a8,color:#fff
```

---

## 23. `buildQueryTermFrequency`

```java
private static Map<String, Integer> buildQueryTermFrequency(QueryParser.ParsedQuery parsed) {
    Map<String, Integer> frequency = new HashMap<>();
    for (String term : parsed.mustTerms()) frequency.merge(term, 1, Integer::sum);
    for (List<String> phrase : parsed.phrases())
        for (String term : phrase) frequency.merge(term, 1, Integer::sum);
    for (List<String> group : parsed.orGroups())
        for (String term : group) frequency.merge(term, 1, Integer::sum);
    return frequency;
}
```

Ba vòng lặp gom **cả** term của `mustTerms`, **cả** term nằm trong mỗi cụm của
`phrases`, **cả** term nằm trong mỗi nhóm của `orGroups` vào cùng một bảng tần
suất — Javadoc cảnh báo rõ: *"nếu bỏ sót, trọng số truy vấn sẽ sai và scorer
chấm điểm lệch"*. `excludedTerms` **cố ý không có mặt** trong hàm này — một
term bị loại trừ không đóng góp vào vector truy vấn dùng để chấm điểm liên
quan, vì về mặt ngữ nghĩa tìm kiếm, người dùng đang nói "tôi không muốn cái
này", không phải "cái này quan trọng với tôi".

★ **Luôn tính từ `parsed` GỐC, được gọi Ở ĐẦU `resolve()`** — trước khi biết
liệu giai đoạn 2 (nới lỏng) có chạy hay không. Đây là điểm bất biến cốt lõi
mà [mục 28](#28-giai-đoạn-2--nới-lỏng-truy-vấn-khi-rỗng) phụ thuộc vào: dù
truy vấn có bị nới lỏng (bỏ bớt term khỏi tập truy hồi) hay không, việc CHẤM
ĐIỂM vẫn luôn dùng tần suất của truy vấn người dùng thực sự gõ — tài liệu khớp
nhiều term hơn trong truy vấn gốc vẫn được xếp trên, kể cả khi một vài term đó
đã bị hệ thống âm thầm bỏ khỏi bước truy hồi.

---

## 24. GIAI ĐOẠN 1 — đánh giá cây AST

Thân của giai đoạn 1 chỉ có ba dòng, nhưng mỗi dòng gánh một lớp trách nhiệm
khác nhau:

```java
QueryNode ast = AST_BUILDER.buildAst(parsed);
if (ast == null) {
    return new ResolvedQuery(List.of(), queryTermFrequency);
}
List<Integer> candidates = applyFilters(ast.evaluate(index), index, parsed);
```

| Dòng | Trách nhiệm | Nếu bỏ qua |
|---|---|---|
| `buildAst(parsed)` | Composite — dựng cấu trúc từ dữ liệu phẳng | Không thể biểu diễn `OR`/`NOT` lồng nhau (xem Javadoc `QueryNode`, mục 15) |
| `ast == null` | Thoát sớm cho truy vấn không có mệnh đề khẳng định | Sẽ phải xử lý `null` ở tận `AndNode`, hoặc gọi nhầm `evaluate()` trên cây rỗng |
| `ast.evaluate(index)` | Truy hồi boolean thuần tuý trên posting list | — |
| `applyFilters(…)` | Chain of Responsibility — ràng buộc sau truy hồi | `site:` và trần số ứng viên sẽ không bao giờ được áp dụng |

`AST_BUILDER` là một `QueryParser` **`static final`** riêng của
`CandidateResolver` (dòng 61 trong mã), tách khỏi thực thể `QueryParser` mà
`SearchEngineFacade` giữ — vì `buildAst` không phụ thuộc tokenizer (nó chỉ
đọc `ParsedQuery` đã tokenize sẵn), một thực thể mặc định là đủ, không cần
tiêm `Tokenizer` từ ngoài vào.

---

## 25. `PostingListMerger` — trái tim thuật toán của tầng truy vấn

**File:** `query/PostingListMerger.java`

### 25.1 `intersect` / `union` — two-pointer `O(m+n)`

```java
public static List<Integer> intersect(List<Integer> a, List<Integer> b) {
    List<Integer> result = new ArrayList<>();
    int i = 0, j = 0;
    while (i < a.size() && j < b.size()) {
        int docA = a.get(i), docB = b.get(j);
        if (docA == docB) { result.add(docA); i++; j++; }
        else if (docA < docB) i++; // b tăng dần -> A[i] không thể còn trong phần sau của b
        else j++;
    }
    return result;
}
```

`union` cùng cấu trúc, chỉ khác: khi hai con trỏ lệch nhau, phần tử nhỏ hơn
được **thêm vào** kết quả (thay vì bỏ qua) rồi mới tiến con trỏ; sau vòng lặp
chính, phần đuôi còn lại của bên dài hơn được nối thẳng vào — vì phần đuôi đó
chắc chắn không trùng với bên kia (bên kia đã duyệt hết).

★ **Vì sao không dùng `HashSet.retainAll`** — Javadoc đưa số đo thật trên hai
danh sách 500.000 phần tử:

| Cách làm | Thời gian |
|---|---|
| two-pointer | ~10,0 ms |
| `HashSet.retainAll` (không tính dựng set) | ~15,5 ms (+55%) |
| `HashSet.retainAll` (tính cả dựng 2 set) | ~27,0 ms (2,7 lần) |

Ba lý do: two-pointer không tốn chi phí dựng cấu trúc trung gian (posting list
lấy thẳng từ chỉ mục), có cục bộ cache tốt hơn (duyệt tuần tự thay vì nhảy
ngẫu nhiên trong bảng băm), và không có hằng số ẩn của việc băm mỗi phần tử.

### 25.2 `intersectCursors` — galloping search, không cấp phát trung gian

```java
public static List<Integer> intersectCursors(PostingCursor a, PostingCursor b) {
    List<Integer> result = new ArrayList<>();
    while (a.docId() != PostingCursor.NO_MORE && b.docId() != PostingCursor.NO_MORE) {
        int docA = a.docId(), docB = b.docId();
        if (docA == docB) { result.add(docA); a.next(); b.next(); }
        else if (docA < docB) a.skipTo(docB); // nhảy cóc, không next() từng bước
        else b.skipTo(docA);
    }
    return result;
}
```

Khi giao một danh sách **rất ngắn** với một danh sách **rất dài**, two-pointer
thuần phải bước từng bước gần hết danh sách dài. `PostingCursor.skipTo` nhảy
thẳng tới vị trí cần tìm (dùng binary/exponential search bên trong cursor):

```
two-pointer thuần : O(m + n)        = 5 + 4000 = 4005 bước
galloping         : O(m log(n/m))   ~= 48 bước
```

`intersectAll` (dùng khi giao nhiều posting list gốc cùng lúc — trường hợp
AND nhiều term đơn) sắp các danh sách theo độ dài **tăng dần** trước, dùng
`intersectCursors` cho cặp đầu tiên (nơi chênh lệch kích thước rõ nhất), rồi
giao tiếp bằng con trỏ với từng danh sách còn lại — dừng ngay khi kết quả
trung gian rỗng, vì rỗng là **phần tử hấp thụ** của phép giao.

---

## 26. `matchesPhrase` — khớp cụm từ theo vị trí

```java
public static boolean matchesPhrase(SearchIndex index, List<String> phraseTerms, int docId) {
    if (phraseTerms.isEmpty()) return true;
    int[][] positionsByTerm = new int[phraseTerms.size()][];
    for (int i = 0; i < phraseTerms.size(); i++) {
        int[] positions = index.getPositions(phraseTerms.get(i), docId);
        if (positions.length == 0) return false; // một term không xuất hiện
        positionsByTerm[i] = positions;
    }

    for (int start : positionsByTerm[0]) {
        boolean allMatch = true;
        for (int i = 1; i < phraseTerms.size(); i++) {
            if (Arrays.binarySearch(positionsByTerm[i], start + i) < 0) {
                allMatch = false;
                break;
            }
        }
        if (allMatch) return true;
    }
    return false;
}
```

### 26.1 Hai tối ưu, đối chiếu với "bản cũ" mà Javadoc mô tả

| Tối ưu | Bản cũ | Bản hiện tại | Vì sao nhanh hơn |
|---|---|---|---|
| Lấy vị trí | gọi `index.getPositions(term_i, docId)` **bên trong** vòng lặp qua từng vị trí của term đầu | lấy **một lần** cho mỗi term, **ngoài** vòng lặp (`positionsByTerm`) | Với term đầu xuất hiện 20 lần và cụm 3 từ: 40 lần tìm kiếm thay vì 2 |
| Tìm vị trí kế tiếp | `List.contains` — quét tuyến tính `O(p)` | `Arrays.binarySearch` trên `int[]` đã sắp tăng dần | `O(log p)`, và chạy thẳng trên mảng nguyên thuỷ nên không phải mở hộp `Integer` ở vòng nóng nhất |

### 26.2 Thuật toán: "mọi vị trí bắt đầu có thể" của tiếng đầu tiên

```mermaid
flowchart TD
    A["lấy positions của MỖI tiếng trong cụm, MỘT lần"] --> B{"tiếng nào có<br/>positions rỗng?"}
    B -->|"có"| C(["return false ngay"])
    B -->|"không"| D["với mỗi vị trí start<br/>trong positions của tiếng ĐẦU"]
    D --> E["kiểm tra: positions của tiếng thứ i<br/>có chứa (start + i) không?<br/>(binary search)"]
    E --> F{"cả cụm khớp<br/>liên tiếp từ start?"}
    F -->|"có"| G(["return true"])
    F -->|"không"| D
    D -->|"hết mọi start"| H(["return false"])
```

Điều kiện `start + i` chính là điều kiện "liên tiếp": nếu tiếng đầu ở vị trí
`start`, tiếng thứ hai của cụm phải ở đúng vị trí `start + 1`, tiếng thứ ba ở
`start + 2`, v.v. — không có khoảng trống, không đảo thứ tự.

---

## 27. Bộ lọc ứng viên: `CandidateFilter`, `DomainFilter`, `MaxCandidatesFilter`

**File:** `query/CandidateResolver.java` (phương thức `applyFilters`) +
`query/filter/*.java`

```java
private static final List<CandidateFilter> FILTERS = List.of(
        new DomainFilter(),
        new MaxCandidatesFilter());

private static List<Integer> applyFilters(List<Integer> candidates, SearchIndex index,
                                           QueryParser.ParsedQuery parsed) {
    CandidateFilter.FilterContext context = new CandidateFilter.FilterContext(index, parsed);
    for (CandidateFilter filter : FILTERS) {
        if (candidates.isEmpty()) break; // rỗng là phần tử HẤP THỤ của mọi phép lọc
        if (!filter.isApplicable(context)) continue;
        candidates = filter.apply(candidates, context);
    }
    return candidates;
}
```

### 27.1 Vì sao đây là Chain of Responsibility, không phải hàm nội bộ

Javadoc của `CandidateFilter` mô tả "bản cũ" — ba tầng lọc từng nằm chọn cứng
trong thân hàm `resolve` dài 104 dòng, không thể test riêng từng tầng, không
đo được "tầng nào loại bao nhiêu ứng viên, tốn bao nhiêu ms". Bản hiện tại:
mỗi tầng là một lớp riêng cài `CandidateFilter`, thêm bộ lọc mới chỉ cần thêm
một dòng vào danh sách `FILTERS` — không sửa `applyFilters`, tuân thủ nguyên
tắc Mở/Đóng.

### 27.2 ★ Thứ tự "rẻ và loại nhiều trước"

```
1. Giao posting list (trong ast.evaluate, TRƯỚC applyFilters)   5011 -> ~50
2. DomainFilter (site:)                                          ~50 -> ~20
3. MaxCandidatesFilter (trần 10.000)                              hiếm khi kích hoạt
```

`DomainFilter.isApplicable` chỉ trả `true` khi `parsed.siteFilter() != null`
— truy vấn không có `site:` bỏ qua hẳn tầng này, không lãng phí một vòng lặp
rỗng. `DomainFilter` khớp theo **hậu tố**: `site:vnexpress.net` bắt được cả
chính `vnexpress.net` lẫn phụ miền `sport.vnexpress.net`.

```java
// DomainFilter.apply — rút gọn
String host = hostOf(doc.getUrl());
if (host != null && (host.equals(wanted) || host.endsWith("." + wanted))) {
    filtered.add(docId);
}
```

★ Javadoc của `DomainFilter` giải thích lý do nó là một **filter**, không phải
một `QueryNode`: cây biểu thức mô hình hoá quan hệ boolean giữa các **term**,
làm việc trên posting list; `site:` không phải một term — nó là ràng buộc trên
**siêu dữ liệu** của tài liệu (URL), không có posting list tương ứng. Đưa nó
vào cây sẽ buộc phải dựng thêm một chỉ mục phụ `host -> docIds`; với vài chục
ứng viên đã qua bước giao posting list, kiểm tra trực tiếp URL của từng ứng
viên đơn giản và đủ nhanh.

### 27.3 `MaxCandidatesFilter` — chặn trên đơn giản, không phải WAND/MaxScore

```java
public static final int DEFAULT_MAX_CANDIDATES = 10_000;

public List<Integer> apply(List<Integer> candidates, FilterContext context) {
    if (candidates.size() <= maxCandidates) return candidates; // không cấp phát
    return List.copyOf(candidates.subList(0, maxCandidates));
}
```

⚠ Javadoc tự thừa nhận đây **không phải** cách chuẩn của ngành (WAND hoặc
MaxScore — ước lượng chặn trên điểm số của từng tài liệu để bỏ qua sớm những
tài liệu không thể lọt top-K). Vì posting list sắp theo **docId**, không phải
theo điểm, phép cắt `subList(0, maxCandidates)` **không bảo toàn top-K một
cách chính xác** — đây là một đánh đổi có ý thức, chỉ kích hoạt ở ngưỡng rất
cao (mặc định `10.000`) nên không ảnh hưởng truy vấn thông thường. Javadoc nói
rõ: bộ lọc này bảo vệ hệ thống khỏi truy vấn bất thường, không phải một tối ưu
xếp hạng.

---

## 28. GIAI ĐOẠN 2 — nới lỏng truy vấn khi rỗng

**File:** `query/CandidateResolver.java`, phương thức `relaxAndRetry`

```java
private static ResolvedQuery relaxAndRetry(SearchIndex index, QueryParser.ParsedQuery parsed,
                                            Map<String, Integer> queryTermFrequency) {
    List<String> mustTerms = parsed.mustTerms();
    if (mustTerms.isEmpty()) {
        return new ResolvedQuery(List.of(), queryTermFrequency);
    }
    if (isUnmatchable(index, parsed)) {
        return new ResolvedQuery(List.of(), queryTermFrequency);
    }

    List<String> remaining = new ArrayList<>(mustTerms);
    List<String> dropped = new ArrayList<>();

    // Bước 1: bỏ TẤT CẢ term có df = 0, một lần.
    remaining.removeIf(term -> {
        if (index.getDocumentFrequency(term) == 0) { dropped.add(term); return true; }
        return false;
    });
    if (!dropped.isEmpty()) {
        ResolvedQuery attempt = attempt(index, parsed, queryTermFrequency, remaining, dropped);
        if (attempt != null) return attempt;
    }

    // Bước 2: bỏ tiếp từng term một, phổ biến nhất trước (IDF tăng dần).
    remaining.sort(Comparator.comparingInt(index::getDocumentFrequency).reversed());
    while (remaining.size() > 1) {
        dropped.add(remaining.remove(0));
        ResolvedQuery attempt = attempt(index, parsed, queryTermFrequency, remaining, dropped);
        if (attempt != null) return attempt;
    }
    return new ResolvedQuery(List.of(), queryTermFrequency);
}
```

### 28.1 ★ `isUnmatchable` — thoát sớm khỏi nỗ lực vô ích

```java
private static boolean isUnmatchable(SearchIndex index, QueryParser.ParsedQuery parsed) {
    for (List<String> phrase : parsed.phrases())
        for (String term : phrase)
            if (index.getDocumentFrequency(term) == 0) return true;
    for (List<String> group : parsed.orGroups()) {
        boolean anyExists = false;
        for (String alternative : group)
            if (index.getDocumentFrequency(alternative) > 0) { anyExists = true; break; }
        if (!anyExists) return true;
    }
    return false;
}
```

Một cụm từ chỉ khớp khi **mọi** tiếng của nó tồn tại; một nhóm OR chỉ khớp khi
**ít nhất một** vế tồn tại. Vi phạm một trong hai điều đó là kết quả rỗng
**vĩnh viễn** — không phép bỏ bớt `mustTerm` nào cứu được, vì `relaxAndRetry`
**chỉ** có khả năng bỏ bớt term trong `mustTerms`, không đụng tới `phrases`
hay `orGroups`. Kiểm tra này chạy TRƯỚC khi bắt đầu vòng lặp thử — tránh lãng
phí tới `k` lần đánh giá lại cây cho một truy vấn không có cách nào cứu được.

### 28.2 ★ Vì sao "cái gì KHÔNG bao giờ bị bỏ" quan trọng hơn "cái gì bị bỏ"

Javadoc liệt kê rõ:

> Cụm từ trong ngoặc kép và nhóm `OR` là ý định TƯỜNG MINH của người dùng,
> không phải suy diễn của hệ thống; term bị loại trừ (`-từ`) lại càng không —
> bỏ một mệnh đề NOT sẽ THÊM vào kết quả đúng những thứ người dùng nói rõ là
> không muốn. Chỉ các term đơn AND ngầm định mới là thứ hệ thống tự suy ra,
> nên cũng chỉ chúng mới được phép rút lại.

Đây chính là lý do truy vấn `"tơi bời" trump -mỹ` ở [mục 35](#35-truy-vấn-2-tơi-bời-trump--mỹ--rỗng-vì-loại-trừ-không-vì-thiếu)
trả về rỗng **và không hề được nới lỏng**, dù về mặt kỹ thuật `relaxAndRetry`
có chạy: `mustTerms` của nó chỉ có `[trump]` — không có gì để bỏ bớt mà vẫn
còn "AndNode với ít nhất một mệnh đề khẳng định" (vòng `while (remaining.size()
> 1)` dừng khi còn đúng 1 phần tử) — trong khi nguyên nhân rỗng thực sự nằm ở
mệnh đề `NOT mỹ`, một thứ nằm ngoài phạm vi mà giai đoạn 2 được phép động vào.

### 28.3 Hai bước nới lỏng, theo đúng thứ tự IDF tăng dần

```mermaid
flowchart TD
    A["remaining = mustTerms (bản sao)"] --> B["Bước 1: removeIf df == 0<br/>bỏ TẤT CẢ trong MỘT lần"]
    B --> C{"có bỏ được gì<br/>không?"}
    C -->|"có"| D["attempt() với remaining đã bỏ"]
    D --> E{"có ứng viên?"}
    E -->|"có"| RET(["trả về, dropped = các term df=0"])
    E -->|"không"| F
    C -->|"không"| F
    F["Bước 2: sort remaining theo df GIẢM DẦN<br/>(phổ biến nhất trước)"]
    F --> G{"remaining.size() > 1?"}
    G -->|"có"| H["bỏ phần tử ĐẦU (df cao nhất)<br/>attempt()"]
    H --> I{"có ứng viên?"}
    I -->|"có"| RET
    I -->|"không"| G
    G -->|"không (còn 1 hoặc 0)"| EMPTY(["ResolvedQuery rỗng"])

    style D fill:#0b7a3b,color:#fff
    style H fill:#0b7a3b,color:#fff
    style RET fill:#0b7a3b,color:#fff
    style EMPTY fill:#b3261e,color:#fff
```

**Bước 1 — bỏ tất cả term có `df = 0` trong một lần**, không phải từng cái
một. Javadoc giải thích: term có `df = 0` không thể khớp bất kỳ tài liệu nào,
nên giữ lại dù chỉ một cái cũng khiến kết quả rỗng vĩnh viễn. Đây cũng là
nguyên nhân phổ biến nhất khiến người dùng gặp 0 kết quả: gõ sai chính tả,
hoặc dùng một từ không có trong corpus. Bỏ hết trong một bước tiết kiệm tới
`k` lần đánh giá lại cây so với bỏ từng cái.

**Bước 2 — bỏ dần từng term, PHỔ BIẾN NHẤT trước** (`df` giảm dần —
`Comparator.comparingInt(index::getDocumentFrequency).reversed()`). Javadoc
giải thích lựa chọn thứ tự này:

> Đây là *IDF tăng dần*, tức bỏ term PHỔ BIẾN nhất trước, giữ lại term HIẾM
> nhất — vì term hiếm mang nhiều thông tin phân biệt hơn. Dùng đúng một đại
> lượng mà `BM25Scorer.idf` dùng để chấm điểm, nên khâu nới lỏng và khâu xếp
> hạng không nói hai thứ khác nhau về "term nào quan trọng".

Vòng lặp dừng khi `remaining.size() > 1` không còn đúng — tức khi chỉ còn lại
đúng một term, không bỏ tiếp để tránh về `ast == null` (không còn mệnh đề
khẳng định nào).

### 28.4 `attempt()` — thử lại và điểm bất biến về chấm điểm

```java
private static ResolvedQuery attempt(SearchIndex index, QueryParser.ParsedQuery parsed,
                                      Map<String, Integer> queryTermFrequency,
                                      List<String> remainingTerms, List<String> dropped) {
    QueryParser.ParsedQuery relaxed = new QueryParser.ParsedQuery(
            List.copyOf(remainingTerms), parsed.phrases(), parsed.excludedTerms(),
            parsed.orGroups(), parsed.siteFilter());
    QueryNode ast = AST_BUILDER.buildAst(relaxed);
    if (ast == null) return null;
    List<Integer> candidates = applyFilters(ast.evaluate(index), index, relaxed);
    if (candidates.isEmpty()) return null;
    // Điểm vẫn tính theo truy vấn GỐC: tài liệu khớp nhiều term hơn vẫn trên.
    return new ResolvedQuery(candidates, queryTermFrequency, List.copyOf(dropped));
}
```

★ Dòng cuối cùng là điểm bất biến quan trọng nhất của toàn giai đoạn 2:
`attempt()` dựng một `ParsedQuery relaxed` **mới**, chỉ khác bản gốc ở
`mustTerms` (đã rút gọn) — `phrases`, `excludedTerms`, `orGroups`,
`siteFilter` giữ nguyên. Nhưng `ResolvedQuery` trả về mang theo
`queryTermFrequency` của **truy vấn gốc**, không phải của `relaxed`. Điều này
đảm bảo: khi `ResultRanker` chấm điểm tài liệu, một tài liệu tình cờ vẫn chứa
term đã bị bỏ (dù nó không còn là điều kiện lọc bắt buộc) vẫn được thưởng điểm
cho nó — tài liệu khớp 4/5 term của truy vấn gốc vẫn xếp trên tài liệu chỉ
khớp 3/5, dù cả hai đều "qua được" vòng lọc rút gọn.

`droppedTerms` được trả ra **ngoài cùng** `SearchResponse` (qua
`ResolvedQuery.droppedTerms()`) — không bị giấu đi. Đây chính là bất biến
đã nêu ở [mục 1.3](#13-vì-sao-searchresponse-trả-về-pagesize-đã-áp-dụng-không-phải-size-client-gửi):
người dùng có quyền biết kết quả họ đang xem ứng với một truy vấn **hẹp hơn**
truy vấn họ vừa gõ.

---
---

# PHẦN V — TỪ ỨNG VIÊN ĐẾN PHẢN HỒI

---

## 29. Bàn giao cho `ResultRanker`

**File:** `ranking/ResultRanker.java` (module `libs/core-search`) — chỉ nêu
ranh giới trách nhiệm ở đây; chi tiết công thức BM25, Decorator PageRank/title
boost, và sinh snippet thuộc về `docs2/RANKING-PIPELINE.md`.

```java
// SearchEngineFacade.search — điểm gọi
int topN = Math.max(page * size, size);
List<ResultRanker.RankedResult> ranked = resultRanker.rank(
        candidates, resolved.queryTermFrequency(), currentIndex,
        currentScorer, currentPageRank, topN);
```

| Tham số truyền vào `rank()` | Nguồn gốc | Tại sao chặng này cần nó |
|---|---|---|
| `candidates` | `resolved.candidateDocIds()` | Tập ứng viên đã qua Composite + Chain of Responsibility + (có thể) nới lỏng |
| `queryTermFrequency` | `resolved.queryTermFrequency()` | Vector truy vấn — luôn của truy vấn GỐC (mục 28.4), BM25 cần để tính điểm khớp từng term |
| `currentIndex`, `currentScorer`, `currentPageRank` | Biến cục bộ đã chụp một lần (mục 10) | Đảm bảo chấm điểm dùng ĐÚNG thế hệ chỉ mục đã dùng để truy hồi |
| `topN` | `Math.max(page * size, size)` | Số lượng tối đa cần giữ lại sau khi sắp hạng — đưa vào `MinHeap.topK` |

★ **Ranh giới rõ ràng:** `CandidateResolver` không hề biết gì về điểm số hay
thứ tự — nó trả về một **tập hợp** docId (không có nghĩa thứ tự nào ngoài
việc sắp tăng dần theo docId, thứ tự thuận tiện cho thuật toán, không phải
thứ tự liên quan). `ResultRanker` không hề biết gì về cách một tài liệu lọt
vào được tập ứng viên — nó chỉ nhận một danh sách docId và một vector tần
suất truy vấn. Đây chính là ranh giới Composite/Chain-of-Responsibility (xử lý
truy hồi) với Strategy/Decorator (xử lý xếp hạng, xem `docs2/RANKING-PIPELINE.md`)
— hai bài toán khác hẳn nhau về bản chất toán học: một là đại số tập hợp trên
posting list, một là hàm số liên tục trên không gian điểm số.

---

## 30. Cắt trang và `SearchResponse`

```java
int fromIndex = Math.min((Math.max(page, 1) - 1) * size, ranked.size());
int toIndex = Math.min(fromIndex + size, ranked.size());
List<SearchResult> pageResults = new ArrayList<>(toIndex - fromIndex);
for (ResultRanker.RankedResult r : ranked.subList(fromIndex, toIndex)) {
    pageResults.add(new SearchResult(
            r.document().getTitle(), r.document().getUrl(), r.snippet(),
            r.finalScore(), r.pageRankScore(), r.document().getCrawledAt()));
}
```

Cả `fromIndex` và `toIndex` đều được **kẹp** (`Math.min(…, ranked.size())`) —
một `page` vượt quá số trang thực có (ví dụ `page=1000` cho một truy vấn chỉ
có 4 kết quả) cho ra `fromIndex == toIndex == ranked.size()`, tức
`pageResults` rỗng một cách an toàn, không ném `IndexOutOfBoundsException`.

```java
SearchResponse response = new SearchResponse(
        normalizedQuery, candidates.size(), page, size, elapsed, pageResults,
        resolved.droppedTerms());
```

⚠ ★ `totalResults` trong response là `candidates.size()` — **tổng số ứng
viên đã qua truy hồi**, không phải `pageResults.size()` (số kết quả của
riêng trang hiện tại). Đây là hành vi ĐÚNG cho phân trang: client cần biết
"có tổng cộng bao nhiêu kết quả" để vẽ điều khiển phân trang, không phải "có
bao nhiêu kết quả trên trang này" (con số đó suy ra được từ độ dài
`results`). Nhầm lẫn hai con số này là lỗi thường gặp khi đọc code phân trang
lần đầu.

---

## 31. Ghi cache và học gợi ý truy vấn

```java
cache.put(cacheKey, response);

if (!candidates.isEmpty()) {
    suggestionService.learnFromQuery(normalizedQuery);
}
return response;
```

Hai thao tác cuối cùng của `search()`, theo đúng thứ tự:

1. **`cache.put`** — ghi **trước** khi kiểm tra học gợi ý, vì cache phải lưu
   cả kết quả rỗng (một truy vấn không có kết quả gõ lại lần nữa vẫn nên trả
   nhanh từ cache, không lặp lại toàn bộ pipeline).
2. **`learnFromQuery`** — chỉ chạy khi `candidates` **không rỗng**. Comment
   trong mã giải thích triết lý:

   > Truy vấn THẬT của người dùng là nguồn gợi ý tốt nhất. Chỉ học từ truy vấn
   > CÓ kết quả, để không học phải lỗi chính tả.

   Nếu bỏ điều kiện này, một người dùng gõ nhầm `"khủng long"` thành
   `"khủgn long"` (giả định corpus không có tài liệu nào chứa `khủgn`) sẽ
   khiến hệ thống gợi ý chính lỗi chính tả đó cho người dùng **tiếp theo** gõ
   `khủ...` — một vòng phản hồi tự làm hỏng chất lượng gợi ý theo thời gian
   nếu không có điều kiện chặn này.

★ Lưu ý: điều kiện `!candidates.isEmpty()` xét trên `candidates` **sau khi**
có thể đã qua `relaxAndRetry` — tức một truy vấn đã bị nới lỏng nhưng cuối
cùng vẫn cho ra kết quả **vẫn được học**, dù nó không khớp đầy đủ 100% những
gì người dùng gõ. Điều này nhất quán với triết lý "báo ra thay vì giấu đi" của
`droppedTerms`: hệ thống không coi truy vấn nới lỏng là "sai", chỉ là "không
khớp đầy đủ".

---
---

# PHẦN VI — ĐỐI CHIẾU OUTPUT THẬT

Mọi số liệu trong phần này lấy từ một lần chạy **thật**: dựng
`InvertedIndex` bằng chính `IndexBuilder(new VietnameseTokenizer())` mà
`SearchEngineFacade` dùng, nạp từ `ContentStorage.loadFromJson("data/seed-documents.json")`,
rồi gọi thật `QueryParser.parse()` → `CandidateResolver.resolve()` trên index
đó. Không có docId, `df`, hay tiêu đề tài liệu nào bị bịa.

---

## 32. Corpus dùng để trace

```
Loaded docs: 40
totalDocs = 40
termCount = 8973
avgdl     = 840.85
```

Corpus `seed-documents.json` là 40 trang thật crawl từ `vnexpress.net` và
`e.vnexpress.net` ngày 29/7/2026 — tin tức thời sự, thể thao, kinh doanh song
song tiếng Việt và tiếng Anh (trang quốc tế). Vài tài liệu dùng xuyên suốt
phần này:

| docId | url | title (rút gọn) |
|---|---|---|
| 0 | `vnexpress.net/` | Báo VnExpress — trang chủ, tổng hợp nhiều tin (Trump, Iran, TP HCM…) |
| 2 | `vnexpress.net/chu-de/thanh-pho-ho-chi-minh-2091` | Tin tức TP HCM 24h… báo **Sài Gòn** mới hàng ngày |
| 4 | `vnexpress.net/tin-tuc-24h` | Tin tức 24h tại **Việt Nam** & Thế giới |
| 6 | `vnexpress.net/ong-trump-doa-danh-iran-toi-boi-…` | Ông **Trump** doạ 'đánh **Iran** **tơi bời**' |
| 18 | `vnexpress.net/cach-thong-ke-thuong-vong-chien-su-iran-…` | Cách thống kê thương vong chiến sự **Iran** khiến chính quyền **Mỹ** hứng chỉ trích |

`df` (document frequency) của các term dùng trong phần này, đo trực tiếp bằng
`index.getDocumentFrequency(term)`:

| Term | df | Term | df |
|---|---|---|---|
| `trump` | 9 | `mỹ` | 9 |
| `iran` | 4 | `sài_gòn` | 4 |
| `việt_nam` | 9 | `tơi_bời` | 3 |
| `khủng_long` | **0** | | |

---

## 33. Truy vấn 1: `trump iran` — AND hai term phổ biến

Cây AST: `AndNode[TermNode(trump), TermNode(iran)]`.

```mermaid
flowchart LR
    Q(["trump iran"]) --> AST["AndNode"]
    AST --> T1["TermNode(trump)<br/>df=9"]
    AST --> T2["TermNode(iran)<br/>df=4"]
```

`AndNode.evaluate` sắp `iran` (df=4, nhỏ hơn) lên **trước** `trump` (df=9) —
đúng shortest-first ở [mục 18.2](#182--vì-sao-shortest-first-bằng-con-số-cụ-thể).
`accumulator` khởi đầu bằng posting list của `iran`, rồi giao với posting list
của `trump`.

**Kết quả thật:**

```
candidates = [0, 4, 6, 18]
dropped    = []
```

| docId | title |
|---|---|
| 0 | Báo VnExpress — Báo tiếng Việt nhiều người xem nhất |
| 4 | Tin tức 24h tại Việt Nam & Thế giới mới nhất [HÔM NAY] |
| 6 | Ông Trump dọa 'đánh Iran tơi bời' — Báo VnExpress |
| 18 | Cách thống kê thương vong chiến sự Iran khiến chính quyền Mỹ hứng chỉ trích |

Cả 4 tài liệu đều chứa cả `trump` **và** `iran` — hợp lý: doc 0 là trang chủ
tổng hợp (chứa nguyên văn bài doc 6 lẫn nhiều tin khác), doc 6 là chính bài về
Trump/Iran, doc 18 nói về thống kê thương vong chiến sự Iran có nhắc Trump/Mỹ,
doc 4 là trang "tin 24h" tổng hợp tương tự doc 0.

`applyFilters` không đổi gì (không có `site:`, 4 ứng viên còn xa ngưỡng
`10.000`) — `resolve()` trả kết quả ngay ở **giai đoạn 1**, không chạm tới
`relaxAndRetry`.

---

## 34. Truy vấn 2: `"tơi bời" trump -mỹ` — rỗng vì loại trừ, không vì thiếu

Đây là truy vấn quan trọng nhất trong PHẦN VI: nó minh hoạ một hành vi mà chỉ
đọc mã không đủ để thấy rõ — chỉ có **chạy thật** mới lộ ra.

Cây AST: `AndNode[TermNode(trump), PhraseNode([tơi_bời]), NotNode(TermNode(mỹ))]`.

**Truy vết từng bước:**

```mermaid
flowchart TD
    A["TermNode(trump).evaluate<br/>df=9 -> {0,1,4,6,9,13,14,18,24}"] --> C["intersect"]
    B["PhraseNode([tơi_bời]).evaluate<br/>df=3, lọc thô rồi matchesPhrase"] --> C
    C --> D["accumulator sau AND khẳng định<br/>= {6} (chỉ doc 6 có cả trump VÀ cụm tơi_bời)"]
    D --> E["NotNode(mỹ).evaluateAgainst({6}, index)"]
    E --> F{"doc 6 có chứa mỹ không?"}
    F -->|"CÓ — bài về Trump/Iran nhắc 'Mỹ' nhiều lần"| G(["loại doc 6 ra<br/>accumulator = {} RỖNG"])

    style G fill:#b3261e,color:#fff
```

**Kết quả thật:**

```
df(trump)=9, df(tơi_bời)=3, df(mỹ)=9
candidates = []
dropped    = []
```

★★ Chú ý dòng cuối: `dropped = []` — **không phải** `[trump]` hay bất kỳ
term nào. `resolve()` **có** gọi `relaxAndRetry` (vì candidates rỗng ở giai
đoạn 1), nhưng `relaxAndRetry` chỉ có quyền bỏ bớt phần tử trong `mustTerms`
— ở truy vấn này `mustTerms = [trump]`, chỉ có đúng một phần tử. Điều kiện
dừng `while (remaining.size() > 1)` ở [mục 28.3](#283-hai-bước-nới-lỏng-theo-đúng-thứ-tự-idf-tăng-dần)
không cho vòng lặp chạy (kích thước đã là 1, không lớn hơn 1), nên không có gì
để thử. Nguyên nhân thật sự khiến kết quả rỗng — mệnh đề `NOT mỹ` loại bỏ
đúng tài liệu duy nhất khớp phần khẳng định — nằm **ngoài phạm vi** mà giai
đoạn 2 được phép động vào (theo đúng nguyên tắc ở
[mục 28.2](#282--vì-sao-cái-gì-không-bao-giờ-bị-bỏ-quan-trọng-hơn-cái-gì-bị-bỏ):
loại trừ là ý định tường minh, không phải suy diễn của hệ thống).

Đây là ví dụ thật cho câu hỏi FAQ "vì sao truy vấn của tôi vẫn 0 kết quả dù hệ
thống có cơ chế nới lỏng?" — xem thêm [mục 40](#40-câu-hỏi-thường-gặp).

---

## 35. Truy vấn 3: `trump OR iran` — hợp

Cây AST: `AndNode[OrNode[TermNode(trump), TermNode(iran)]]` (một `AndNode` bọc
ngoài với duy nhất một con là `OrNode` — do `buildAst` luôn bọc mọi mệnh đề
khẳng định trong một `AndNode`, kể cả khi chỉ có một mệnh đề).

**Kết quả thật:**

```
candidates = [0, 1, 4, 6, 9, 13, 14, 18, 24]
```

9 tài liệu — đúng bằng `df(trump) ∪ df(iran)` sau khi loại trùng, và vì mọi
tài liệu chứa `iran` (4 tài liệu: 0, 6, 14, 18) đều **cũng** chứa `trump`
(tập con của tập 9 tài liệu chứa `trump`), `union` ở đây thực chất cho kết quả
trùng khít với tập `df(trump)` — minh hoạ trực quan lý do
`OrNode.estimatedSize` chỉ là **chặn trên** (mục 19): tổng `9 + 4 = 13` nhưng
kết quả thật chỉ có 9, vì 4 tài liệu trùng lặp hoàn toàn.

So với truy vấn 1 (`trump iran`, AND, 4 kết quả), truy vấn này (OR) cho **hơn
gấp đôi** số kết quả — đúng trực giác: OR luôn cho tập kết quả lớn hơn hoặc
bằng AND của cùng cặp term.

---

## 36. Truy vấn 4: `sài gòn site:vnexpress.net` — lọc domain

Cây AST chỉ còn `AndNode[TermNode(sài_gòn)]`; `site:vnexpress.net` không nằm
trong cây — nó là `parsed.siteFilter()`, được `DomainFilter` áp dụng ở tầng
lọc sau truy hồi.

**Trước `DomainFilter`** (`ast.evaluate` một mình): posting list của
`sài_gòn` có `df=4`.

**Kết quả thật sau `applyFilters`:**

```
candidates = [2, 20, 21, 32]
```

| docId | title | host |
|---|---|---|
| 2 | Tin tức TP HCM 24h mới nhất, báo **Sài Gòn** mới hàng ngày | `vnexpress.net` |
| 20 | Tin nóng — Báo VnExpress | `vnexpress.net` |
| 21 | Diễn đàn chia sẻ kinh nghiệm, quan điểm xã hội, dân sinh — VnExpress | `vnexpress.net` |
| 32 | Tâm sự — Diễn đàn tư vấn tình yêu, gia đình, cuộc sống — VnExpress | `vnexpress.net` |

Toàn bộ 40 tài liệu của corpus này đều thuộc `vnexpress.net` hoặc
`e.vnexpress.net`, nên trên corpus nhỏ này `DomainFilter` **không loại thêm
tài liệu nào** so với trước lọc (`4 → 4`) — một minh hoạ trung thực rằng hiệu
ứng của `site:` chỉ rõ rệt trên corpus đa domain thật (hàng nghìn trang từ
nhiều báo khác nhau), không phải trên bộ seed nhỏ dùng để trace tài liệu này.
`DomainFilter` khớp theo hậu tố (mục 27.2): nếu một trong bốn tài liệu trên
thuộc `sport.vnexpress.net`, nó vẫn được giữ lại vì host đó kết thúc bằng
`.vnexpress.net`.

---

## 37. Truy vấn 5: `trump khủng long iran` — nới lỏng thật

Đây là ví dụ **thật** của giai đoạn 2 thực sự làm việc — khác truy vấn 2
(mục 34), lần này nguyên nhân rỗng NẰM ĐÚNG trong phạm vi `relaxAndRetry` được
phép sửa.

`khủng long` tokenize thành `khủng_long` — một từ ghép **không có trong
corpus 40 tài liệu này** (`df(khủng_long) = 0`, tin thời sự không nói về
khủng long).

**Giai đoạn 1** (đầy đủ): `AndNode[Term(trump), Term(khủng_long), Term(iran)]`
— shortest-first đưa `khủng_long` (df=0) lên đầu, `accumulator` khởi đầu đã
**rỗng**, và mọi phép giao sau đó dừng ngay (rỗng là phần tử hấp thụ, mục 18).
`candidates` rỗng → chuyển sang giai đoạn 2.

**Giai đoạn 2:** `isUnmatchable` kiểm tra `phrases` (rỗng — không áp dụng) và
`orGroups` (rỗng — không áp dụng) → không unmatchable, tiếp tục. Bước 1
(`removeIf df==0`) bắt đúng `khủng_long`:

```mermaid
flowchart LR
    A["mustTerms = [trump, khủng_long, iran]"] --> B["Bước 1: removeIf df==0"]
    B --> C["dropped = [khủng_long]<br/>remaining = [trump, iran]"]
    C --> D["attempt() với AndNode[Term(trump), Term(iran)]"]
    D --> E["candidates = [0,4,6,18] — KHÔNG rỗng"]
    E --> F(["trả về NGAY, không chạy Bước 2"])

    style F fill:#0b7a3b,color:#fff
```

**Kết quả thật:**

```
candidates = [0, 4, 6, 18]
dropped    = [khủng_long]
```

Trùng khớp **chính xác** với kết quả của truy vấn 1 (`trump iran`) ở mục 33 —
đúng như kỳ vọng, vì sau khi bỏ `khủng_long`, truy vấn còn lại đúng là
`trump AND iran`. `droppedTerms=[khủng_long]` được trả ra trong
`SearchResponse`, để client hiển thị kiểu "không có kết quả chính xác cho
'khủng long', hiển thị kết quả gần đúng cho 'trump iran'".

★ Một biến thể ngắn hơn, `trump khủng long` (không có `iran`), cho ra 9 ứng
viên **giống hệt** tập kết quả của truy vấn 3 (`trump OR iran`, mục 35) —
không phải trùng hợp: sau khi bỏ `khủng_long`, `mustTerms` chỉ còn `[trump]`,
và `AndNode` với một con duy nhất trả về đúng posting list của `trump`, tình
cờ đúng bằng tập `trump ∪ iran` vì (đã nêu ở mục 35) mọi tài liệu chứa `iran`
đều cũng chứa `trump`.

---
---

# PHẦN VII — PHỤ LỤC

---

## 38. Bảng hằng số toàn hệ thống

| Hằng số | Giá trị | File | Ý nghĩa |
|---|---|---|---|
| `MAX_PAGE` | `1_000` | `SearchController` | Trần `page`, chặn tràn `int` khi tính `topN = page * size` |
| `MAX_SIZE` | `100` | `SearchController` | Trần `size` mỗi trang |
| `DEFAULT_SIZE` | `20` | `SearchController` | `size` dùng khi tham số ngoài khoảng hợp lệ |
| `PHRASE_PATTERN` | `"([^"]*)"` | `QueryParser` | Regex cắt cụm từ trong ngoặc kép, biên dịch sẵn một lần |
| `OR_KEYWORD` | `"OR"` | `QueryParser` | Từ khoá nhận diện nhóm lựa chọn (phân biệt hoa/thường — chỉ `OR` viết hoa mới hợp lệ) |
| `SITE_PREFIX` | `"site:"` | `QueryParser` | Tiền tố nhận diện ràng buộc domain |
| `DEFAULT_MAX_CANDIDATES` | `10_000` | `MaxCandidatesFilter` | Trần số ứng viên đưa sang khâu chấm điểm |
| *(không hằng số riêng)* `app.search.cache-size` | `200` (mặc định Spring `@Value`) | `SearchEngineFacade` | Sức chứa `LRUCache<String, SearchResponse>` |

---

## 39. Bảng tra nhanh khối ↔ file ↔ hàm

| Khối trong sơ đồ | File | Hàm |
|---|---|---|
| Chặn `page`/`size` | `controller/SearchController.java` | `search()` |
| Điều phối toàn pipeline | `service/SearchEngineFacade.java` | `search()` |
| Chụp trạng thái 1 lần | `service/SearchEngineFacade.java` | `search()` — 4 dòng đầu |
| `cacheKey`, LRU | `service/SearchEngineFacade.java` + `datastructure/LRUCache.java` | `search()`, `LRUCache.get/put` |
| Cắt cụm ngoặc kép | `query/QueryParser.java` | `parse()` — Bước 1 |
| Quét `site:`/`OR`/`-loại_trừ` | `query/QueryParser.java` | `parse()` — Bước 2 |
| Tokenize | `query/QueryParser.java` + `index/VietnameseTokenizer.java` | `tokenizeToTerms()`, `tokenize()` |
| Dựng cây AST | `query/QueryParser.java` | `buildAst()` |
| Tần suất truy vấn | `query/CandidateResolver.java` | `buildQueryTermFrequency()` |
| Đánh giá AND | `query/ast/AndNode.java` | `evaluate()` |
| Đánh giá OR | `query/ast/OrNode.java` | `evaluate()` |
| Đánh giá cụm từ | `query/ast/PhraseNode.java` + `query/PostingListMerger.java` | `evaluate()`, `matchesPhrase()` |
| Loại trừ | `query/ast/NotNode.java` | `evaluateAgainst()` |
| Giao/hợp posting list | `query/PostingListMerger.java` | `intersect()`, `union()`, `intersectCursors()` |
| Lọc domain | `query/filter/DomainFilter.java` | `apply()` |
| Chặn trần ứng viên | `query/filter/MaxCandidatesFilter.java` | `apply()` |
| Nới lỏng truy vấn | `query/CandidateResolver.java` | `relaxAndRetry()`, `attempt()`, `isUnmatchable()` |
| Bàn giao xếp hạng | `ranking/ResultRanker.java` | `rank()` (chi tiết: `docs2/RANKING-PIPELINE.md`) |
| Cắt trang | `service/SearchEngineFacade.java` | `search()` — đoạn cuối |
| Học gợi ý | `service/SuggestionService.java` | `learnFromQuery()` |

---

## 40. Câu hỏi thường gặp

**1. Vì sao gõ `"máy tính"` (có ngoặc kép quanh MỘT từ ghép) lại không có gì
đặc biệt so với gõ `máy tính` (không ngoặc)?**
Vì `PhraseNode` với một cụm chỉ chứa đúng một token sau tokenize hoạt động
giống hệt `TermNode` — điều kiện "liên tiếp" của một chuỗi một-phần-tử luôn
đúng. Ngoặc kép chỉ tạo khác biệt thật khi cụm tokenize ra **từ hai token trở
lên**.

**2. Vì sao `NOT` một mình luôn cho kết quả rỗng, ngay cả khi có tài liệu thật
sự không chứa từ đó?**
`NotNode.evaluate()` không được cài đặt theo hợp đồng thông thường — nó ném
`UnsupportedOperationException` (mục 20.1). Nhưng thực tế người dùng không
bao giờ chạm được lỗi này: `buildAst` (mục 13) đặt mọi `NotNode` **sau** khi
đã kiểm tra `children.isEmpty()`, nên một truy vấn chỉ toàn dấu trừ nhận
`ast == null` và trả về tập rỗng ngay từ `resolve()`, không hề tạo ra một
`AndNode` toàn `NotNode`.

**3. Vì sao truy vấn nới lỏng vẫn chấm điểm theo truy vấn gốc, không phải
theo truy vấn đã bị bỏ bớt term?**
`queryTermFrequency` được tính **một lần duy nhất** ở đầu `resolve()`
(mục 23), từ `parsed` gốc — trước khi biết liệu có nới lỏng hay không. Mọi
lần gọi `attempt()` trong giai đoạn 2 đều tái sử dụng đúng `Map` đó
(mục 28.4), không tính lại. Mục đích: tài liệu khớp 4/5 term của truy vấn gốc
vẫn xếp trên tài liệu chỉ khớp 3/5, dù cả hai đều "qua" được vòng lọc rút gọn.

**4. Vì sao một nhóm `OR` chỉ còn một vế lại "hạ cấp" thành một `mustTerm`
thay vì vẫn là một `OrNode` một-con?**
Về mặt kết quả, `OrNode` với một con duy nhất và `TermNode` đó đứng thẳng
trong `AndNode` cho ra **kết quả giống hệt nhau** — `union(List.of(),
child.evaluate())` (mục 19) chỉ là chính `child.evaluate()`. Giữ nó như một
`mustTerm` đơn giản hơn, tránh một tầng bọc thừa trong cây, và tránh
`estimatedSize` của `OrNode` (một phép cộng, dù chỉ cộng một số) chạy không
cần thiết.

**5. Vì sao `DomainFilter` không nằm trong cây `AndNode`, cùng chỗ với
`TermNode`?**
Vì `site:` không có posting list tương ứng — nó là ràng buộc trên URL của tài
liệu (siêu dữ liệu), không phải quan hệ boolean giữa các term (mục 27.2). Đưa
nó vào cây sẽ buộc phải dựng thêm chỉ mục phụ `host -> docIds`; với vài chục
ứng viên đã qua bước giao posting list, kiểm tra URL trực tiếp đơn giản hơn.

**6. Vì sao truy vấn của tôi bị nới lỏng (`droppedTerms` khác rỗng) nhưng
`totalResults` vẫn hiển thị một con số như thể mọi thứ bình thường?**
`totalResults = candidates.size()` sau `resolve()` — không phân biệt candidates
đến từ giai đoạn 1 (đầy đủ) hay giai đoạn 2 (nới lỏng). Client cần nhìn riêng
trường `droppedTerms` (không rỗng) để biết kết quả đang hiển thị ứng với một
truy vấn hẹp hơn — xem mục 30 và mục 1.3.

**7. Vì sao truy vấn hai term phổ biến như `trump iran` (mục 33) lại nhanh
hơn nhiều so với việc duyệt toàn bộ posting list của từng term rồi so sánh thủ
công?**
Nhờ `intersect` two-pointer `O(m+n)` (mục 25.1) thay vì `HashSet.retainAll`
(chậm hơn tới 2,7 lần theo đo thực tế trên danh sách 500.000 phần tử) và nhờ
shortest-first (mục 18.2) đảm bảo phía giao luôn bắt đầu từ tập nhỏ nhất.

**8. Truy vấn `-mỹ` một mình (không có must term nào khác) trả về gì?**
Rỗng, và **không** thông qua cơ chế nới lỏng nào — `buildAst` trả `null` vì
`children` rỗng tại thời điểm kiểm tra (chỉ có `NotNode`, chưa được thêm vào),
`resolve()` trả `ResolvedQuery(List.of(), queryTermFrequency)` ngay, không gọi
`ast.evaluate()`, không gọi `relaxAndRetry` (mục 28: `relaxAndRetry` yêu cầu
`mustTerms` không rỗng, thoát sớm nếu rỗng).

**9. Vì sao `site:` khớp theo hậu tố mà không khớp chính xác tuyệt đối?**
Để `site:vnexpress.net` bắt được cả các phụ miền như `sport.vnexpress.net`,
`e.vnexpress.net` — hành vi mà hầu hết máy tìm kiếm thật đều làm cho toán tử
`site:`, vì người dùng thường nghĩ về một "trang web" như một thực thể, không
phân biệt phụ miền.

**10. `MaxCandidatesFilter` với ngưỡng mặc định `10_000` có đảm bảo giữ đúng
top-10.000 điểm cao nhất không?**
Không. Vì posting list sắp theo **docId**, không theo điểm số, việc cắt
`subList(0, maxCandidates)` giữ 10.000 ứng viên **đầu tiên theo docId**, không
phải 10.000 ứng viên điểm cao nhất (mục 27.3). Đây là đánh đổi có ý thức —
Javadoc của lớp gọi rõ nó là "bảo vệ hệ thống khỏi truy vấn bất thường, không
phải một tối ưu xếp hạng" — khác hẳn WAND/MaxScore của các hệ thống production
lớn.

**11. Vì sao `QueryParser` nhận `Tokenizer` qua constructor thay vì tự
`new VietnameseTokenizer()` bên trong?**
Để **ép buộc** cùng một thực thể tokenizer (cùng từ điển, cùng cấu hình) được
dùng ở cả lúc dựng chỉ mục lẫn lúc phân tích truy vấn (mục 8). Nếu hai bên
lệch nhau — ví dụ index dùng từ điển 185.000 mục nhưng truy vấn dùng một
tokenizer mặc định nghèo hơn — mọi từ ghép sẽ tokenize khác nhau ở hai phía và
không bao giờ khớp, mà không có ngoại lệ hay log nào cảnh báo.

**12. `"tơi bời" trump -mỹ` (mục 34) và `trump khủng long iran` (mục 37) đều
trả về rỗng ở giai đoạn 1 — vì sao một cái được nới lỏng cứu sống, cái kia
không?**
Vì nguyên nhân rỗng khác nhau. Truy vấn thứ hai rỗng vì một `mustTerm`
(`khủng_long`) có `df=0` — đúng loại lỗi mà `relaxAndRetry` được thiết kế để
sửa. Truy vấn thứ nhất rỗng vì mệnh đề `NOT mỹ` loại bỏ đúng tài liệu duy nhất
khớp phần khẳng định — nằm ngoài phạm vi `relaxAndRetry` được phép sửa (chỉ
được rút bớt `mustTerms`, không được rút bớt `excludedTerms`).

---

## 41. Chẩn đoán sự cố

```mermaid
flowchart TD
    START(["Truy vấn luôn 0 kết quả"]) --> Q1{"Có `droppedTerms`<br/>trong response không?"}
    Q1 -->|"có"| A1(["Bình thường — hệ thống ĐÃ nới lỏng<br/>nhưng vẫn cho candidates rỗng ở lần thử cuối,<br/>hoặc client chưa đọc droppedTerms để hiển thị"]) 
    Q1 -->|"không, droppedTerms rỗng"| Q2{"Truy vấn có cụm ngoặc kép<br/>hoặc nhóm OR không?"}
    Q2 -->|"có"| A2(["Kiểm tra df từng tiếng trong cụm/nhóm bằng<br/>index.getDocumentFrequency —<br/>một tiếng df=0 trong CỤM khiến isUnmatchable=true,<br/>relaxAndRetry thoát sớm, KHÔNG thử bỏ term nào"])
    Q2 -->|"không"| Q3{"Truy vấn có `-loại_trừ` không?"}
    Q3 -->|"có"| A3(["Thử BỎ phần -loại_trừ đi và chạy lại thủ công —<br/>nếu có kết quả, nguyên nhân là NOT loại hết<br/>ứng viên khẳng định (xem mục 34, ví dụ thật)"])
    Q3 -->|"không"| Q4{"mustTerms có tokenize đúng<br/>như kỳ vọng không?"}
    Q4 -->|"kiểm tra bằng QueryParser.main<br/>hoặc trace giống mục 14"| A4(["Nếu tokenize RA KHÁC với lúc index<br/>(ví dụ tách 'máy tính' thành 2 token<br/>thay vì 'máy_tính'), đây là lỗi<br/>BẤT BIẾN TOKENIZER (mục 8) —<br/>kiểm tra index và query có dùng<br/>CHUNG một VietnameseTokenizer"])
    A4 --> Q5{"Từ có thật trong corpus không?"}
    Q5 -->|"df=0"| A5(["Người dùng gõ từ không tồn tại/gõ sai chính tả —<br/>hành vi ĐÚNG, không phải lỗi hệ thống"])

    style A1 fill:#0b7a3b,color:#fff
    style A5 fill:#0b7a3b,color:#fff
    style A2 fill:#c9720b,color:#fff
    style A3 fill:#c9720b,color:#fff
    style A4 fill:#b3261e,color:#fff
```

<details><summary>Xem bản chữ (ASCII)</summary>

```
Truy van luon 0 ket qua
  co droppedTerms? co -> BINH THUONG, he thong da noi long
                   khong ->
    co cum ngoac kep / nhom OR? co -> kiem tra df tung tieng trong cum/nhom
                                       mot tieng df=0 -> isUnmatchable=true
                                       -> relaxAndRetry thoat SOM, khong thu gi
                                khong ->
      co -loai_tru? co -> thu BO -loai_tru chay lai thu cong
                           neu co ket qua -> NOT loai het ung vien khang dinh
                    khong ->
        mustTerms tokenize dung ky vong? khong -> LOI BAT BIEN TOKENIZER
                                                    (index va query phai dung
                                                     CHUNG mot VietnameseTokenizer)
                                          co ->
          tu co that trong corpus (df>0)? khong -> nguoi dung go sai/tu khong ton tai
                                                     (dung, khong phai loi)
```
</details>

### 41.1 Bảng chẩn đoán nhanh khác

| Triệu chứng | Nguyên nhân khả dĩ | Kiểm tra ở |
|---|---|---|
| Truy vấn chậm bất thường khi số ứng viên rất lớn | `MaxCandidatesFilter` chưa kịp cắt (candidates < 10.000 nhưng vẫn nhiều), hoặc quá nhiều `PhraseNode` chạy `matchesPhrase` trên tập lớn | mục 27.3, mục 19 (lọc thô của `PhraseNode`) |
| Kết quả `site:abc.vn` trả về tài liệu của domain khác hẳn | `DomainFilter` khớp theo hậu tố — kiểm tra domain khác có VÔ TÌNH kết thúc bằng `.abc.vn` không | mục 27.2 |
| `OR` không hoạt động, cả cụm bị coi như must-term thường | `OR` viết thường/hoa lẫn (`or`), hoặc đứng đầu/cuối câu — điều kiện `!mustRaw.isEmpty()` không thoả | mục 10.2 |
| Cache trả kết quả cũ dù vừa reindex | `cacheKey` không đổi (cùng query/page/size) trong khi `searchCache` bị thay bằng instance mới sau reindex — chính là lý do phải chụp `cache` vào biến cục bộ TRƯỚC khi `get`/`put` cùng một request | mục 10 |
| `elapsedMs`/`timeTakenMs` rất nhỏ cho một truy vấn "nặng" | Đó là cache hit — thời gian ghi trong response là thời gian của lần tính GỐC, không phải request hiện tại | mục 7 |

---

## 42. Thuật ngữ

| Thuật ngữ | Nghĩa trong ngữ cảnh tài liệu này |
|---|---|
| **AST** (Abstract Syntax Tree) | Cây biểu thức truy vấn — `QueryNode` và các cài đặt của nó |
| **Composite pattern** | Mẫu thiết kế cho phép nút lá và nút trong dùng chung một giao diện, gọi đệ quy tự nhiên — dùng cho `QueryNode` |
| **Chain of Responsibility** | Mẫu thiết kế cho một chuỗi các bộ xử lý độc lập, mỗi bộ có thể xử lý hoặc bỏ qua — dùng cho `CandidateFilter` |
| **Posting list** | Danh sách các `(docId, tf, positions[])` cho một term, sắp tăng dần theo docId |
| **`df` (document frequency)** | Số tài liệu chứa một term — độ dài của posting list |
| **Two-pointer / merge kiểu merge-sort** | Thuật toán giao/hợp hai danh sách đã sắp xếp bằng hai con trỏ tiến song song, `O(m+n)` |
| **Galloping search** | Kỹ thuật nhảy cóc (thay vì bước từng bước) khi giao hai danh sách chênh lệch kích thước lớn — `PostingCursor.skipTo` |
| **Shortest-first** | Chiến lược sắp xếp các toán hạng của một phép giao theo kích thước tăng dần trước khi thực thi, để kết quả trung gian nhỏ ngay từ đầu |
| **Filter-and-refine** | Chiến lược lọc bằng điều kiện rẻ-nhưng-yếu trước, rồi mới kiểm tra điều kiện đắt-nhưng-chính-xác trên tập đã thu hẹp — dùng trong `PhraseNode` |
| **Rỗng là phần tử hấp thụ** | Tính chất: giao của bất kỳ tập nào với tập rỗng luôn là tập rỗng — dùng để dừng sớm các vòng lặp giao/lọc liên tiếp |
| **Nới lỏng truy vấn (query relaxation)** | Bỏ bớt một số điều kiện AND ngầm định khi truy vấn đầy đủ cho ra rỗng, để trả về kết quả gần đúng thay vì trang trắng |
| **`ParsedQuery`** | Cấu trúc dữ liệu phẳng (5 danh sách/trường) là đầu ra của `QueryParser.parse()`, đầu vào của `buildAst()` |
| **`ResolvedQuery`** | Cấu trúc dữ liệu là đầu ra của `CandidateResolver.resolve()`: `candidateDocIds`, `queryTermFrequency`, `droppedTerms` |

---

## 43. Toàn cảnh một trang

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
      │  └─ (chi tiết công thức BM25 + Decorator PageRank/title + snippet:
      │      xem docs2/RANKING-PIPELINE.md)
      ├─ cắt trang: fromIndex = (max(page,1) − 1)·size,  toIndex = từ + size
      ├─ SearchResponse(query, totalHits, page, size ĐÃ ÁP DỤNG, elapsedMs, results, droppedTerms)
      ├─ cache.put(cacheKey, response)
      └─ candidates không rỗng → SuggestionService.learnFromQuery(query)
         ↳ chỉ học từ truy vấn CÓ kết quả, để không học phải lỗi chính tả
```

Cây biểu thức của các truy vấn mẫu dùng xuyên suốt tài liệu (dữ liệu thật, xem
PHẦN VI):

```
trump iran
└─ AndNode[ Term(trump), Term(iran) ]                       → 4 kết quả

"tơi bời" trump -mỹ
└─ AndNode[ Term(trump), Phrase(tơi_bời), Not(Term(mỹ)) ]    → 0 kết quả (do NOT, KHÔNG nới lỏng được)

trump OR iran
└─ AndNode[ Or[ Term(trump), Term(iran) ] ]                  → 9 kết quả

sài gòn site:vnexpress.net
└─ AndNode[ Term(sài_gòn) ]  + DomainFilter("vnexpress.net") → 4 kết quả

trump khủng long iran
└─ AndNode[ Term(trump), Term(khủng_long) df=0, Term(iran) ] → 0 → nới lỏng
   └─ bỏ khủng_long → AndNode[ Term(trump), Term(iran) ]     → 4 kết quả, droppedTerms=[khủng_long]
```

Vì sao NOT không tự đánh giá được:

```
NotNode.evaluate           → UnsupportedOperationException
NotNode.evaluateAgainst(…) → đường ĐÚNG, luôn trừ trên một tập ứng viên có sẵn
  ↳ phủ định độc lập sẽ trả về gần như TOÀN BỘ corpus — đúng về mặt tập hợp,
    vô dụng về mặt tìm kiếm, và tốn bộ nhớ đúng bằng cỡ corpus
```
