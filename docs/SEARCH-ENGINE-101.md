# Search Engine hoạt động thế nào — giải thích từ đầu

> Tài liệu này giải thích **toàn bộ kiến thức thuật toán** đằng sau một máy
> tìm kiếm, theo đúng thứ tự dữ liệu chảy qua hệ thống. Khác với
> `ALGORITHMS.md` (bảng tra cứu nhanh) và `DSA-REPORT.md` (báo cáo độ phức
> tạp), tài liệu này tập trung vào **tại sao** mỗi thuật toán tồn tại và
> **vấn đề gì** nó giải quyết.
>
> Mọi ví dụ tính tay đều dùng số liệu thật của dự án: **5.011 tài liệu,
> 136.768 term, độ dài trung bình 1.043 token**.

---

## Mục lục

1. [Bài toán gốc](#1-bài-toán-gốc)
2. [Thu thập dữ liệu — Crawler](#2-thu-thập-dữ-liệu--crawler)
3. [Tách từ tiếng Việt — Tokenizer](#3-tách-từ-tiếng-việt--tokenizer)
4. [Chỉ mục đảo — Inverted Index](#4-chỉ-mục-đảo--inverted-index)
5. [Xử lý truy vấn — Query Processing](#5-xử-lý-truy-vấn--query-processing)
6. [Xếp hạng: TF-IDF](#6-xếp-hạng-tf-idf)
7. [Xếp hạng: BM25](#7-xếp-hạng-bm25)
8. [Xếp hạng: PageRank](#8-xếp-hạng-pagerank)
9. [Kết hợp điểm và lấy top-K](#9-kết-hợp-điểm-và-lấy-top-k)
10. [Trình bày kết quả — Snippet](#10-trình-bày-kết-quả--snippet)
11. [Tăng tốc — Cache và Autocomplete](#11-tăng-tốc--cache-và-autocomplete)
12. [Đo chất lượng — phần hầu hết mọi người bỏ qua](#12-đo-chất-lượng--phần-hầu-hết-mọi-người-bỏ-qua)
13. [Điều gì vỡ ở quy mô lớn](#13-điều-gì-vỡ-ở-quy-mô-lớn)

---

## 1. Bài toán gốc

Người dùng gõ `công nghệ`. Hệ thống có 5.011 tài liệu. Cần trả về 10 tài
liệu **liên quan nhất**, trong vài chục mili giây.

Cách ngây thơ: duyệt cả 5.011 tài liệu, đếm xem tài liệu nào chứa từ khoá.
Với 5.011 tài liệu × 1.043 token = **5,2 triệu phép so sánh** cho mỗi truy
vấn. Ở quy mô Google (hàng trăm tỷ trang) thì hoàn toàn bất khả thi.

Toàn bộ ngành Information Retrieval xoay quanh việc né phép duyệt đó. Có
ba câu hỏi lớn:

| Câu hỏi | Trả lời bằng |
|---|---|
| Làm sao tìm nhanh tài liệu **chứa** từ khoá? | Chỉ mục đảo (mục 4) |
| Trong số đó, tài liệu nào **liên quan nhất**? | TF-IDF, BM25, PageRank (mục 6–8) |
| Làm sao **biết** mình xếp hạng đúng? | Các độ đo IR (mục 12) |

Pipeline đầy đủ:

```
Web → Crawler → Tokenizer → Inverted Index
                                  ↓
Người dùng → Query Parser → Tìm ứng viên → Xếp hạng → Snippet → Kết quả
```

---

## 2. Thu thập dữ liệu — Crawler

### 2.1. Web là một đồ thị

Trang web là **đỉnh**, siêu liên kết là **cạnh có hướng**. Crawl chính là
duyệt đồ thị.

**Vì sao dùng BFS chứ không DFS?** Đồ thị web sâu gần như vô hạn — DFS sẽ
lao xuống một nhánh và không bao giờ quay lên. BFS duyệt theo từng lớp độ
sâu, nên với ngân sách hữu hạn ta thu được các trang **gần seed nhất**,
vốn thường là trang quan trọng nhất (trang chủ, trang chuyên mục).

### 2.2. Hàng đợi không phải FIFO thuần

BFS chuẩn dùng hàng đợi FIFO. Nhưng không phải trang nào cũng đáng giá như
nhau, nên ta thay bằng **hàng đợi ưu tiên**:

```
điểm ưu tiên = −(độ sâu × 2) + min(backlink, 50) × 0,5 + (5 nếu là .vn)
```

Cài trong `UrlFrontier.java`. Có một mẹo đáng chú ý: ta có `MinHeap` (lấy
phần tử **nhỏ nhất**) nhưng cần lấy phần tử **ưu tiên cao nhất**. Giải
pháp: sắp xếp heap theo `−priority`. Phần tử ưu tiên cao nhất có
`−priority` nhỏ nhất nên vẫn ra đầu tiên. Kỹ thuật này biến min-heap thành
max-heap mà không phải viết lại cấu trúc.

### 2.3. Politeness — và vì sao nó định hình cả kiến trúc

Nếu bắn 100 request/giây vào một website, bạn đang tấn công DoS họ. Quy
tắc: **mỗi host tối đa 1 request/giây**.

Hệ quả quan trọng: **thông lượng tối đa = số host đang crawl đồng thời**.
Muốn 400 trang/giây thì phải có ít nhất 400 host được crawl song song. Dự
án này có 52 host → trần lý thuyết 52 trang/giây, thực đo 26,2.

Đây cũng là lý do phải **tách hàng đợi theo host**. Với một heap toàn cục,
khi phần tử đầu thuộc host đang bị hoãn, ta phải rút nó ra, gác lại, rút
tiếp... Trường hợp xấu nhất phải rút cạn cả heap rồi nhét lại:
**O(n log n) cho mỗi lần lấy một URL**.

Giải pháp là `Map<host, MinHeap>` — mỗi host một hàng đợi riêng, chỉ quét
qua các host (D nhỏ) rồi lấy một phần tử: **O(D + log n_d)**. Đây chính là
mô hình "back queue" của crawler **Mercator** (Heydon & Najork, 1999).

### 2.4. Khử trùng lặp URL — Bloom Filter

Crawl 5.011 trang thu về **394.940 outlink**. Trước khi fetch phải hỏi:
"URL này crawl chưa?"

`HashSet<String>` trả lời được, nhưng phải lưu nguyên chuỗi URL. Đo thực
tế với 1 triệu URL:

| Cấu trúc | Bộ nhớ |
|---|---|
| `HashSet<String>` | **~108 MB** |
| Bloom Filter | **~1,1 MB** |

Chênh **95 lần**. Bloom Filter hoạt động thế nào:

- Một mảng bit kích thước `m`, ban đầu toàn 0
- Thêm phần tử: băm nó bằng `k` hàm băm khác nhau, **bật** `k` bit tương ứng
- Kiểm tra: băm lại, nếu **có bất kỳ bit nào bằng 0** → chắc chắn chưa thêm

Điểm mấu chốt về tính đúng đắn:

> **Không bao giờ có false negative.** Vì `add()` chỉ **bật** bit, không
> bao giờ tắt. Bit đã bật bởi X sẽ vẫn bật khi kiểm tra lại X.
>
> **Có thể có false positive.** Nhiều chuỗi khác nhau có thể vô tình bật
> trùng bộ bit.

Với bài toán crawl, false positive chỉ khiến bỏ lỡ vài trang — chấp nhận
được. False negative mới nguy hiểm (crawl lại trang đã crawl → vòng lặp vô
hạn), và điều đó **không thể xảy ra**.

Công thức chọn tham số tối ưu:

```
m = ⌈−n·ln(p) / (ln2)²⌉      (số bit)
k = round((m/n)·ln2)          (số hàm băm)
```

Mẹo cài đặt: thay vì viết `k` hàm băm riêng, dùng **double hashing**
(Kirsch & Mitzenmacher): `h_i(x) = h₁(x) + i·h₂(x) mod m`. Chỉ cần 2 hàm
băm thật, phần còn lại là tổ hợp tuyến tính.

### 2.5. Chuẩn hoá URL

`https://a.com` và `https://a.com/` là **cùng một trang** nhưng là **hai
chuỗi khác nhau**. Không chuẩn hoá thì Bloom Filter coi chúng khác nhau và
crawl cả hai. Dự án này đã dính đúng lỗi đó: **23 cặp trang trùng** trong
phiên crawl đầu tiên.

Các phép chuẩn hoá **an toàn** (không đổi tài nguyên được trỏ tới):

| Phép | Ví dụ |
|---|---|
| Bỏ fragment | `a.com/x#phan-2` → `a.com/x` |
| Hạ chữ thường scheme + host | `HTTPS://A.COM/X` → `https://a.com/X` |
| Bỏ cổng mặc định | `a.com:443/x` → `a.com/x` |
| Bỏ dấu `/` cuối | `a.com/tin/` → `a.com/tin` |

Lưu ý: **đường dẫn có phân biệt hoa thường** (RFC 3986) nên không được hạ
chữ thường phần path. Cũng **không** đụng vào query string — bỏ hay đảo
thứ tự tham số có thể làm trang trả về khác đi.

### 2.6. robots.txt

Chuẩn Robots Exclusion Protocol. Điểm dễ sai: khi nhiều luật cùng khớp,
**luật có đường dẫn dài nhất thắng**:

```
Disallow: /admin
Allow: /admin/public
```
→ `/admin/public/x` được phép (luật `Allow` dài hơn), `/admin/secret` bị cấm.

---

## 3. Tách từ tiếng Việt — Tokenizer

### 3.1. Vấn đề riêng của tiếng Việt

Tiếng Anh tách từ bằng khoảng trắng: `computer science` → 2 từ, mỗi từ có
nghĩa riêng.

Tiếng Việt **không** như vậy. `máy tính` là **một từ** (computer), nhưng
viết thành 2 tiếng cách nhau bởi khoảng trắng. Tách theo khoảng trắng sẽ
được `máy` (machine) và `tính` (to calculate) — sai hoàn toàn về nghĩa.

Hệ quả trực tiếp cho tìm kiếm: nếu index `máy` và `tính` riêng lẻ, thì truy
vấn `máy tính` sẽ khớp cả bài viết về "máy giặt" có chữ "tính tiền".

### 3.2. Thuật toán Longest Matching

Thuật toán tham lam kinh điển:

```
Tại mỗi vị trí i:
    Thử ghép 4 tiếng liên tiếp → có trong từ điển không?
    Không → thử 3 tiếng
    Không → thử 2 tiếng
    Không → lấy 1 tiếng
    Nhảy tới sau cụm vừa ghép
```

Ví dụ với `khoa học máy tính rất hay`:

| Vị trí | Thử | Kết quả |
|---|---|---|
| `khoa` | `khoa học máy tính` (4) | ✅ có trong từ điển → token `khoa_học_máy_tính` |
| `rất` | `rất hay` (2) | ❌ → token đơn `rất` |

Độ phức tạp O(n × 4) = **O(n)** vì 4 là hằng số.

> **Hạn chế thật của dự án:** từ điển chỉ có **154 mục**. `máy tính` có
> trong đó nên được ghép đúng; `bóng đá` **không có** nên bị tách thành
> `bóng` + `đá`. Từ điển tiếng Việt đầy đủ cần 30.000–70.000 mục. Đây là
> trần chất lượng của toàn hệ thống.

### 3.3. Chuẩn hoá Unicode

Chữ `ế` có **hai cách** biểu diễn trong Unicode:

- **NFC** (dựng sẵn): 1 ký tự `U+1EBF`
- **NFD** (tổ hợp): `e` + dấu mũ + dấu sắc = 3 ký tự

Hai chuỗi trông y hệt nhau trên màn hình nhưng **khác nhau về byte**. Không
chuẩn hoá thì cùng một từ tạo ra 2 khoá khác nhau trong chỉ mục.

Giải pháp: luôn chuẩn hoá về NFC trước khi xử lý.

### 3.4. Sinh bản không dấu

Người Việt hay gõ không dấu trên bàn phím quốc tế: `may tinh` thay vì
`máy tính`. Cách xử lý:

1. Chuẩn hoá về **NFD** (tách dấu ra thành ký tự riêng)
2. Xoá mọi ký tự thuộc nhóm `\p{M}` (combining mark)
3. Riêng `đ`/`Đ` phải xử lý thủ công — nó là **chữ cái Latin độc lập**,
   không phải `d` + dấu, nên NFD không tách được

Mỗi token được index **hai lần**: bản có dấu và bản không dấu, cùng trỏ tới
một posting list.

### 3.5. Loại từ dừng

Từ như `của`, `và`, `là` xuất hiện trong gần như mọi tài liệu nên không
mang thông tin phân biệt. Loại bỏ chúng giúp chỉ mục nhỏ hơn và truy vấn
nhanh hơn.

---

## 4. Chỉ mục đảo — Inverted Index

### 4.1. Ý tưởng cốt lõi

Chỉ mục **xuôi** (forward index) là thứ tự tự nhiên:

```
doc1 → [máy_tính, xách_tay, giá, rẻ]
doc2 → [công_nghệ, máy_tính, mới]
```

Muốn tìm tài liệu chứa `máy_tính` phải duyệt hết mọi tài liệu.

Chỉ mục **đảo** lật ngược lại:

```
máy_tính  → [doc1, doc2]
xách_tay  → [doc1]
công_nghệ → [doc2]
```

Giờ tra `máy_tính` là một phép tra `HashMap`: **O(1)**.

Đây là ý tưởng nền tảng của mọi máy tìm kiếm. Tên gọi "đảo" chính vì nó lật
quan hệ tài liệu→từ thành từ→tài liệu.

### 4.2. Posting list chứa gì

Mỗi mục trong danh sách (gọi là **posting**) không chỉ có `docId`:

```java
record Posting(int docId, int termFrequency, List<Integer> positions)
```

- `termFrequency` — từ xuất hiện bao nhiêu lần trong tài liệu (dùng cho TF-IDF)
- `positions` — xuất hiện ở **vị trí thứ mấy** (dùng cho tìm cụm từ)

### 4.3. Bất biến quan trọng nhất: posting list luôn sắp xếp theo docId

Đây là chi tiết dễ bỏ qua nhưng **quyết định toàn bộ hiệu năng phía sau**.

Vì `addDocument()` luôn được gọi theo thứ tự docId tăng dần và chỉ **append**
vào cuối, nên posting list tự nhiên đã sắp xếp — **không tốn một phép sort
nào**.

Bất biến này mở khoá hai thứ:

1. **Giao posting list bằng two-pointer O(m+n)** thay vì phải sort lại
   O(n log n) mỗi truy vấn (mục 5.2)
2. **Binary search O(log n)** để tra tần suất/vị trí của một tài liệu cụ
   thể, thay vì quét tuyến tính (dùng trong TF-IDF và tìm cụm từ)

> Bài học tổng quát: chọn đúng **bất biến** khi xây dựng cấu trúc dữ liệu
> thường có giá trị hơn tối ưu thuật toán về sau.

---

## 5. Xử lý truy vấn — Query Processing

### 5.1. Phân tích truy vấn

Truy vấn `"trình duyệt web" máy tính -giá` được tách thành:

| Thành phần | Giá trị | Ý nghĩa |
|---|---|---|
| `phrases` | `[[trình_duyệt_web]]` | phải xuất hiện **liên tiếp** |
| `mustTerms` | `[máy_tính]` | phải có (AND ngầm định) |
| `excludedTerms` | `[giá]` | tài liệu chứa từ này bị loại |

Điểm then chốt: truy vấn phải được tokenize bằng **chính** tokenizer đã
dùng lúc index. Nếu lúc index tạo ra `máy_tính` mà lúc truy vấn tạo ra
`máy` + `tính` thì không bao giờ khớp.

### 5.2. Giao posting list bằng two-pointer

Truy vấn `máy tính công nghệ` cần các tài liệu có **cả hai** term. Tức là
lấy **giao** của hai posting list.

Vì cả hai đã sắp xếp, dùng kỹ thuật **two-pointer** (giống bước merge của
merge sort):

```
A: [1, 3, 5, 7, 9]
B: [3, 5, 8]

i=0,j=0: A[0]=1 < B[0]=3  → i++
i=1,j=0: A[1]=3 = B[0]=3  → ghi 3, i++, j++
i=2,j=1: A[2]=5 = B[1]=5  → ghi 5, i++, j++
i=3,j=2: A[3]=7 < B[2]=8  → i++
i=4,j=2: A[4]=9 > B[2]=8  → j++
j hết → dừng

Kết quả: [3, 5]
```

Mỗi phần tử được xét đúng **một lần**: **O(m+n)**.

**Vì sao không dùng `HashSet.retainAll`?** Đo thực tế với 2 danh sách
500.000 phần tử:

| Cách làm | Thời gian |
|---|---|
| Two-pointer | **~10,0 ms** |
| `HashSet.retainAll` (không tính chi phí dựng HashSet) | ~15,5 ms |
| `HashSet.retainAll` (tính cả chi phí dựng) | ~27,0 ms |

Two-pointer thắng vì: không có chi phí băm và xử lý va chạm, tận dụng trực
tiếp tính đã-sắp-xếp có sẵn, và không cần cấp phát cấu trúc trung gian.

### 5.3. Tối ưu shortest-first

Khi truy vấn có nhiều term, **thứ tự giao rất quan trọng**.

Gọi `A` là kết quả giao sau k bước. Luôn có `|A| ≤ min(các list đã xét)`.
Vậy nên bắt đầu từ list **ngắn nhất** để `|A|` nhỏ ngay từ đầu.

Ví dụ: `iPhone` (df=5) và `của` (df=4000)

| Thứ tự | Chi phí |
|---|---|
| Ngắn trước: `5 ∩ 4000` | duyệt 4005 phần tử, kết quả ≤ 5 |
| Dài trước — không có lợi gì | vẫn 4005, nhưng các bước sau tốn hơn |

Với 3+ term, khác biệt tích luỹ rất lớn. Đây là lý do
`intersectAll()` sắp xếp posting list theo độ dài tăng dần trước.

### 5.4. Tìm theo cụm từ

`"trình duyệt web"` yêu cầu 3 từ xuất hiện **liên tiếp đúng thứ tự**. Đây
là lúc `positions` phát huy tác dụng:

```
Trong doc5:
  trình  xuất hiện ở vị trí [2, 17]
  duyệt  xuất hiện ở vị trí [3, 40]
  web    xuất hiện ở vị trí [4, 41]

Thử start = 2: cần duyệt ở 3 ✅, web ở 4 ✅ → KHỚP
```

Thuật toán: với mỗi vị trí của từ đầu tiên, kiểm tra từ thứ i có nằm ở
`start + i` hay không.

---

## 6. Xếp hạng: TF-IDF

Đã tìm được 1.639 tài liệu chứa `công nghệ`. Giờ phải xếp hạng chúng.

### 6.1. Trực giác

Một tài liệu liên quan tới từ khoá khi:

1. Từ khoá xuất hiện **nhiều lần** trong nó → **TF** (term frequency)
2. Từ khoá **hiếm gặp** trong toàn corpus → **IDF** (inverse document frequency)

Ý thứ 2 quan trọng hơn người ta tưởng. Từ `của` xuất hiện trong mọi tài
liệu nên **không phân biệt được gì**. Từ `blockchain` chỉ có trong vài tài
liệu nên **rất giàu thông tin**.

### 6.2. Công thức

```
tf  = 1 + log₁₀(số lần xuất hiện)
idf = log₁₀(N / df)
trọng số = tf × idf
```

**Vì sao TF dùng logarit?** Nếu dùng tf thô, tài liệu lặp từ khoá 100 lần
sẽ được điểm gấp 100 lần tài liệu chỉ có 1 lần. Nhưng nó **không liên quan
gấp 100 lần** — chỉ là spam từ khoá. Logarit nén khoảng cách đó lại.

**Ví dụ tính tay** (N = 5.011 tài liệu):

| Term | df | idf = log₁₀(5011/df) |
|---|---|---|
| `công_nghệ` (phổ biến) | 1.639 | log₁₀(3,06) = **0,486** |
| `máy_tính` (trung bình) | 50 | log₁₀(100,2) = **2,001** |
| `blockchain` (hiếm) | 5 | log₁₀(1002) = **3,001** |

Term hiếm được trọng số cao gấp **6 lần** term phổ biến.

Một tài liệu chứa `máy_tính` 5 lần:
```
tf  = 1 + log₁₀(5) = 1 + 0,699 = 1,699
trọng số = 1,699 × 2,001 = 3,400
```

### 6.3. Cosine similarity

Biểu diễn truy vấn và tài liệu thành **vector** trong không gian nhiều
chiều (mỗi term là một chiều). Độ liên quan = **cosin góc giữa hai vector**:

```
similarity = (q · d) / (‖q‖ × ‖d‖)
```

**Vì sao phải chia cho độ dài vector?** Nếu không, tài liệu **dài** luôn
thắng — chỉ vì chứa nhiều từ hơn nên tích vô hướng lớn hơn, chứ không phải
vì liên quan hơn. Chia cho độ dài đưa mọi tài liệu về cùng thang đo.

Dự án dùng xấp xỉ kinh điển của Lucene: `‖d‖ ≈ √(độ dài tài liệu)`. Tính
chuẩn xác `‖d‖` đòi hỏi duyệt **mọi** term của tài liệu, tốn O(|từ vựng|)
cho mỗi tài liệu.

---

## 7. Xếp hạng: BM25

BM25 (Okapi BM25) là **chuẩn công nghiệp** hiện nay, thay thế TF-IDF trong
hầu hết hệ thống thật (Elasticsearch dùng nó làm mặc định).

### 7.1. Công thức

```
                    f(q,D) · (k₁ + 1)
score = Σ IDF(q) · ─────────────────────────────
        q          f(q,D) + k₁·(1 − b + b·|D|/avgdl)

IDF(q) = ln(1 + (N − df + 0,5)/(df + 0,5))
```

với `k₁ = 1,2` và `b = 0,75` (giá trị chuẩn qua nhiều thập kỷ thực nghiệm).

### 7.2. Cải tiến 1: bão hoà tần suất

Ở TF-IDF, `tf = 1 + log₁₀(f)` vẫn **tăng vô hạn** theo f. Ở BM25, phân thức
`f/(f + k₁·…)` tiến tới **trần** `k₁ + 1` khi f lớn.

**Ví dụ tính tay** (N=5011, df=50, tài liệu độ dài trung bình):

```
IDF = ln(1 + (5011−50+0,5)/50,5) = ln(99,25) = 4,598
lengthNorm = 1,2 × (1 − 0,75 + 0,75×1) = 1,2
```

| tf | BM25 | TF-IDF (tf-weight) |
|---|---|---|
| 5 | 4,598 × 11/6,2 = **8,16** | 1 + log₁₀5 = **1,70** |
| 50 (gấp 10 lần) | 4,598 × 110/51,2 = **9,88** | 1 + log₁₀50 = **2,70** |
| **Tỷ lệ tăng** | **1,21×** | **1,59×** |

Lặp từ khoá gấp 10 lần chỉ tăng điểm BM25 1,21 lần. Điều này khớp với trực
giác: bài đã nói về "bóng đá" 20 lần thì rõ ràng nói về bóng đá rồi, lặp
thêm 30 lần nữa chỉ là dấu hiệu nhồi từ khoá.

### 7.3. Cải tiến 2: chuẩn hoá độ dài có tham số

TF-IDF chia **cứng** cho `√(docLength)`. BM25 có tham số `b`:

- `b = 0` → không phạt tài liệu dài chút nào
- `b = 1` → chuẩn hoá hoàn toàn theo `|D|/avgdl`
- `b = 0,75` → dung hoà

Có tham số nghĩa là **điều chỉnh được theo đặc thù corpus**.

### 7.4. Cải tiến 3: IDF không bao giờ âm

TF-IDF: `log₁₀(N/df)`. Khi df > N/2 thì giá trị này **âm** — tài liệu chứa
term đó bị **trừ** điểm, một hành vi vô lý.

BM25 bọc trong `ln(1 + …)` nên luôn dương. Kiểm chứng với N=10, df=10 (term
có trong **mọi** tài liệu):

| | Giá trị |
|---|---|
| TF-IDF | log₁₀(10/10) = **0** (triệt tiêu hoàn toàn) |
| BM25 | ln(1 + 0,5/10,5) = **0,0465** (chỉ giảm trọng số) |

### 7.5. Kết quả thực nghiệm

Trên corpus 5.011 tài liệu với 200 truy vấn known-item:

| Mô hình | MRR | Success@1 |
|---|---|---|
| TF-IDF thuần | 0,8537 | 78,0% |
| **BM25 thuần** | **0,8989** | **85,0%** |

BM25 thắng **+5,3%** — đúng như lý thuyết dự đoán.

---

## 8. Xếp hạng: PageRank

TF-IDF và BM25 chỉ nhìn vào **nội dung**. Nhưng nội dung có thể bị giả mạo
— ai cũng viết được một trang nhồi từ "máy tính" 500 lần.

PageRank nhìn vào **cấu trúc liên kết**, thứ khó giả mạo hơn nhiều vì nó
phụ thuộc vào hành vi của **người khác**.

### 8.1. Trực giác: người lướt web ngẫu nhiên

Tưởng tượng một người lướt web mãi mãi:
- 85% thời gian: bấm một liên kết ngẫu nhiên trên trang hiện tại
- 15% thời gian: chán, gõ một URL ngẫu nhiên bất kỳ

**PageRank của một trang = xác suất người đó đang ở trang đó tại một thời
điểm ngẫu nhiên.**

Trang được nhiều trang khác trỏ tới → dễ bị ghé thăm → PageRank cao.

### 8.2. Công thức

```
PR(j) = (1−d)/N + d · [ Σ  PR(i)/outDegree(i) + danglingMass/N ]
                       i→j
```

với `d = 0,85` (damping factor).

Ba thành phần:

| Thành phần | Ý nghĩa |
|---|---|
| `(1−d)/N` | xác suất nhảy ngẫu nhiên tới trang này |
| `Σ PR(i)/outDegree(i)` | "phiếu bầu" từ các trang trỏ tới |
| `danglingMass/N` | xử lý trang cụt (xem 8.4) |

Điểm tinh tế: một trang **chia đều** PageRank của nó cho các trang nó trỏ
tới. Trang trỏ đi 100 link thì mỗi link chỉ mang 1/100 giá trị — nên spam
link không có tác dụng.

### 8.3. Power iteration

Không giải hệ phương trình. Thay vào đó lặp:

1. Khởi tạo mọi trang: `PR = 1/N`
2. Áp dụng công thức cho mọi trang
3. Lặp lại cho tới khi **hội tụ**: `Σ|PR_mới − PR_cũ| < 10⁻⁶`

Thực đo trên dự án:

| Corpus | Số vòng lặp |
|---|---|
| Đồ thị 6 node | 1–28 |
| 150 trang, 1 domain | 44 |
| **5.011 trang, 6 domain** | **53** |

**Ví dụ hội tụ tức thì:** chu trình đối xứng A→B→C→A. Mỗi trang có đúng 1
liên kết vào và 1 liên kết ra, nên theo đối xứng `PR = 1/3` cho cả ba, và
đó đã là điểm bất động ngay từ vòng đầu.

### 8.4. Dangling node — cái bẫy kinh điển

Trang **không có outlink nào** (file PDF, trang cụt) làm "rò rỉ" xác suất
ra khỏi hệ thống. Người lướt vào đó rồi mắc kẹt, và tổng PageRank tụt dần
về 0 — vi phạm tính chất `Σ PR = 1`.

Cách xử lý: gom toàn bộ PageRank của các trang cụt (`danglingMass`) rồi
**phân phối đều** cho tất cả N trang. Tương đương với việc người lướt gõ
URL ngẫu nhiên khi bị mắc kẹt.

### 8.5. Ma trận thưa — vì sao bắt buộc

Đồ thị liên kết là một ma trận N×N. Với N = 5.011:

```
Ma trận đặc: 5011 × 5011 × 8 byte = 191,5 MB
Thực tế chỉ có 239.691 ô khác 0 → adjacency list: ~3,7 MB
```

**Tỷ lệ thưa và quy mô** — đây là bài học quan trọng:

| Corpus | nnz | nnz/N² |
|---|---|---|
| 150 trang, **1 domain** | 3.901 | **17,3%** |
| 5.011 trang, **6 domain** | 239.691 | **0,95%** |

Càng nhiều domain, ma trận càng thưa, lợi ích càng lớn. Đồ thị web thật
(nhiều triệu domain) có tỷ lệ thưa dưới 0,01%.

### 8.6. PageRank chỉ có nghĩa khi có liên kết chéo domain

Corpus 150 trang cùng một tờ báo có **0 liên kết chéo domain**. Liên kết
nội bộ một website phản ánh **cấu trúc điều hướng** (menu, chuyên mục, bài
liên quan) chứ không phản ánh **uy tín**. PageRank trên đó gần như vô nghĩa.

Corpus 6 báo có **42.002 liên kết chéo** — báo này dẫn nguồn báo kia, đó
mới là "phiếu bầu" thật.

---

## 9. Kết hợp điểm và lấy top-K

### 9.1. Kết hợp tuyến tính

```
finalScore = α·relevance + β·pageRank + γ·titleBonus
```

Mặc định `α=0,6`, `β=0,3`, `γ=0,1`.

### 9.2. Cái bẫy lớn: thang đo không tương thích

Đây là phát hiện quan trọng nhất khi đánh giá dự án này.

Công thức trên **ngầm giả định ba đại lượng cùng thang đo**. Đo thực tế:

| Thành phần | Trung bình | Sau khi nhân trọng số |
|---|---|---|
| TF-IDF cosine | 0,177473 | 0,106484 (α=0,6) |
| PageRank | 0,00035580 | 0,00010674 (β=0,3) |

TF-IDF đóng góp **gấp 998 lần** PageRank.

**Nguyên nhân:** PageRank là một **phân phối xác suất tổng bằng 1** trên N
tài liệu, nên giá trị điển hình quanh `1/N ≈ 0,0002`. TF-IDF cosine nằm
trong `[0;1]` với giá trị điển hình lớn hơn hàng nghìn lần.

**Hệ quả:** `β = 0,3` **không** có nghĩa "PageRank đóng góp 30%". Trên thực
tế nó gần như không ảnh hưởng tới thứ hạng ở mọi giá trị β.

**Cách sửa:** chuẩn hoá PageRank về cùng thang trước khi kết hợp — chia cho
PageRank lớn nhất, hoặc min-max normalisation trên tập ứng viên của từng
truy vấn.

> Bài học tổng quát: khi kết hợp tuyến tính nhiều tín hiệu, **luôn kiểm tra
> độ lớn thực tế** của từng thành phần trước khi diễn giải trọng số.

### 9.3. Lấy top-K bằng Min-Heap

Có 1.639 ứng viên, cần 10 kết quả tốt nhất. Sort toàn bộ là **O(n log n)**
— lãng phí, vì ta vứt đi 1.629 kết quả.

Kỹ thuật heap kích thước K:

```
Duy trì min-heap tối đa K phần tử:
    Nếu heap chưa đủ K → thêm vào
    Ngược lại, nếu phần tử mới > đỉnh heap (phần tử nhỏ nhất trong K tốt nhất):
        bỏ đỉnh, thêm phần tử mới
```

Độ phức tạp **O(n log K)**. Với n=1639, K=10:

| Cách | Phép so sánh |
|---|---|
| Sort toàn bộ | 1639 × log₂(1639) ≈ **17.300** |
| Heap top-K | 1639 × log₂(10) ≈ **5.400** |

Nhanh hơn ~3,2 lần, và khoảng cách càng giãn khi n lớn.

---

## 10. Trình bày kết quả — Snippet

Snippet là đoạn trích hiển thị dưới mỗi kết quả, có bôi vàng từ khoá.

### 10.1. Cửa sổ trượt

Bài toán: trong một bài viết 1.043 token, chọn đoạn 25 từ **chứa nhiều từ
khoá nhất**.

Cách ngây thơ: với mỗi vị trí, đếm lại số từ khoá trong cửa sổ →
**O(n × windowSize)**.

Cửa sổ trượt: khi trượt sang phải một bước, chỉ có **một** từ ra khỏi cửa
sổ và **một** từ vào:

```
count = count − (từ vừa ra là từ khoá ? 1 : 0)
              + (từ vừa vào là từ khoá ? 1 : 0)
```

Cập nhật O(1) mỗi bước → tổng **O(n)**.

### 10.2. Chỉ sinh snippet cho top-N

Một lỗi hiệu năng thật đã gặp trong dự án: `buildSnippet()` được gọi cho
**mọi** ứng viên rồi mới cắt top-N. Với 500 ứng viên thì 490 snippet bị tạo
ra rồi vứt đi ngay.

Sửa thành 3 bước: **chấm điểm → lấy top-K → chỉ sinh snippet cho K tài liệu
sống sót.** Chi phí giảm từ `O(c × docLength)` xuống `O(topN × docLength)`.

### 10.3. Bôi sáng: cạm bẫy bỏ dấu

Ban đầu mọi từ đều bị bỏ dấu trước khi so khớp. Kết quả sai:

```
Truy vấn: "ngân hàng"
Snippet:  Nhiều <mark>ngân</mark> <mark>hàng</mark> cắt giảm cả <mark>ngàn</mark> nhân sự
                                                              ↑ SAI
```

`ngân` và `ngàn` bỏ dấu đều thành `ngan` nên đụng nhau.

Bỏ dấu là **cần** ở khâu tra cứu chỉ mục (để gõ `may tinh` tìm được
`máy tính`), nhưng **thừa và sai** ở khâu bôi sáng — vì lúc đó đã biết
chính xác người dùng gõ gì.

**Quy tắc đúng:** tiếng trong truy vấn **có dấu** → chỉ khớp chính xác;
người dùng vốn gõ **không dấu** → mới cho phép khớp lỏng.

---

## 11. Tăng tốc — Cache và Autocomplete

### 11.1. LRU Cache

Truy vấn phổ biến được lặp lại rất nhiều. Cache kết quả theo khoá
`query + page + size`.

**LRU (Least Recently Used)**: khi cache đầy, loại bỏ mục **lâu nhất không
được dùng**.

Cấu trúc: `HashMap` + **danh sách liên kết đôi**:

```
HashMap:  khoá → node          (tra cứu O(1))
Danh sách: MRU ⟷ ... ⟷ LRU     (thứ tự sử dụng)
```

- `get(k)`: tra HashMap O(1), chuyển node lên đầu O(1)
- `put(k,v)`: thêm vào đầu, nếu quá sức chứa thì xoá node cuối O(1)

**Vì sao danh sách liên kết đôi?** Để xoá một node ở **giữa** danh sách
trong O(1), cần biết cả node trước và node sau. Danh sách liên kết đơn phải
duyệt từ đầu để tìm node trước → O(n).

**Vì sao 2 sentinel node?** Hai node giả ở đầu và cuối, không chứa dữ liệu.
Nhờ chúng, thao tác thêm/xoá **không bao giờ** phải kiểm tra `null` cho
trường hợp đặc biệt ở biên — mọi node thật đều chắc chắn có `prev` và `next`.

Đo thực tế: cache miss 34,5 ms → cache hit 12,8 ms (nhanh **2,7 lần**; phần
lớn thời gian còn lại là chi phí HTTP round-trip).

### 11.2. Trie cho autocomplete

Người dùng gõ `cong`, cần gợi ý ngay `công nghệ`, `công ty`...

**Trie** (cây tiền tố): mỗi cạnh là một ký tự, đường đi từ gốc tới node là
một tiền tố.

```
        (gốc)
         │c
         o
         n
         g
        ╱ ╲
   " "        ...
    │
   "công ty", "công nghệ"
```

- `insert`: O(L) với L là độ dài chuỗi
- Tìm node của tiền tố: O(L)
- Thu thập mọi từ dưới cây con: DFS O(m)
- Lấy top-k theo tần suất: `MinHeap.topK` O(m log k)

**Vấn đề riêng của tiếng Việt:** Trie khớp **từng ký tự chính xác**, nên
tiền tố `cong` không bao giờ đi tới được nhánh `công nghệ`.

**Giải pháp:** tách **khoá tra cứu** khỏi **chuỗi hiển thị**. Chèn cùng một
mục hai lần — một lần dưới khoá có dấu, một lần dưới khoá không dấu — nhưng
cả hai node ghi nhớ cùng một chuỗi hiển thị có dấu:

```
insert(key="công nghệ", display="công nghệ")
insert(key="cong nghe", display="công nghệ")
```

Gõ kiểu nào cũng ra, mà thứ hiển thị luôn đúng chính tả.

**Nguồn dữ liệu gợi ý cũng quan trọng.** Ban đầu dự án chèn nguyên tiêu đề
và từng tiếng lẻ → gợi ý ra `cong`, `the`, `congreso`, và cả tiêu đề tiếng
Anh dài loằng ngoằng. Tiếng lẻ trong tiếng Việt **không phải từ**. Sửa
thành: tokenize tiêu đề rồi lấy **từ ghép** và **cặp token liền nhau**, lọc
bỏ tiêu đề không phải tiếng Việt.

---

## 12. Đo chất lượng — phần hầu hết mọi người bỏ qua

Đây là phần phân biệt một đồ án "tôi xây được" với một đồ án "tôi chứng
minh được".

### 12.1. Vì sao thời gian truy vấn không nói lên điều gì

Đo được "truy vấn mất 6,43 ms" và "cache hit rate 90%" là đo **tốc độ**. Nó
hoàn toàn không trả lời được câu hỏi quan trọng nhất:

> **Kết quả trả về có đúng không?**

Một hệ thống trả về kết quả sai trong 1 ms vẫn vô dụng.

### 12.2. Các độ đo

Giả sử truy vấn có 3 tài liệu liên quan, hệ thống trả về 5 kết quả, trong
đó vị trí 1, 3, 5 là đúng:

**Precision@k** — trong k kết quả đầu, bao nhiêu phần đúng:
```
P@3 = 2/3 = 0,667      (vị trí 1 và 3 đúng)
P@5 = 3/5 = 0,600
```
Mẫu số luôn là `k`, không phải số kết quả trả về — trả về quá ít kết quả tự
nó là khiếm khuyết và phải bị phạt.

**Recall@k** — trong mọi tài liệu liên quan, lấy được bao nhiêu:
```
R@5 = 3/3 = 1,0
```

**MAP (Mean Average Precision)** — nhạy với **thứ tự**:
```
AP = (P@1 + P@3 + P@5) / 3          ← chỉ tính tại vị trí có kết quả đúng
   = (1/1 + 2/3 + 3/5) / 3
   = (1,0 + 0,667 + 0,6) / 3
   = 0,756
```
Chia cho **tổng** số tài liệu liên quan (không phải số tìm được), nên bỏ
sót vẫn bị phạt.

**Vì sao cần MAP khi đã có P@k?** Hai hệ thống cùng P@4 = 0,5:
```
Hệ A: [đúng, đúng, sai, sai]   → AP = (1/1 + 2/2)/2 = 1,00
Hệ B: [sai, sai, đúng, đúng]   → AP = (1/3 + 2/4)/2 = 0,42
```
P@4 không phân biệt được, MAP thì có. Mà người dùng thật **luôn** nhìn kết
quả đầu tiên trước.

**nDCG** — độ đo duy nhất dùng được mức độ liên quan **nhiều bậc** (0/1/2):
```
DCG@k = Σ (2^rel_i − 1) / log₂(i + 1)
nDCG  = DCG / IDCG        (IDCG = DCG của thứ tự lý tưởng)
```

Ví dụ tính tay với `[d1(mức 2), d2(mức 0), d3(mức 1)]`:
```
độ lợi:     2²−1=3,    2⁰−1=0,    2¹−1=1
chiết khấu: log₂2=1,   log₂3=1,585, log₂4=2

DCG  = 3/1 + 0/1,585 + 1/2 = 3,5
IDCG = 3/1 + 1/1,585 + 0/2 = 3,631      (thứ tự lý tưởng [2,1,0])
nDCG = 3,5/3,631 = 0,964
```

Dùng độ lợi **hàm mũ** `2^rel − 1` thay vì tuyến tính để nhấn mạnh: tài
liệu "rất liên quan" (mức 2) được 3 điểm, "liên quan" (mức 1) được 1 điểm —
tỷ lệ 3:1 thay vì 2:1.

**MRR (Mean Reciprocal Rank)** — nghịch đảo thứ hạng của kết quả đúng đầu
tiên:
```
đúng ở hạng 1 → 1,0
đúng ở hạng 2 → 0,5
đúng ở hạng 10 → 0,1
```
Phù hợp nhất khi chỉ có **một** đáp án đúng.

### 12.3. Lấy nhãn liên quan ở đâu ra

Đây là khó khăn thật: muốn tính các độ đo trên thì phải biết tài liệu nào
liên quan — mà cái đó thường phải **người gán tay**.

**Cách 1: Known-item search** (tự động, khách quan)

Lật ngược bài toán: thay vì hỏi "tài liệu nào liên quan tới truy vấn này",
ta **chọn trước một tài liệu**, sinh truy vấn từ chính các từ khoá đặc
trưng nhất của nó, và đáp án đúng hiển nhiên là tài liệu đó.

Mô phỏng đúng tình huống người dùng nhớ mang máng một bài báo rồi gõ vài từ
khoá tìm lại.

> **Chi tiết dễ làm sai:** nếu chọn các term hiếm nhất (df = 1) thì phép
> giao posting list chỉ còn đúng một tài liệu — hệ thống nào cũng đạt
> MRR = 1,0 và bài đánh giá vô nghĩa. Phải lọc df vào khoảng
> **[3, 10% corpus]**: đủ hiếm để phân biệt, đủ phổ biến để còn cạnh tranh.

**Cách 2: TREC pooling** (người gán, nhưng ít việc)

Không ai gán nhãn nổi 5.011 tài liệu × 30 truy vấn = 150.000 lượt. Cách
TREC giải quyết: chỉ gán nhãn **phần hợp của top-k từ nhiều hệ thống khác
nhau**.

Giả định nền tảng: tài liệu thực sự liên quan gần như chắc chắn sẽ được ít
nhất một hệ thống đưa lên top. Tài liệu không hệ thống nào đưa lên top thì
coi như không liên quan.

Khối lượng giảm từ 150.000 xuống vài trăm, mà thứ tự xếp hạng giữa các hệ
thống hầu như không đổi.

### 12.4. Ablation — cách trả lời "tại sao chọn tham số này"

Chạy **cùng** bộ truy vấn, **cùng** chỉ mục, chỉ thay **đúng một** biến số
mỗi lần:

| Cấu hình | MRR | Success@1 |
|---|---|---|
| TF-IDF thuần | 0,8537 | 78,0% |
| BM25 thuần | 0,8989 | 85,0% |
| TF-IDF + title | 0,9050 | 85,5% |
| TF-IDF + PageRank | 0,8625 | 79,0% |
| **TF-IDF + PR + title (0,6/0,3/0,1)** | **0,9196** | **87,5%** |

Giờ câu hỏi "tại sao α=0,6?" có câu trả lời bằng số liệu, không còn là con
số chọn bừa.

> **Nguyên tắc sống còn:** bộ đánh giá phải dùng **đúng code path** mà hệ
> thống thật chạy. Nếu viết một đường đi riêng cho phần đo, mọi kết luận
> rút ra chỉ nói về đường đi đó chứ không nói gì về sản phẩm.

### 12.5. Cạm bẫy khi đo hiệu năng trên JVM

Những lần gọi đầu tiên chạy bằng **trình thông dịch**; chỉ sau vài nghìn
lượt thì JIT mới biên dịch sang mã máy. Nếu đo ngay từ đầu, phía chạy
**trước** gánh toàn bộ chi phí khởi động còn phía chạy **sau** hưởng JVM đã
nóng.

Thực đo trong dự án:

| | Chưa làm nóng | Đã làm nóng |
|---|---|---|
| Chỉ mục tự cài | 10,83 ms | **6,43 ms** |
| PostgreSQL GIN | 1,42 ms | 1,18 ms |

Chi phí warmup chiếm **40%** con số ban đầu. Luôn chạy vài vòng làm nóng
cho **cả hai** phía trước khi bấm giờ.

---

## 13. Điều gì vỡ ở quy mô lớn

Dự án này chạy tốt ở 5.011 trang. Nếu nhắm **1 tỷ trang/tháng** (386
trang/giây), đây là những chỗ vỡ — và chúng mang tính **kiến trúc**, không
phải chỉ cần chạy nhanh hơn 15 lần.

### 13.1. Tràn số nguyên trong Bloom Filter

```java
new BloomFilter(Math.max(200_000, maxPages * 200), 0.01)
```

Với `maxPages = 10⁹`: `10⁹ × 200 = 2×10¹¹` vượt `Integer.MAX_VALUE`
(2,1×10⁹) → **tràn thành số âm** → `Math.max` trả về 200.000 bit cho 1 tỷ
URL → false positive **100%** → crawler tưởng đã thăm hết mọi thứ và **dừng
ngay lập tức**.

Bloom filter cho 10 tỷ URL ở FPR 1% cần **12 GB** — vẫn vừa RAM một máy,
nhưng phải viết lại bằng `long` thay vì `int`.

### 13.2. Cấu trúc trong bộ nhớ không scale

| Cấu trúc | Ở 5.011 trang | Ở 1 tỷ trang |
|---|---|---|
| Tập `enqueued` (HashSet) | vài MB | **168 GB** |
| `crawled` ConcurrentHashMap | 62 MB | OOM từ vài trăm nghìn trang |
| `DEFAULT_MAX_SIZE` | 500.000 | nhỏ hơn nhu cầu **20.000 lần** |

Frontier phải chuyển sang **lưu trên đĩa hoặc phân tán**, và tài liệu crawl
được phải **ghi ra ngoài theo luồng** thay vì giữ trong RAM tới cuối phiên.

### 13.3. Quét O(D) không còn rẻ

`nextUrl()` quét qua mọi host. Với 52 host thì không sao. Web thật có **~200
triệu host** — mỗi lần lấy một URL phải quét 200 triệu mục. Cần hàng đợi ưu
tiên theo **thời điểm khả dụng tiếp theo**, không phải quét tuyến tính.

### 13.4. Politeness đặt trần cứng lên thông lượng

1 giây/host ⟹ muốn 400 trang/giây phải có **≥ 400 host được crawl đồng thời
mọi lúc**. Đây không phải vấn đề kỹ thuật mà là vấn đề **phân bổ**: phải
shard URL theo hash của host để mỗi máy giữ politeness cục bộ.

### 13.5. Những thứ còn thiếu hoàn toàn

| Thành phần | Trạng thái |
|---|---|
| **Content Seen?** (khử trùng lặp **nội dung**) | ❌ Không có |
| **DNS cache** | ❌ Không có — phân giải lại mỗi lần fetch |
| **Freshness** (recrawl, `If-Modified-Since`, `ETag`) | ❌ Không có |
| Chống spider trap | ⚠️ Chỉ nhờ `maxDepth = 3` |
| Checkpoint / phục hồi sau sự cố | ❌ Không có |

Đáng chú ý nhất là **Content Seen?**: dự án khử trùng lặp **URL** nhưng
không khử trùng lặp **nội dung**. Cùng một bài báo ở `/bai-viet`,
`/bai-viet?utm_source=fb`, `/print/bai-viet` là ba URL khác nhau, ba lần
crawl, ba bản trong chỉ mục. Giải pháp chuẩn là **SimHash + khoảng cách
Hamming** (Google dùng chính kỹ thuật này).

---

## Tóm tắt: toàn bộ luồng cho một truy vấn

```
"công nghệ"
   │
   ├─ QueryParser: tokenize bằng CÙNG tokenizer lúc index
   │                → mustTerms=[công_nghệ]
   │
   ├─ InvertedIndex.getPostings("công_nghệ")     O(1) HashMap
   │                → posting list đã sắp xếp theo docId
   │
   ├─ PostingListMerger.intersectAll()            O(m+n) two-pointer
   │                → 1.639 docId ứng viên          (shortest-first)
   │
   ├─ TfIdfScorer / BM25Scorer                    O(q log d) binary search
   │  + PageRankService (tính sẵn)                 power iteration
   │  → finalScore = α·tfidf + β·pagerank + γ·title
   │
   ├─ MinHeap.topK(10)                            O(n log K)
   │
   ├─ ResultRanker.buildSnippet()                 O(n) sliding window
   │                → chỉ cho 10 tài liệu sống sót
   │
   └─ LRUCache.put()                              O(1)
                    → JSON trả về client
```

## Đọc thêm

Các công trình gốc, nên trích dẫn trong chương cơ sở lý thuyết:

| Chủ đề | Nguồn |
|---|---|
| PageRank | Brin & Page (1998), *The Anatomy of a Large-Scale Hypertextual Web Search Engine* |
| BM25 | Robertson & Sparck Jones (1976); Robertson & Zaragoza (2009) |
| Kiến trúc crawler | Heydon & Najork (1999), *Mercator: A Scalable, Extensible Web Crawler* |
| Bloom Filter | Bloom (1970); Kirsch & Mitzenmacher (2008) cho double hashing |
| Khử trùng lặp nội dung | Charikar (2002), *Similarity Estimation Techniques* (SimHash) |
| Đánh giá IR | Manning, Raghavan & Schütze (2008), *Introduction to Information Retrieval* — chương 8 |
| Pooling | Voorhees & Harman (2005), *TREC: Experiment and Evaluation in Information Retrieval* |

## Tài liệu liên quan trong dự án

| Tài liệu | Nội dung |
|---|---|
| `ALGORITHMS.md` | Bảng tra cứu nhanh mọi thuật toán theo pipeline |
| `DSA-REPORT.md` | Độ phức tạp Big-O, so sánh có đo đạc |
| `ARCHITECTURE.md` | Sơ đồ kiến trúc, sequence diagram |
| `EVALUATION.md` | Kết quả đánh giá chất lượng (sinh tự động) |
| `GIN-BASELINE.md` | Đối chứng với PostgreSQL GIN (sinh tự động) |
