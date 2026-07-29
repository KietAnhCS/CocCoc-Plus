# Bao cao Cau truc du lieu & Giai thuat (DSA-REPORT)

> Tai lieu nay tong hop toan bo cau truc du lieu / giai thuat TU CAI DAT
> trong do an, kem do phuc tap ly thuyet va so lieu do thuc te (Phase 10).
> Moi class duoc liet ke deu co Javadoc chi tiet hon ngay trong ma nguon —
> bang duoi day la ban tom tat de tra cuu nhanh.

## Bang tong hop

| Cau truc / Thuat toan | File | Dung de lam gi | Big-O thoi gian | Big-O bo nho |
|---|---|---|---|---|
| Trie | `datastructure/Trie.java` | Autocomplete goi y tu khoa | insert O(L), search O(L), suggest O(L + m·log k) | O(tong so ky tu cac tu da insert) |
| Bloom Filter | `datastructure/BloomFilter.java` | Kiem tra URL da crawl chua | add/mightContain O(k), k = so ham bam (hang so nho) | O(m) bit, m tinh theo cong thuc chuan |
| LRU Cache | `datastructure/LRUCache.java` | Cache ket qua tim kiem / trang da ghe | get/put O(1) | O(capacity) |
| Min-Heap | `datastructure/MinHeap.java` | Lay top-K ket qua diem cao nhat | insert/extractMin O(log n), topK O(n·log k) | O(n) |
| Url Frontier (priority queue tren MinHeap) | `datastructure/UrlFrontier.java` | Hang doi URL cho crawler, co uu tien + politeness | addUrl O(log n), nextUrl O(d·log n) (d = so URL bi tam gac do politeness) | O(n) |
| Sparse Matrix (adjacency list) | `datastructure/SparseMatrix.java` | Ma tran lien ket web cho PageRank | set O(1) amortized, multiply O(nnz) | O(nnz) |
| Inverted Index | `index/InvertedIndex.java` | Tra cuu tai lieu chua mot term | addDocument O(L), getPostings/getPositions O(1)/O(log n) | O(tong so cap (term, doc)) |
| Posting List Merger | `query/PostingListMerger.java` | Giao/hop posting list nhieu term, phrase match | intersect/union O(m+n), intersectAll O(sum) voi sap xep shortest-first | O(ket qua) |
| TF-IDF Scorer | `ranking/TfIdfScorer.java` | Diem lien quan tu-tai lieu (cosine) | O(q·log d) — q = so term truy van, d = do dai posting list dai nhat (binary search) | O(1) ngoai du lieu index |
| PageRank | `ranking/PageRankService.java` | Diem uy tin trang dua tren lien ket | O(iterations · (nnz + N)) | O(N + nnz) |
| ResultRanker (snippet) | `ranking/ResultRanker.java` | Ket hop diem, sinh snippet | rank O(c·log topN) (c = so ung vien), snippet O(so tu trong bodyText) | O(c) |
| Stack (tu cai, TypeScript) | `browser-app/.../lib/Stack.ts` | Back/forward cua trinh duyet | push/pop/peek O(1) | O(do sau lich su) |
| Trie (TypeScript) | `browser-app/.../lib/BookmarkTrie.ts` | Tim kiem bookmark theo tien to | insert O(L), searchByPrefix O(L + m) | O(tong so ky tu tieu de bookmark) |

*(L = do dai chuoi/tu; n, m = kich thuoc cau truc/danh sach lien quan; N =
so tai lieu; nnz = so phan tu khac 0 cua ma tran thua; k = tham so cau
hinh nho, hang so.)*

## Vi sao chon cau truc nay thay vi phuong an khac (co do luong thuc te)

### 1. Bloom Filter vs `HashSet<String>` — dedupe URL khi crawl

Do thuc te voi **1.000.000 URL**, `expectedItems=1_000_000, falsePositiveRate=0.01`:

| Cau truc | Bo nho |
|---|---|
| BloomFilter (ly thuyet, `m/8` byte) | **~1.170 KB (~1,1 MB)** |
| `HashSet<String>` (do bang heap delta thuc te, cung 1M URL) | **~110.932 KB (~108 MB)** |

→ HashSet ton **~95 lan** bo nho so voi Bloom Filter o cung quy mo — vi
HashSet phai luu nguyen ven tung chuoi URL (cong them overhead cua
`String`, entry cua HashMap ben trong, con tro...), trong khi Bloom
Filter chi luu vai bit tren moi phan tu, doc lap voi do dai chuoi goc.
Danh doi: co ty le false positive nho (đa cau hinh 1%) nhung KHONG BAO
GIO false negative — chap nhan duoc cho bai toan "co the da crawl hay
chua", vi false positive toi da chi khien bo lo mot vai trang (khong gay
loi logic).

### 2. Two-pointer `intersect` vs `HashSet.retainAll` — giao posting list

Do thuc te voi 2 danh sach da sap xep, **500.000 phan tu moi ben**, ket
qua giao ~250.000 phan tu, trung binh 5 lan chay:

| Cach lam | Thoi gian trung binh/lan |
|---|---|
| Two-pointer `PostingListMerger.intersect` | **~10,0 ms** |
| `HashSet.retainAll` (khong tinh chi phi xay HashSet) | ~15,5 ms (**cham hon ~55%**) |
| `HashSet.retainAll` (tinh ca chi phi xay 2 HashSet — sat voi thuc te vi posting list la `List` moi truy van, khong co san HashSet) | ~27,0 ms (**cham hon ~2,7 lan**) |

→ Two-pointer thang o ca 2 kich ban vi: (1) khong co overhead tinh hash +
xu ly va cham cua HashMap/HashSet, (2) tan dung truc tiep tinh chat "da
sap xep" von co cua posting list (bat bien do InvertedIndex dam bao) ma
khong can cau truc trung gian nao. Trong thuc te he thong nay, posting
list la `List<Posting>` lay thang tu index, nen phai tinh CA chi phi xay
HashSet moi lan — cot thu 3 la so sanh cong bang nhat.

### 3. Sparse Matrix (adjacency list) vs `double[n][n]` — do thi lien ket cho PageRank

- Kich ban ly thuyet (n = 10.000 trang, dac trung bai toan lon): ma tran
  dac can `10.000 × 10.000 × 8 byte = 800.000.000 byte (~763 MB)`, trong
  khi bieu dien thua (adjacency list, moi Entry ~16 byte) voi so lien ket
  thuc te (vai chuc/trang) chi ton vai MB.
- Do THUC TE tren corpus da crawl (**n = 150 trang vnexpress.net**):
  `nnz = 3.901` lien ket noi bo (giua cac trang da crawl). Ma tran dac can
  `150 × 150 × 8 = 180.000 byte (~176 KB)`; adjacency list can
  `~62.416 byte (~61 KB)` — ty le thua do duoc la **17,3%**
  (nnz/n²). O quy mo nho, mot website tin tuc lien ket cheo rat nhieu nen
  ty le thua chua "an tuong" nhu kich ban 10.000 trang toan-web (noi cac
  trang thuoc nhieu domain khac nhau it lien ket cheo hon nhieu, ty le
  thua thuc te thuong duoi 0,01%) — day la minh chung ro rang la loi ich
  cua sparse matrix TANG THEO quy mo corpus, dung nhu du doan ly thuyet.

### 4. Tu cai Doubly Linked List cho `LRUCache` thay vi `LinkedHashMap`

`LinkedHashMap` (voi `accessOrder=true` va override `removeEldestEntry`)
co the lam LRU cache "mien phi", nhung tu viet Doubly Linked List + sentinel
node (thay vi dung lop nay) buoc phai hieu ro CO CHE ben trong: vi sao
di chuyen 1 node len dau la O(1) (chi doi 4 con tro `prev`/`next`, khong
can duyet danh sach), vi sao can 2 sentinel de khong phai kiem tra
`null` rieng cho truong hop them/xoa o dau hoac cuoi. Day chinh la yeu
cau cot loi cua do an DSA: chung minh HIEU BAN CHAT, khong chi biet goi
API co san.

### 5. Two-pointer trong `PostingListMerger.intersectAll` — sap xep shortest-first

Khi truy van nhieu term, sap xep cac posting list theo do dai TANG DAN
truoc khi intersect tuan tu: goi A la ket qua giao sau k buoc, luon co
`|A| <= min(cac list da xet)`. Bat dau tu list NGAN NHAT giup `|A|` nho
ngay tu dau, nen cac buoc intersect ke tiep (O(|A| + |list ke tiep|)) re
hon dang ke so voi bat dau tu list dai nhat — dac biet loi khi 1 term
hiem (document frequency nho) tron voi nhieu term pho bien.

## Do hieu nang (do thuc te tren corpus 40-150 trang vnexpress.net)

- **Thoi gian truy van trung binh (cache MISS, query da dang, khong lap
  lai)**: dao dong 2,6 ms – 33 ms, trung binh ~14,4 ms/truy van
  (10 truy van khac nhau: "công nghệ", "thể thao", "kinh doanh", v.v.)
- **Cache hit rate thuc te** (do qua `/api/admin/stats` sau 1 truy van
  moi + 20 lan lap lai 1 truy van khac): **90,48%** (19 hit / 21 lan goi) —
  cache hit tra ve trong ~3 ms so voi ~52 ms cho lan dau (cache miss),
  nhanh hon **~15 lan**.
- **So vong lap PageRank hoi tu** (nguong `1e-6`, toi da 100 vong):
  - Corpus 150 trang thuc (crawl that tu vnexpress.net): **44 vong lap**.
  - Corpus 40 trang (seed data rut gon): **20 vong lap**.
  - Do thi 6 node tu tao (test don vi): 1–28 vong lap tuy cau truc lien
    ket (chu trinh doi xung hoi tu ngay lap 1; do thi bat doi xung voi
    dangling node can nhieu vong lap hon).
- **Ty le thua do thi lien ket**: xem muc 3 o tren (17,3% tren 150 trang
  thuc te).

Tat ca so lieu tren co the tai tao lai bang cach chay backend
(`./mvnw.cmd spring-boot:run`) roi goi `docs/api-examples.http`, hoac xem
lai cac test o `PageRankServiceTest`/`TfIdfScorerTest` de kiem chung ket
qua tren du lieu nho, biet truoc dap so.
