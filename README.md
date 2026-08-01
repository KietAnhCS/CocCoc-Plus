# VnSearch — Máy tìm kiếm tiếng Việt tự xây + Trình duyệt desktop

Máy tìm kiếm tiếng Việt tự crawl / tự lập chỉ mục / tự xếp hạng (**không**
dùng Elasticsearch, Lucene hay Solr), tích hợp làm trang chủ mặc định của
một trình duyệt desktop đơn giản (Electron + React).

Mọi cấu trúc dữ liệu và giải thuật lõi đều **tự cài đặt bằng tay**, kèm
phân tích độ phức tạp và **đo đạc thực nghiệm** trên corpus thật.

## Trạng thái

| Hạng mục | Số liệu |
|---|---|
| Corpus | **5.011 trang** thật từ 6 báo điện tử Việt Nam, 52 host phân biệt |
| Chỉ mục | 136.768 term phân biệt, 1.043 token/tài liệu |
| Đồ thị liên kết | 239.691 cạnh (42.002 cạnh chéo domain), độ thưa 0,95% |
| Kiểm thử | **280 test**, tất cả xanh |
| Mã nguồn | **76 lớp Java / 10.485 dòng** (backend) + **3.507 dòng test** + ~1.500 dòng TypeScript (frontend) |
| Trừu tượng hoá | **8 interface** tự định nghĩa, **10 design pattern** có chủ đích |
| Chất lượng tìm kiếm | MRR **0,8758**, Success@1 **81,5%** (cấu hình đang dùng); tốt nhất là BM25+PR+title với MRR **0,9093** |
| Tốc độ truy vấn | **~1,6 ms**/truy vấn — 1,59 ms (EvaluationRunner) / 1,62 ms (GinBaselineRunner); trước tối ưu: 3,84 ms |
| Nén chỉ mục | delta + VByte — chỉ mục **341,5 MB → 94,7 MB** (nhỏ 3,60 lần) |

## Cấu trúc thư mục

```
search-engine/
├── search-engine/          # Backend: Spring Boot (Java 17, Maven Wrapper)
│   ├── src/main/java/com/vnsearch/
│   │   ├── crawler/        # Crawler BFS đa luồng — mỗi khối trong sơ đồ kiến trúc là một lớp
│   │   │                   #   (DnsResolver, HtmlDownloader, ContentParser, ContentSeenFilter,
│   │   │                   #    ContentStorage, LinkExtractor, UrlFilter, UrlSeenFilter, UrlStorage)
│   │   │   └── frontier/  #   URL Frontier hai tầng: Prioritizer, FrontQueues, BackQueues
│   │   ├── datastructure/  # Trie, BloomFilter, LRUCache, MinHeap, SparseMatrix
│   │   ├── index/          # Tokenizer, chỉ mục đảo, nén VByte, PostingCursor, TermDictionary
│   │   ├── query/          # Phân tích truy vấn
│   │   │   ├── ast/        #   Cây biểu thức AND/OR/NOT (Composite)
│   │   │   └── filter/     #   Đường ống lọc ứng viên (Chain of Responsibility)
│   │   ├── ranking/        # TF-IDF, BM25, PageRank, ScorerFactory, snippet
│   │   │   └── decorator/  #   Tín hiệu bổ sung: PageRank, khớp tiêu đề (Decorator)
│   │   ├── eval/           # Bộ đánh giá chất lượng (độ đo IR, known-item, pooling)
│   │   ├── storage/        # DocumentStore (Strategy): PostgreSQL / JSON / seed
│   │   ├── service/        # Facade điều phối + IndexBuilder, SuggestionService, CrawlJobManager
│   │   └── controller/     # REST API
│   └── data/               # Corpus, chỉ mục, dữ liệu đánh giá
│   └── Dockerfile          # Ảnh backend (build 2 giai đoạn: Maven → JRE)
├── browser-app/            # Frontend: Electron + React + TypeScript + Tailwind
├── docs/                   # Tài liệu (xem bên dưới)
├── docker-compose.yml      # Backend + PostgreSQL — `docker compose up -d --build`
└── run-frontend.bat        # Chạy trình duyệt (Windows)
```

## Tài liệu

Tài liệu viết theo kiểu **giáo trình**: mỗi khái niệm đi từ *vấn đề* → *ý
tưởng* → *công thức* → *ví dụ tính tay* → *mã thật trong repo* → *độ phức
tạp*. Đọc theo thứ tự trong bảng.

| # | Tài liệu | Nội dung | Nên đọc khi |
|---|---|---|---|
| 1 | [**docs/SEARCH-ENGINE-101.md**](docs/SEARCH-ENGINE-101.md) | **Bắt đầu từ đây** — giáo trình đầy đủ về lý thuyết máy tìm kiếm, 13 chương, kèm ví dụ tính tay và bài tập "tự code thử" | Muốn **hiểu và tự code lại** |
| 2 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Ba tầng hệ thống, sơ đồ thành phần, 4 luồng xử lý, quyết định thiết kế kèm phương án thay thế | Muốn biết các mảnh **ghép lại** thế nào |
| 3 | [docs/ALGORITHMS.md](docs/ALGORITHMS.md) | Từng thuật toán theo thứ tự pipeline, mỗi mục có mã giả + trích mã Java thật | Đang **đọc code** và cần tra cứu |
| 3b | [**docs/SO-SANH-PHUONG-AN.md**](docs/SO-SANH-PHUONG-AN.md) | **13 bài toán, mỗi bài nhiều thuật toán giải được** — bảng so sánh, lý do chọn, và **điểm lật** (khi nào kết luận đảo chiều) | Bị hỏi **"sao không dùng cách khác?"** |
| 4 | [**docs/Math/**](docs/Math/README.md) | **Một trang cho mỗi file nguồn** — công thức đầy đủ, chứng minh, ví dụ tính tay, độ phức tạp, hạn chế | Cần **đào sâu** một thành phần |
| 5 | [**docs/Math/09-design-patterns/**](docs/Math/09-design-patterns/README.md) | **12 trang học OOP** — mỗi design pattern một trang, kèm lỗi thật mà nó sửa và câu hỏi bảo vệ | Học **OOP**, chuẩn bị **bảo vệ** |
| 6 | [docs/DSA-REPORT.md](docs/DSA-REPORT.md) | Bảng Big-O đầy đủ, lý do chọn từng cấu trúc kèm **số đo**, 3 lỗi hiệu năng phát hiện nhờ đo đạc | Viết **báo cáo đồ án** |
| 7 | [docs/EVALUATION.md](docs/EVALUATION.md) | **Đánh giá chất lượng tìm kiếm** — known-item search, 11 cấu hình ablation, phân tích thang đo *(sinh tự động)* | Cần **chứng minh** chất lượng |
| 8 | [docs/GIN-BASELINE.md](docs/GIN-BASELINE.md) | **Đối chứng với PostgreSQL GIN** — baseline bên ngoài, có làm nóng JVM đúng cách *(sinh tự động)* | Cần một **mốc so sánh** |
| — | [docs/api-examples.http](docs/api-examples.http) | Ví dụ gọi REST API | Muốn thử API ngay |

> ⚠️ **`EVALUATION.md` và `GIN-BASELINE.md` được sinh tự động — đừng sửa tay.**
> Toàn bộ nội dung (kể cả phần giảng giải) nằm trong
> `eval/EvaluationRunner.java` và `storage/GinBaselineRunner.java`; sửa ở đó
> rồi chạy lại, nếu không lần chạy kế tiếp sẽ ghi đè mất.

## Yêu cầu môi trường

- **Docker** — chạy backend + PostgreSQL
- **Node.js 20+ và npm** — chạy trình duyệt
- Java 17+ — chỉ cần khi muốn chạy backend ngoài Docker, hoặc chạy các runner
  đánh giá/crawl (không cần cài Maven, dự án dùng Maven Wrapper)

## Chạy — hai lệnh

**Backend** (Spring Boot + PostgreSQL, một lệnh duy nhất):

```bash
docker compose up -d --build
```

**Trình duyệt** (Electron — là ứng dụng desktop có cửa sổ nên không đóng gói
vào container được):

```
run-frontend.bat
```

Nháy đúp `run-frontend.bat` ở thư mục gốc là xong. Nó tự kiểm tra Node.js, tự
`npm install` lần đầu, và cảnh báo nếu backend chưa sẵn sàng.

### Kiểm tra backend đã lên chưa

```bash
docker compose ps                       # cả hai phải là "healthy"
docker compose logs -f backend          # xem log
curl "http://localhost:8080/api/admin/stats"
curl "http://localhost:8080/api/search?q=công%20nghệ&page=1&size=5"
curl "http://localhost:8080/api/suggest?prefix=cong&limit=5"
```

Lần khởi động đầu mất ~15 giây để lập chỉ mục 5.011 trang (`healthcheck` có
`start_period` 90 giây nên đừng lo khi thấy trạng thái `starting`).

Nguồn corpus được chọn theo thứ tự ưu tiên: **PostgreSQL** → `data/crawled-multi.json`
→ `data/seed-documents.json` (40 trang đi kèm repo). Thư mục `search-engine/data`
được mount vào container nên file chỉ mục dựng sẵn **sống sót qua các lần tạo
lại container** — lần chạy sau không phải lập chỉ mục lại.

```bash
docker compose down          # dừng
docker compose down -v       # dừng và xoá luôn dữ liệu PostgreSQL
```

### Chạy backend không cần Docker

```bash
cd search-engine
./mvnw.cmd spring-boot:run      # Windows
# ./mvnw spring-boot:run        # Git Bash / WSL / macOS
```

Không có PostgreSQL thì nó tự lui về `data/seed-documents.json` — vẫn tìm kiếm
được ngay.

## Dựng lại corpus lớn từ đầu

```bash
cd search-engine

# 1. Crawl 5.000 trang từ 6 báo điện tử (~3-5 phút)
./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.crawler.MultiDomainCrawlRunner \
  -Dexec.args="5000 3 data/crawled-multi.json"

# 2. (Tuỳ chọn) Nạp vào PostgreSQL
docker compose up -d          # từ thư mục gốc
./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.PostgresImportRunner \
  -Dexec.args="data/crawled-multi.json"
```

## Chạy lại toàn bộ phần đánh giá

Mọi con số trong `docs/EVALUATION.md` và `docs/GIN-BASELINE.md` đều **tái
lập được** — seed ngẫu nhiên cố định, chạy lại ra đúng kết quả cũ.

```bash
cd search-engine

# Đánh giá chất lượng + ablation trọng số (known-item search)
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.eval.EvaluationRunner \
  -Dexec.args="data/crawled-multi.json 200"

# Đối chứng với chỉ mục GIN của PostgreSQL (cần Docker đang chạy)
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.storage.GinBaselineRunner -Dexec.args="200"

# Sinh pool để gán nhãn liên quan thủ công (cho nDCG/MAP)
MAVEN_OPTS=-Xmx4g ./mvnw.cmd compile exec:java \
  -Dexec.mainClass=com.vnsearch.eval.QrelsEvaluationRunner \
  -Dexec.args="pool data/crawled-multi.json"
```

## Kết quả nổi bật

**Chất lượng xếp hạng** (200 truy vấn known-item, corpus 5.011 trang):

| Cấu hình | MRR | Success@1 |
|---|---|---|
| TF-IDF thuần | 0,8541 | 78,0% |
| TF-IDF + title | 0,8715 | 81,0% |
| TF-IDF + PageRank + title (đang dùng) | 0,8758 | 81,5% |
| BM25 thuần | 0,8989 | 85,0% |
| **BM25 + PageRank + title** | **0,9093** | **85,5%** |

> **Cấu hình đang dùng KHÔNG phải cấu hình tốt nhất.** Mặc định là TF-IDF vì
> bộ trọng số được tinh chỉnh cho thang điểm TF-IDF; nhưng đo trên cùng 200
> truy vấn thì BM25 hơn 0,0335 MRR. Đổi một dòng trong
> `application.properties` (`app.ranking.scorer=bm25`) là chuyển sang cấu
> hình tốt hơn — xem `docs/EVALUATION.md` §5 để biết chênh lệch này **có ý
> nghĩa thống kê hay không**.

**Tốc độ truy vấn** (cùng máy, cùng corpus, cùng 200 truy vấn — A/B trước và
sau đợt tối ưu tính-trước-theo-truy-vấn):

| Cấu hình | Trước | Sau | Nhanh hơn |
|---|---|---|---|
| TF-IDF thuần | 3,58 ms | 3,10 ms | 1,2× |
| BM25 thuần | 3,43 ms | 2,20 ms | 1,6× |
| **TF-IDF + PR + title (đang dùng)** | **3,84 ms** | **1,59 ms** | **2,4×** |

Chất lượng **không đổi một chữ số thập phân nào** — đây thuần tuý là bỏ công
việc thừa. Cấu hình có title boost nhanh lên nhiều nhất vì chính nó lãng phí
nhiều nhất: nó dựng lại tập tiếng của truy vấn cho **từng tài liệu ứng viên**.

**Đối chứng với PostgreSQL GIN** (cùng corpus, cùng truy vấn):

| Tiêu chí | Chỉ mục tự cài | PostgreSQL GIN |
|---|---|---|
| MRR | **0,8758** | 0,8330 |
| Success@1 | **81,5%** | 79,5% |
| Success@10 | **97,5%** | 91,0% |
| Thời gian truy vấn | 1,62 ms | **1,24 ms** |

Chỉ mục tự cài **thắng về chất lượng tiếng Việt** (+5,1% MRR, nhờ tách từ ghép
bằng Longest Matching và chỉ mục kép có dấu/không dấu) nhưng vẫn **thua về tốc
độ** (chậm hơn 1,31 lần — trước đợt tối ưu là 2,9 lần). Cả hai cột đo trong
**cùng một lần chạy**, cùng máy, cùng 200 truy vấn. Phân tích nguyên nhân và
phần "so sánh này KHÔNG chứng minh điều gì" nằm trong `docs/GIN-BASELINE.md`.

**Lợi ích của ma trận thưa tăng theo quy mô** — đúng như lý thuyết dự đoán:

| Corpus | Cạnh đồ thị | Tỷ lệ thưa nnz/n² |
|---|---|---|
| 150 trang, 1 domain | 3.901 | 17,3% |
| **5.011 trang, 6 domain** | **239.691** | **0,95%** |

## Nguyên tắc bắt buộc của đồ án

- Mọi cấu trúc dữ liệu và giải thuật lõi (Trie, BloomFilter, LRUCache,
  MinHeap, UrlFrontier, SparseMatrix, InvertedIndex, PostingListMerger,
  TF-IDF, BM25, PageRank, các độ đo IR) đều **tự cài đặt**, không dùng thư
  viện làm thay.
- **PostgreSQL chỉ là kho lưu trữ** tài liệu thô. Chỉ mục đảo, Trie, LRU
  cache, PageRank vẫn nằm trong bộ nhớ và do đồ án tự cài — nếu đẩy việc
  tìm kiếm sang full-text search của CSDL thì toàn bộ nội dung chính của đồ
  án sẽ trở nên vô nghĩa. Chỉ mục GIN chỉ được dùng làm **đối chứng**.
- Được phép dùng: Java Collections cơ bản làm nền, Jsoup (chỉ để phân tích
  HTML), Jackson (JSON), Spring Web, JDBC.
