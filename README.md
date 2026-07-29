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

## Chay frontend (Electron + React)

```bash
cd browser-app
npm install
npm run dev
```

Cua so Electron se mo, dev server cua renderer chay o `http://localhost:5173`
(chi dung noi bo cho Vite HMR, khong can mo bang browser ngoai).

## Trang thai hien tai (PHASE 1)

Moi day chi la khung du an (skeleton): cay thu muc dung dac ta, cac class/
file trong package `datastructure`, `crawler`, `index`, `ranking`, `query`,
`controller`, `model` (backend) va `main`/`preload`/`renderer` (frontend)
la stub co Javadoc/comment TODO mo ta trach nhiem — CHUA cai thuat toan
that. Xem PHẦN 7 (ke hoach theo phase) trong yeu cau ban dau de biet lo
trinh day du tu PHASE 2 den PHASE 10.

## Nguyen tac quan trong (bat buoc theo dac ta)

- Moi cau truc du lieu/thuat toan loi (Trie, BloomFilter, LRUCache, MinHeap,
  UrlFrontier, SparseMatrix, InvertedIndex, PostingListMerger, TF-IDF,
  PageRank...) deu TU CAI DAT BANG TAY, khong dung thu vien lam thay.
- Duoc phep dung: Java Collections co ban lam primitive, Jsoup (chi de
  parse HTML), Jackson (JSON), Spring Web, Lombok.
