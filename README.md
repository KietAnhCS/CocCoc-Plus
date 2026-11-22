# CocCoc-Plus — a Vietnamese search engine built on hand-written DSA

A small Vietnamese search engine: a crawler, an inverted index, ranking, and a
mini browser to query it. Two parts only:

- **`backend/`** — one Spring Boot process (monolith) on port **8080**.
- **`desktop-app/`** — the browser (Electron + React + TypeScript).

Every core data structure and algorithm is **hand-written**, with no
off-the-shelf search library. Spring is only used to expose them over REST.

| Data structure / algorithm | Where | Used for |
|---|---|---|
| Inverted index + VByte/delta compression | `index/InvertedIndex`, `CompressedPostings`, `VByteCodec` | Term → posting list |
| Vietnamese word segmenter (max-weight) | `index/MaxWeightSegmenter`, `VietnameseTokenizer` | Tokenizing queries and documents |
| Trie / SyllableTrie | `datastructure/Trie`, `SyllableTrie` | Prefix suggestions, dictionary lookup |
| Bloom filter | `datastructure/BloomFilter` | "URL already seen?" in the crawler |
| MinHeap | `datastructure/MinHeap` | Top-K results |
| LRU cache | `datastructure/LRUCache` | Query result cache |
| Sparse matrix + PageRank | `datastructure/SparseMatrix`, `ranking/PageRankService` | Link-based ranking |
| TF-IDF, BM25 (+ decorators) | `ranking/` | Relevance scoring |
| Query parser (AST: AND / OR / NOT / phrase) | `query/` | Boolean and phrase queries |
| Posting-list merge | `query/PostingListMerger` | Intersect / union / difference |
| Two-tier URL frontier | `crawler/frontier/` | Politeness and priority in the crawler |
| robots.txt parser, URL canonicalizer | `crawler/` | Crawling rules |

All paths are under `backend/src/main/java/com/vnsearch/`.

```mermaid
flowchart LR
    APP["desktop-app<br/>(Electron)"] -->|HTTP :8080| API["backend<br/>REST controllers"]
    API --> ENGINE["SearchEngineFacade"]
    ENGINE --> IDX["InvertedIndex<br/>VByte + delta"]
    ENGINE --> RANK["TF-IDF / BM25 · PageRank<br/>MinHeap top-K · LRU cache"]
    ENGINE --> SUG["Trie suggestions"]
    CRAWL["crawl.bat<br/>crawler (frontier, Bloom filter)"] -->|data/crawled-documents.json| ENGINE
```

---

## Hướng dẫn cài đặt

### Cách 1 — Chạy bằng `start.bat` (khuyên dùng, Windows)

**Cần cài trước:**

| Phần mềm | Dùng để | Tải |
|---|---|---|
| Docker Desktop (đang chạy) | Build và chạy backend | https://www.docker.com/products/docker-desktop |
| Node.js 22+ | Chạy giao diện Electron | https://nodejs.org |

**Các bước:**

```bash
git clone https://github.com/KietAnhCS/CocCoc-Plus.git
cd CocCoc-Plus
start.bat
```

`start.bat` tự làm 3 việc: build và chạy backend trong Docker (chờ đến khi
sẵn sàng), chạy `npm install` + tải Electron ở lần đầu, rồi mở cửa sổ trình duyệt.
Lần đầu mất vài phút. Kiểm tra backend:

```bash
curl "http://localhost:8080/api/health"
curl "http://localhost:8080/api/search?q=máy+tính&size=3"
```

Tắt toàn bộ: `stop.bat`.

> Không có Node.js? Docker còn chạy sẵn giao diện qua noVNC. Chạy
> `docker compose up -d --build`, rồi mở http://localhost:6080.

### Cách 2 — Chạy thủ công (không cần Docker)

Cần **JDK 17+** và **Node.js 22+**.

```bash
# Cửa sổ 1: backend (chạy trong thư mục backend/ vì đường dẫn data là tương đối)
cd backend
./mvnw -B clean package -DskipTests      # Windows: mvnw.cmd
java -jar target/vnsearch-0.0.1-SNAPSHOT.jar

# Cửa sổ 2: giao diện
cd desktop-app
npm install
npm run dev
```

Chưa crawl thì backend dùng `backend/data/seed-documents.json` (bộ mẫu nhỏ
có sẵn trong repo), nên clone về là tìm kiếm được ngay.

### Crawl dữ liệu riêng

Crawler chạy trong Docker, mặc định **nối tiếp** corpus đang có:

```bash
crawl.bat                                   # 10.000 trang, độ sâu 4
crawl.bat 5000 3                            # 5.000 trang, độ sâu 3
crawl.bat 500 2 data/try.json --fresh       # xoá và crawl lại từ đầu
```

Crawl xong, script xoá `backend/data/index.json`. Chạy lại `start.bat` để lập
chỉ mục từ corpus mới. `crawl-stats.bat` thống kê corpus (số trang, link, ảnh, dung lượng).

### Lỗi thường gặp

| Triệu chứng | Cách xử lý |
|---|---|
| `Chua cai Docker Desktop` | Cài Docker Desktop và mở nó lên trước khi chạy `start.bat` |
| Backend không lên / treo ở bước 1 | Kiểm tra cổng 8080 có bị chiếm không; xem log bằng `docker compose logs backend` |
| `Khong tai duoc Electron` | Lỗi mạng. Chạy lại `start.bat`, hoặc `node node_modules\electron\install.js` trong `desktop-app/` |
| Kết quả cũ sau khi crawl | Xoá `backend/data/index.json` rồi chạy lại `start.bat` |

---

## API

| Endpoint | Description |
|---|---|
| `GET /api/search?q=&page=&size=` | Search |
| `GET /api/suggest?prefix=&limit=` | Prefix suggestions (Trie). Note: `prefix`, **not** `q` |
| `GET /api/images?q=&page=&size=` | Image search |
| `GET /api/feed?seed=&page=&size=` | Browse the index without a query. Same `seed` ⇒ same order |
| `GET /api/health` | Liveness. Returns `503` when the index is empty |

Browsing history, search history, settings and bookmarks live in the browser's
`localStorage`; the backend has no accounts and stores nothing per user.

---

## Configuration

Set through environment variables (defaults in
`backend/src/main/resources/application.properties`):

| Variable | Default | Effect |
|---|---|---|
| `APP_RANKING_SCORER` | `tfidf` | `tfidf` or `bm25` |
| `SERVER_PORT` | `8080` | Backend port |
| `APP_CRAWLER_DATA_PATH` | `data/crawled-documents.json` | Corpus file |
| `APP_INDEX_PATH` | `data/index.json` | Saved index (rebuilt if missing) |
| `APP_SEARCH_CACHE` | `200` | LRU cache size |

---

## Development

```bash
cd backend && ./mvnw test                                        # ~520 JUnit tests
cd desktop-app && npm run typecheck && npm run lint && npm test  # Vitest
```

`RankingQualityTest` guards search quality: it fails if ranking on the seed
corpus gets worse, even when every other test stays green.

## Repository layout

```
backend/                     Single Maven module (Spring Boot)
  src/main/java/com/vnsearch/
    datastructure/           Trie, SyllableTrie, BloomFilter, MinHeap, LRUCache, SparseMatrix
    index/                   Inverted index, VByte compression, Vietnamese segmenter
    query/                   Query parser (AST), posting-list merging, filters
    ranking/                 TF-IDF, BM25, PageRank, decorators, snippets
    crawler/                 Downloader, robots.txt, URL frontier, Bloom-filter dedup
    storage/                 JSON document store
    eval/                    Search quality harness
    service/                 SearchEngineFacade — wires everything together
    controller/              REST endpoints
  data/                      Corpus, index, images
  tools/build_dict.py        Regenerates the Vietnamese dictionary
desktop-app/                 Mini browser (Electron + React + TypeScript)
docs/                        Report and documentation
```

The Vietnamese dictionary is generated from
[`coccoc-tokenizer`](https://github.com/coccoc/coccoc-tokenizer) (LGPL-3.0),
which is **not** vendored here — clone it separately if you need to regenerate
`vietnamese-words.txt`.
