# PostingListMerger — two-pointer, shortest-first và khớp cụm từ

**File nguồn:** `search-engine/src/main/java/com/vnsearch/query/PostingListMerger.java`
**Việc nó làm:** Từ nhiều posting list, tìm ra tập tài liệu chứa **tất cả** các term — phần DSA đắt giá nhất của module truy vấn.

> 📖 Chưa quen ký hiệu toán? Đọc [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md) trước.


> ### 🔄 Đã cập nhật sau đợt tái cấu trúc
>
> Phần **toán học và thuật toán** dưới đây vẫn đúng nguyên vẹn. Nhưng một số
> đoạn mã trích dẫn và mục *"Hạn chế đã biết"* mô tả **phiên bản trước**.
> Những gì đã thay đổi ở file này:
>
> - Đã thêm **`intersectCursors`** dùng `PostingCursor` với **galloping search** — không autoboxing, 4005 bước → ~48 bước.
> - `matchesPhrase` đã sửa hai chỗ lãng phí: lấy `positions` một lần ngoài vòng lặp, và dùng `Collections.binarySearch` thay `List.contains`.
> - Lớp nay là `final` với constructor `private`.
>

---

## 📌 Hiểu trong 30 giây

Truy vấn `máy tính công nghệ` cần các tài liệu có **cả hai** term — tức lấy **giao** của hai posting list.

Cách hiển nhiên: nhét một list vào `HashSet` rồi duyệt list kia. Đo thực tế cho thấy cách đó **chậm hơn 2,7 lần**.

Cách nhanh hơn tận dụng một thứ ta đã có **miễn phí**: posting list đã sắp xếp theo `docId` (xem [InvertedIndex §4](../02-index/InvertedIndex.md)). Với hai danh sách đã sắp xếp, có thể duyệt song song bằng **two-pointer** — mỗi phần tử được xét đúng **một** lần, không băm, không cấp phát.

```
   A:  3   7   11   15   22
       ▲
   B:  7   11   19   22
       ▲
                                so 3 < 7   ⇒ tiến con trỏ A
   A:  3   7   11   15   22
           ▲
   B:  7   11   19   22
       ▲
                                so 7 = 7   ⇒ GHI 7, tiến CẢ HAI
   A:  3   7   11   15   22
               ▲
   B:  7   11   19   22
           ▲
                                so 11 = 11 ⇒ GHI 11, tiến cả hai
   …
   kết quả: [7, 11, 22]

   Bất biến vòng lặp: con trỏ nào trỏ vào giá trị NHỎ HƠN thì tiến con trỏ đó.
   ⇒ không bao giờ bỏ sót một phần tử chung nào.
```

```mermaid
%%{init:{'theme':'base','themeVariables':{'background':'#ffffff','primaryColor':'#ffffff','primaryTextColor':'#000000','primaryBorderColor':'#000000','secondaryColor':'#ffffff','secondaryTextColor':'#000000','secondaryBorderColor':'#000000','tertiaryColor':'#ffffff','tertiaryTextColor':'#000000','tertiaryBorderColor':'#000000','lineColor':'#000000','textColor':'#000000','mainBkg':'#ffffff','nodeBorder':'#000000','clusterBkg':'#ffffff','clusterBorder':'#000000','edgeLabelBackground':'#ffffff','actorBkg':'#ffffff','actorBorder':'#000000','actorTextColor':'#000000','actorLineColor':'#000000','signalColor':'#000000','signalTextColor':'#000000','labelBoxBkgColor':'#ffffff','labelBoxBorderColor':'#000000','labelTextColor':'#000000','loopTextColor':'#000000','noteBkgColor':'#ffffff','noteBorderColor':'#000000','noteTextColor':'#000000','sequenceNumberColor':'#ffffff','fontFamily':'ui-monospace, SFMono-Regular, Consolas, monospace'}}}%%
flowchart TD
    S["i = 0, j = 0"]
    C{"A[i] so B[j]"}
    EQ["bằng ⇒ ghi vào kết quả<br/>i++, j++"]
    LT["A[i] nhỏ hơn ⇒ i++"]
    GT["B[j] nhỏ hơn ⇒ j++"]
    E{"hết một trong hai list?"}
    DONE["xong"]

    S --> C
    C -->|"="| EQ --> E
    C -->|"<"| LT --> E
    C -->|">"| GT --> E
    E -->|"chưa"| C
    E -->|"rồi"| DONE
```

**Vì sao HashSet thua dù cũng là $O(m+n)$.** Cùng bậc độ phức tạp không có
nghĩa là cùng tốc độ:

| | Two-pointer | HashSet |
|---|---|---|
| Cấp phát bộ nhớ | **không** | dựng cả một bảng băm |
| Autoboxing `int` → `Integer` | không | có, hàng trăm nghìn lần |
| Truy cập bộ nhớ | **tuần tự** — thân thiện cache CPU | ngẫu nhiên — trượt cache liên tục |
| Đo thật | **10,0 ms** | 27,0 ms |

Yếu tố quyết định là dòng thứ ba: duyệt tuần tự hai mảng cho phép CPU nạp
trước dữ liệu, còn nhảy ngẫu nhiên trong bảng băm thì không.

---

## 1. Two-pointer intersect

**Ý tưởng.** Hai con trỏ cùng tiến. Ở mỗi bước, con trỏ nào đang chỉ vào giá trị **nhỏ hơn** thì tiến — vì giá trị đó chắc chắn không thể xuất hiện ở danh sách kia nữa (danh sách kia đã vượt qua nó rồi).

**Chạy tay:**

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

**Mã thật:**

```java
public static List<Integer> intersect(List<Integer> a, List<Integer> b) {
    List<Integer> result = new ArrayList<>();
    int i = 0, j = 0;
    while (i < a.size() && j < b.size()) {
        int docA = a.get(i);
        int docB = b.get(j);
        if (docA == docB) {
            result.add(docA);
            i++;
            j++;
        } else if (docA < docB) {
            i++;
        } else {
            j++;
        }
    }
    return result;
}
```

### 1.1 Chứng minh tính đúng đắn

**Bất biến vòng lặp:** *Tại mọi thời điểm, `result` chứa đúng $A[0..i) \cap B[0..j)$, và mọi phần tử chung còn lại nằm trong $A[i..) \cap B[j..)$.*

**Chứng minh bằng quy nạp.** Ban đầu $i = j = 0$, `result` rỗng — đúng.

Giả sử bất biến đúng trước một bước lặp. Xét ba trường hợp:

- **$A[i] = B[j]$**: đây là phần tử chung, ghi vào `result` và tăng cả hai. Bất biến giữ nguyên.
- **$A[i] < B[j]$**: vì $B$ tăng dần, mọi phần tử $B[j'], j' \ge j$ đều $\ge B[j] > A[i]$. Vậy $A[i]$ **không thể** có trong $B[j..)$. Bỏ nó đi là an toàn: $i{+}{+}$.
- **$A[i] > B[j]$**: đối xứng.

Vòng lặp dừng khi một danh sách hết; phần còn lại của danh sách kia không thể có phần tử chung nào (không còn gì để khớp). ∎

**Điểm mấu chốt của chứng minh** là câu *"vì $B$ tăng dần"* — không có bất biến sắp xếp thì lập luận sụp đổ hoàn toàn.

### 1.2 `union` — gần như y hệt

```java
public static List<Integer> union(List<Integer> a, List<Integer> b) {
    ...
        if (docA == docB) { result.add(docA); i++; j++; }
        else if (docA < docB) { result.add(docA); i++; }     // ← ghi thay vì bỏ
        else { result.add(docB); j++; }
    }
    while (i < a.size()) result.add(a.get(i++));             // ← dọn đuôi
    while (j < b.size()) result.add(b.get(j++));
    return result;
}
```

Hai khác biệt: ghi cả phần tử nhỏ hơn thay vì bỏ, và có hai vòng `while` dọn phần đuôi còn lại. Đây **chính xác** là bước merge của merge sort — cùng thuật toán, khác đúng ba dòng.

---

## 2. Vì sao không dùng `HashSet.retainAll`

Đo thực tế với 2 danh sách 500.000 phần tử:

| Cách làm | Thời gian trung bình/lần |
|---|---|
| **Two-pointer `intersect`** | **~10,0 ms** |
| `HashSet.retainAll` (không tính chi phí dựng HashSet) | ~15,5 ms (**chậm hơn ~55 %**) |
| `HashSet.retainAll` (tính cả chi phí dựng 2 HashSet) | ~27,0 ms (**chậm hơn ~2,7 lần**) |

**Ba lý do two-pointer thắng, xếp theo mức đóng góp:**

### 2.1 Không có chi phí dựng cấu trúc trung gian

Trong hệ thống thật, posting list là `List<Posting>` lấy **thẳng** từ chỉ mục. Muốn dùng `HashSet` thì phải dựng nó **mỗi truy vấn** — $O(n)$ phép băm cộng cấp phát bảng. Dòng thứ 3 của bảng là so sánh công bằng nhất.

### 2.2 Cục bộ cache

Two-pointer đọc hai mảng **tuần tự**. CPU nạp một cache line 64 byte là được 16 giá trị `int` liền kề, và bộ tiên đoán nạp trước (prefetcher) nhận ra ngay mẫu truy cập tuyến tính.

`HashSet.contains` nhảy tới một vị trí **ngẫu nhiên** trong bảng băm — gần như luôn là cache miss (~100 chu kỳ CPU). Với 500.000 phép tra, chênh lệch này chiếm phần lớn 5,5 ms của dòng thứ 2.

### 2.3 Không có hằng số ẩn của hashing

$O(1)$ của `HashSet` là **trung bình khấu hao**, và hằng số ẩn gồm: tính `hashCode`, trộn bit, lấy modulo, dò chuỗi va chạm. Two-pointer là **$O(m+n)$ tuyệt đối** — một phép so sánh `int` mỗi bước, không có gì ẩn.

> **Bài học tổng quát:** khi dữ liệu **đã có sẵn một tính chất** (ở đây: đã sắp xếp), thuật toán tận dụng tính chất đó gần như luôn thắng thuật toán tổng quát hơn. `HashSet` mạnh vì nó không cần giả định gì — nhưng ở đây ta có giả định, và nó miễn phí.

---

## 3. Shortest-first — tối ưu cho truy vấn nhiều term

**Vấn đề.** Khi truy vấn có 3+ term, **thứ tự giao rất quan trọng**.

**Cơ sở toán học.** Gọi $A_k$ là kết quả giao sau $k$ bước. Luôn có:

$$\lvert A_k \rvert \;\le\; \min_{1 \le j \le k} \lvert L_j \rvert$$

*"Giao không bao giờ lớn hơn danh sách nhỏ nhất đã xét."*

Chi phí mỗi bước giao là $O(\lvert A_{k} \rvert + \lvert L_{k+1} \rvert)$. Vậy để tổng chi phí nhỏ, phải làm $\lvert A_k \rvert$ nhỏ **càng sớm càng tốt** — tức bắt đầu từ danh sách **ngắn nhất**.

**Ví dụ bằng số.** Truy vấn 3 term: `iPhone` (df = 5), `của` (df = 4000), `giá` (df = 3000).

| Thứ tự | Bước 1 | Bước 2 | Tổng phần tử duyệt |
|---|---|---|---|
| **Ngắn trước** (5, 3000, 4000) | $5 + 3000 = 3005$, kết quả $\le 5$ | $5 + 4000 = 4005$ | **7 010** |
| Dài trước (4000, 3000, 5) | $4000+3000 = 7000$, kết quả có thể tới 3000 | $3000 + 5 = 3005$ | **10 005** |

Chênh 30% ở ví dụ này. Với truy vấn có một term **rất hiếm** trộn nhiều term phổ biến, chênh lệch lên tới hàng chục lần.

```java
public static List<Integer> intersectAll(List<List<Posting>> postingLists) {
    if (postingLists.isEmpty()) return new ArrayList<>();
    List<List<Posting>> sorted = new ArrayList<>(postingLists);
    sorted.sort(Comparator.comparingInt(List::size));       // ← shortest-first

    List<Integer> result = docIdsOf(sorted.get(0));
    for (int i = 1; i < sorted.size() && !result.isEmpty(); i++) {
        result = intersect(result, docIdsOf(sorted.get(i)));
    }
    return result;
}
```

### 3.1 `&& !result.isEmpty()` — thoát sớm

Giao rỗng thì **dừng ngay**, không duyệt các list còn lại. Với AND ngầm định, **rỗng là rỗng mãi**:

$$\emptyset \cap L = \emptyset \quad \forall L$$

Một dòng điều kiện tiết kiệm toàn bộ phần còn lại của vòng lặp trong trường hợp phổ biến nhất của truy vấn không có kết quả.

### 3.2 Tối ưu còn sớm hơn ở `CandidateResolver`

```java
for (String term : allRequiredTerms) {
    List<Posting> postings = index.getPostings(term);
    if (postings.isEmpty()) {
        // AND ngầm định: chỉ cần một term không xuất hiện là kết quả rỗng.
        return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
    }
    postingLists.add(postings);
}
```

Thoát **trước khi** gọi `intersectAll` nếu **bất kỳ** term nào có df = 0. Đây là trường hợp cực phổ biến (người dùng gõ sai chính tả, hoặc dùng từ không có trong corpus) và nó được xử lý với chi phí gần bằng 0.

### 3.3 `docIdsOf` — một chi phí ẩn

```java
public static List<Integer> docIdsOf(List<Posting> postings) {
    List<Integer> ids = new ArrayList<>(postings.size());
    for (Posting p : postings) ids.add(p.docId());
    return ids;
}
```

Hàm này **cấp phát một `ArrayList<Integer>` mới** cho mỗi posting list, và mỗi `docId` bị **autobox** thành một object `Integer` (16 byte thay vì 4).

Với posting list 4.000 mục, đó là 64 KB rác GC mỗi lần gọi — và `intersectAll` gọi nó $k$ lần cho truy vấn $k$ term.

**Cách tối ưu:** cho `intersect` nhận thẳng `List<Posting>` và so sánh `p.docId()`, hoặc dùng `int[]` thay vì `List<Integer>`. Với quy mô hiện tại (posting list dài nhất 1.639) thì chi phí này không đo được, nhưng nó là loại chi phí tăng tuyến tính theo kích thước corpus.

---

## 4. Tổng hợp độ phức tạp

| Thao tác | Thời gian |
|---|---|
| `intersect(a, b)` | **$O(m+n)$** tuyệt đối |
| `union(a, b)` | **$O(m+n)$** tuyệt đối |
| `docIdsOf` | $O(n)$ + cấp phát |
| `intersectAll` | $O\!\left(\sum_j \lvert L_j \rvert\right)$ + $O(k \log k)$ sort |
| `matchesPhrase` | $O(p_1 \cdot k \cdot \log n)$ — xem §5 |

**Vì sao $O(k \log k)$ sort là không đáng kể:** $k$ = số term trong truy vấn, thực tế 1–4. $4 \log 4 = 8$ phép so sánh.

---

## 5. Khớp cụm từ theo vị trí liên tiếp

**Vấn đề.** `"trình duyệt web"` yêu cầu 3 từ xuất hiện **liên tiếp đúng thứ tự**, không chỉ là cùng có mặt trong tài liệu.

Giao posting list chỉ trả lời "cả ba term đều có trong tài liệu này" — chưa đủ. Một bài viết có `trình` ở vị trí 5, `duyệt` ở vị trí 200, `web` ở vị trí 700 vẫn lọt qua phép giao.

**Ý tưởng.** Đây là lúc `positions` phát huy tác dụng: với mỗi vị trí xuất hiện của từ **đầu tiên**, kiểm tra từ thứ $i$ có nằm đúng ở $\text{start} + i$.

```
Trong doc5:
  trình  xuất hiện ở vị trí [2, 17]
  duyệt  xuất hiện ở vị trí [3, 40]
  web    xuất hiện ở vị trí [4, 41]

Thử start = 2:  cần duyệt ở 3 ✅, web ở 4 ✅ → KHỚP
```

**`PostingListMerger.java:207-238`** — bản hiện hành, đã vá **cả hai** chỗ lãng phí mà bản trước của trang này còn liệt kê là nợ kỹ thuật:

```java
public static boolean matchesPhrase(SearchIndex index, List<String> phraseTerms, int docId) {
    if (phraseTerms.isEmpty()) {
        return true;
    }
    // TỐI ƯU 1: lấy tất cả danh sách vị trí MỘT lần, ngoài vòng lặp.
    int[][] positionsByTerm = new int[phraseTerms.size()][];
    for (int i = 0; i < phraseTerms.size(); i++) {
        int[] positions = index.getPositions(phraseTerms.get(i), docId);
        if (positions.length == 0) {
            return false; // một term không xuất hiện -> không thể có cụm
        }
        positionsByTerm[i] = positions;
    }

    for (int start : positionsByTerm[0]) {
        boolean allMatch = true;
        for (int i = 1; i < phraseTerms.size(); i++) {
            // TỐI ƯU 2: tìm kiếm nhị phân trên dãy đã sắp xếp.
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

### 5.1 Độ phức tạp — và hai chỗ lãng phí ĐÃ ĐƯỢC VÁ

> ✅ **Bản trước của trang này liệt kê hai chỗ lãng phí như nợ kỹ thuật còn tồn.**
> Cả hai đã được sửa, và code đánh dấu bằng chính hai chữ `TỐI ƯU 1` / `TỐI ƯU 2`
> (`PostingListMerger.java:211`, `:224`). Giữ lại phân tích ở đây vì nó cho thấy
> **vì sao** hai phép sửa đó đáng làm.

| | Trước | Nay | Dòng |
|---|---|---|---|
| **Lãng phí 1** | `getPositions` gọi lại trong vòng lặp **trong**, dù kết quả không phụ thuộc `start`. Term đầu xuất hiện 20 lần, cụm 3 từ → $20 \times 2 = 40$ lần binary search thay vì 2 | Lấy hết **một lần** vào `int[][] positionsByTerm`, ngoài vòng lặp | `:211-219` |
| **Lãng phí 2** | `positions.contains(start + i)` quét tuyến tính $O(p_i)$ | `Arrays.binarySearch` → $O(\log p_i)$ | `:229` |

Độ phức tạp đi từ

$$O\bigl(p_1 \times k \times (\log n + p_i)\bigr) \qquad\longrightarrow\qquad O\bigl(\underbrace{k \log n}_{\text{lấy vị trí, 1 lần}} + \underbrace{p_1 \times k \times \log p_i}_{\text{dò cụm}}\bigr)$$

**Một chi tiết tinh tế đáng học, code ghi rõ ở `:224-228`:**

> `Arrays.binarySearch` thay cho `Collections.binarySearch`: **cùng thuật toán**,
> nhưng chạy thẳng trên `int[]` nên **không phải mở hộp một `Integer` ở mỗi bước
> so sánh** — mà vòng này là chỗ nóng nhất của tìm kiếm theo cụm.

Đây chính là chỗ khoản đầu tư `int[]` ở [`Posting`](../02-index/InvertedIndex.md)
trả lãi lần thứ hai: lần đầu là **bộ nhớ** (87,5 MB → 14,6 MB), lần này là **tốc
độ** trong vòng lặp nóng nhất.

**Và một phép thoát sớm mới** (`:215-217`): nếu **bất kỳ** term nào không xuất
hiện trong tài liệu thì không thể có cụm — trả `false` ngay, khỏi vào vòng dò.

### 5.2 Còn lại gì

Cách tối ưu nhất vẫn chưa dùng: **two-pointer trên các dãy vị trí**, giống hệt §1
nhưng so $pos_i$ với $pos_0 + i$, cho $O(\sum_i p_i)$ — tuyến tính thay vì tích.

Vì sao chưa đáng làm: phrase search chỉ chạy trên các ứng viên **đã qua phép
giao**, tức thường vài chục tài liệu, và người dùng hiếm khi gõ dấu ngoặc kép.
Sau hai phép vá trên, phần còn lại đã tụt xuống dưới ngưỡng đo được — đây là ví
dụ đúng về **tối ưu theo tần suất sử dụng thật**, và lần này nó là *đánh đổi có
lý do* chứ không còn là *nợ kỹ thuật* như bản trước mô tả.

---

## 6. Chủ đề DSA thể hiện

| Chủ đề | Ở đâu |
|---|---|
| **Two-pointer / merge** | `intersect`, `union` — cùng thuật toán với bước merge của merge sort |
| **Tận dụng bất biến sắp xếp** | toàn bộ lớp phụ thuộc bất biến của `InvertedIndex` |
| **Chứng minh bằng bất biến vòng lặp** | §1.1 |
| **Thuật toán tham lam có chứng minh** | shortest-first, dựa trên $\lvert A \cap B\rvert \le \min$ |
| **Thoát sớm** | `!result.isEmpty()`, `df == 0` |
| **Cục bộ cache** | duyệt tuần tự thắng bảng băm |
| **Big-O tuyệt đối vs khấu hao** | $O(m+n)$ không hằng số ẩn vs $O(1)$ có hằng số ẩn |
| **Khớp mẫu theo vị trí** | `matchesPhrase` |
| **Chi phí autoboxing** | `docIdsOf` tạo `List<Integer>` |

---

## 7. Hạn chế đã biết

1. **`matchesPhrase` gọi lại `getPositions` thừa** và dùng `contains` tuyến tính (xem §5.1).
2. **`docIdsOf` cấp phát và autobox** (xem §3.3).
3. **Không có skip pointer.** Khi giao một list 5 phần tử với list 4.000 phần tử, two-pointer vẫn duyệt gần hết 4.000. Với skip list hoặc **galloping search** (nhân đôi bước nhảy), chi phí về $O(m \log(n/m))$ — với $m=5, n=4000$ là khoảng 48 bước thay vì 4.005.
4. **Chỉ hỗ trợ AND.** `union` có tồn tại nhưng **không được gọi ở đâu cả** — không có toán tử OR trong ngôn ngữ truy vấn. Xem [QueryParser](QueryParser.md).
5. **Không có "khoảng cách gần" (proximity).** Chỉ khớp cụm liền kề tuyệt đối, không hỗ trợ `"máy tính"~3` (cách nhau tối đa 3 từ) như Lucene.
6. **Lớp có constructor mặc định public** dù toàn bộ phương thức là `static` — nên là `final class` với constructor `private`, giống `CandidateResolver` và `UrlCanonicalizer` đã làm đúng.

---

## 8. Liên kết

- Bất biến mà lớp này phụ thuộc: [InvertedIndex §4](../02-index/InvertedIndex.md)
- Người gọi: [CandidateResolver.md](CandidateResolver.md)
- Nguồn của `phraseTerms`: [QueryParser.md](QueryParser.md)
- Cùng dùng bất biến sắp xếp: [TfIdfScorer.md](../04-ranking/TfIdfScorer.md)
- Ký hiệu chưa hiểu: [00 — Từ điển ký hiệu toán](../00-KY-HIEU-TOAN.md)
