# Bao cao Cau truc du lieu & Giai thuat (DSA-REPORT)

> Trang thai: khung bao cao tao o PHASE 1, cot "File" da tro dung vi tri
> stub hien tai. Cot Big-O va phan "Vi sao chon" se dien day du dan theo
> tung phase khi thuat toan duoc cai dat that (PHASE 2-6). Day la tai lieu
> quan trong nhat de nop giang vien.

## Bang tong hop

| Cau truc / Thuat toan | File | Dung de lam gi | Big-O thoi gian | Big-O bo nho |
|---|---|---|---|---|
| Trie | `datastructure/Trie.java` | Autocomplete goi y tu khoa | insert O(L), search O(L), suggest O(L + k·log(limit)) | *(PHASE 2)* |
| Bloom Filter | `datastructure/BloomFilter.java` | Kiem tra URL da crawl chua | add/mightContain O(k) | *(PHASE 2)* |
| LRU Cache | `datastructure/LRUCache.java` | Cache ket qua tim kiem / trang da ghe | get/put O(1) | *(PHASE 2)* |
| Min-Heap | `datastructure/MinHeap.java` | Lay top-K ket qua diem cao nhat | insert/extractMin O(log n), topK O(n·log k) | *(PHASE 2)* |
| Url Frontier (priority queue) | `datastructure/UrlFrontier.java` | Hang doi URL cho crawler, co uu tien | O(log n) moi thao tac | *(PHASE 3)* |
| Sparse Matrix (CSR) | `datastructure/SparseMatrix.java` | Ma tran lien ket web cho PageRank | multiply O(nnz) | *(PHASE 5)* |
| Inverted Index | `index/InvertedIndex.java` | Tra cuu tai lieu chua mot term | *(PHASE 4)* | *(PHASE 4)* |
| Posting List Merger | `query/PostingListMerger.java` | Giao/hop posting list nhieu term | intersect/union O(m+n) | *(PHASE 4)* |
| TF-IDF Scorer | `ranking/TfIdfScorer.java` | Diem lien quan tu-tai lieu | *(PHASE 5)* | *(PHASE 5)* |
| PageRank | `ranking/PageRankService.java` | Diem uy tin trang dua tren lien ket | O(so vong lap · nnz) | *(PHASE 5)* |
| Stack (tu cai, TypeScript) | `browser-app/.../historyStore.ts` | Back/forward cua trinh duyet | push/pop/peek O(1) | *(PHASE 8)* |
| Trie (TypeScript) | `browser-app/.../bookmarkStore.ts` | Tim kiem bookmark | *(PHASE 8)* | *(PHASE 8)* |

## Vi sao chon cau truc nay thay vi phuong an khac

*(Se dien chi tiet o PHASE 10, kem so lieu do thuc te. Vi du can co:)*

- Bloom Filter vs `HashSet<String>`: so sanh bo nho thuc te voi 1 trieu URL.
- Two-pointer intersect vs `HashSet.retainAll`: so sanh so phep so sanh /
  thoi gian chay tren posting list lon.
- Sparse Matrix (CSR) vs `double[n][n]`: n=10.000 trang -> ma tran dac ~800MB
  so voi bieu dien thua chi vai MB.
- Tu cai Doubly Linked List cho LRUCache thay vi `LinkedHashMap`: chung
  minh hieu ro co che O(1) o ca get va put.

## Do hieu nang (dien o PHASE 10)

- [ ] Thoi gian truy van trung binh (co/khong LRUCache).
- [ ] Cache hit rate thuc te.
- [ ] So vong lap PageRank hoi tu tren tap du lieu thuc.
