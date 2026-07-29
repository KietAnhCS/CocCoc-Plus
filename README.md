# VnSearch — Search Engine tu xay + Trinh duyet (do an DSA)

Do an mon Cau truc du lieu & Giai thuat: mot search engine tu crawl / tu
index / tu rank (khong dung Elasticsearch/Lucene/Solr...), tich hop lam
trang chu mac dinh cua mot trinh duyet desktop don gian (Electron + React).

Xem chi tiet kien truc o [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) va
bang tong hop cau truc du lieu/giai thuat o [docs/DSA-REPORT.md](docs/DSA-REPORT.md).

## Cau truc thu muc

```
search-engine/
├── search-engine/     # Backend: Spring Boot (Java 17, Maven wrapper)
└── browser-app/       # Frontend: Electron + React + TypeScript + Tailwind
```

## Yeu cau moi truong

- Java 17+ (khong can cai Maven he thong — du an dung Maven Wrapper)
- Node.js 20+ va npm

## Chay backend (Spring Boot)

```bash
cd search-engine
./mvnw.cmd spring-boot:run      # Windows PowerShell / cmd
# hoac: ./mvnw spring-boot:run  # Git Bash / WSL
```

Server chay o `http://localhost:8080`.

> Ghi chu phien ban: dac ta ban dau khoa Spring Boot 3.x. Tai thoi diem tao
> du an, start.spring.io da ngung ho tro sinh project 3.x (compatibility
> range hien tai la >=4.0.0), nen `pom.xml` duoc viet tay, pin
> `spring-boot-starter-parent` ban 3.3.4 (van con tren Maven Central) de
> giu dung yeu cau "Spring Boot 3.x" cua dac ta.

**Demo ngay khong can crawl lai**: repo di kem san
`search-engine/data/seed-documents.json` (~40 trang that da crawl san tu
vnexpress.net). Neu chua tung crawl (chua co `data/crawled-documents.json`
hay `data/index.json`), backend se TU DONG dung file seed nay khi khoi
dong — chi can chay `spring-boot:run` la co du lieu de tim kiem ngay.
Muon crawl du lieu moi, thuc thi `docs/api-examples.http` (muc 6-7) hoac
xem huong dan trong tai lieu do.

## Chay frontend (Electron + React)

```bash
cd browser-app
npm install
npm run dev
```

Cua so Electron se mo, dev server cua renderer chay o `http://localhost:5173`
(chi dung noi bo cho Vite HMR, khong can mo bang browser ngoai).

## Trang thai hien tai: HOAN THIEN CA 10 PHASE

- **PHASE 1-6 (backend)**: skeleton, 5 cau truc du lieu loi (Trie, BloomFilter,
  LRUCache, MinHeap, SparseMatrix), crawler da luong that (da crawl thuc
  te vnexpress.net), inverted index + query parser + posting list merger,
  TF-IDF + PageRank + ResultRanker, REST API day du (`/api/search`,
  `/api/suggest`, `/api/admin/*`). 105 unit/integration test, tat ca xanh.
- **PHASE 7-9 (frontend)**: quan ly tab bang WebContentsView + IPC, UI
  trinh duyet that (TabBar/AddressBar/NavigationButtons noi voi backend),
  historyStore (Stack tu cai) + bookmarkStore (Tree + Trie tu cai), tich
  hop tim kiem/goi y that voi backend (debounce, autocomplete, phan trang,
  che do debug).
- **PHASE 10**: tai lieu kien truc + bao cao DSA day du kem so lieu do
  thuc te, du lieu seed de demo khong can crawl lai.

Xem `docs/DSA-REPORT.md` de biet chi tiet tung cau truc du lieu, do phuc
tap, va so sanh hieu nang thuc te (Bloom Filter vs HashSet, two-pointer
vs `retainAll`...).

## Nguyen tac quan trong (bat buoc theo dac ta)

- Moi cau truc du lieu/thuat toan loi (Trie, BloomFilter, LRUCache, MinHeap,
  UrlFrontier, SparseMatrix, InvertedIndex, PostingListMerger, TF-IDF,
  PageRank...) deu TU CAI DAT BANG TAY, khong dung thu vien lam thay.
- Duoc phep dung: Java Collections co ban lam primitive, Jsoup (chi de
  parse HTML), Jackson (JSON), Spring Web, Lombok.
