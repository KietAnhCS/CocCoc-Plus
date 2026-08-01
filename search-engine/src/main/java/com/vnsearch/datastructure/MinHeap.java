package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Min-Heap tự cài đặt trên mảng (array-based, dùng {@link ArrayList} làm bộ
 * nhớ liên tục), tổng quát với {@link Comparator} tuỳ ý truyền vào.
 *
 * <p>Biểu diễn: phần tử tại chỉ số {@code i} có con trái ở {@code 2i+1}, con
 * phải ở {@code 2i+2}, cha ở {@code (i-1)/2}. Đây là cách biểu diễn "cây nhị
 * phân đầy đủ" chuẩn cho heap — toàn bộ cấu trúc cây nằm gọn trong một mảng,
 * không cần một con trỏ nào.
 *
 * <p><b>Tối ưu "hole" (lỗ trống).</b> {@link #siftUp} và {@link #siftDown}
 * KHÔNG dùng {@code swap}. Thay vào đó chúng giữ giá trị cần di chuyển trong
 * một biến tạm, chỉ KÉO phần tử trên đường đi vào chỗ trống, rồi đặt giá trị
 * đúng MỘT lần ở cuối. Tiết kiệm khoảng 2/3 số phép gán:
 * <pre>
 *   swap  : 3 gán mỗi bước      -&gt; 3*log n
 *   "hole": 1 gán mỗi bước + 1  -&gt; log n + 1
 * </pre>
 * Đây cũng là kỹ thuật mà {@code java.util.PriorityQueue} của JDK dùng.
 *
 * <p><b>Dựng heap từ một tập có sẵn.</b> Constructor
 * {@link #MinHeap(Collection, Comparator)} dùng thuật toán <b>Floyd
 * heapify</b> — siftDown từ chỉ số {@code n/2 - 1} lùi về 0 — cho ra
 * {@code O(n)} thay vì {@code O(n log n)} nếu chèn lần lượt từng phần tử.
 * Chứng minh: tổng chi phí là
 * {@code sum_{h=0..log n} (n/2^(h+1)) * h = n * sum h/2^(h+1) <= 2n}.
 *
 * <p>Độ phức tạp thời gian:
 * <ul>
 *   <li>{@link #insert(Object)}: O(log n)</li>
 *   <li>{@link #extractMin()}: O(log n)</li>
 *   <li>{@link #peek()}: O(1)</li>
 *   <li>{@link #MinHeap(Collection, Comparator)}: <b>O(n)</b> (Floyd heapify)</li>
 *   <li>{@link #topK}: O(n log k), bộ nhớ O(k)</li>
 * </ul>
 * Độ phức tạp không gian: O(n) cho n phần tử đang có trong heap.
 *
 * <p><b>Không thread-safe.</b> Người gọi phải tự đồng bộ nếu dùng từ nhiều
 * luồng (ví dụ {@code UrlFrontier} của crawler bọc mọi thao tác trong khối
 * {@code synchronized}).
 *
 * @param <T> loại phần tử trong heap
 */
public class MinHeap<T> {

    private final List<T> heap;
    private final Comparator<T> comparator;

    public MinHeap(Comparator<T> comparator) {
        this.comparator = Objects.requireNonNull(comparator, "comparator không được null");
        this.heap = new ArrayList<>();
    }

    /**
     * <b>O(n)</b> — dựng heap từ một tập phần tử có sẵn bằng thuật toán Floyd
     * heapify, nhanh hơn hẳn việc gọi {@link #insert} n lần ({@code O(n log n)}).
     */
    public MinHeap(Collection<? extends T> items, Comparator<T> comparator) {
        this.comparator = Objects.requireNonNull(comparator, "comparator không được null");
        this.heap = new ArrayList<>(Objects.requireNonNull(items, "items không được null"));
        heapify();
    }

    /** O(n) — Floyd heapify: siftDown từ node trong cùng cuối cùng lùi về gốc. */
    private void heapify() {
        // Chỉ số >= size/2 đều là lá (không có con) nên không cần siftDown.
        for (int i = (heap.size() >>> 1) - 1; i >= 0; i--) {
            siftDown(i);
        }
    }

    public int size() {
        return heap.size();
    }

    public boolean isEmpty() {
        return heap.isEmpty();
    }

    /** O(1) — xem phần tử nhỏ nhất mà không xoá. */
    public T peek() {
        if (heap.isEmpty()) {
            throw new NoSuchElementException("Heap rỗng");
        }
        return heap.get(0);
    }

    /** O(log n) — thêm phần tử mới và khôi phục tính chất heap. */
    public void insert(T item) {
        heap.add(item);
        siftUp(heap.size() - 1);
    }

    /** O(log n) — lấy và xoá phần tử nhỏ nhất. */
    public T extractMin() {
        if (heap.isEmpty()) {
            throw new NoSuchElementException("Heap rỗng");
        }
        T min = heap.get(0);
        T last = heap.remove(heap.size() - 1);
        if (!heap.isEmpty()) {
            heap.set(0, last);
            siftDown(0);
        }
        return min;
    }

    /**
     * Tối ưu "hole": giữ {@code item} trong biến tạm, chỉ KÉO cha xuống chỗ
     * trống, đặt {@code item} đúng một lần ở cuối. 1 phép gán mỗi bước thay vì
     * 3 của {@code swap}.
     */
    private void siftUp(int index) {
        T item = heap.get(index);
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            T parentItem = heap.get(parent);
            if (comparator.compare(item, parentItem) >= 0) {
                break; // đã đúng chỗ
            }
            heap.set(index, parentItem); // kéo cha xuống lấp "lỗ"
            index = parent;
        }
        heap.set(index, item); // đặt MỘT lần duy nhất
    }

    /**
     * Tối ưu "hole" cho chiều ngược lại. Vòng lặp chặn bằng
     * {@code index < n/2} vì mọi node có chỉ số {@code >= n/2} đều là lá:
     * nếu {@code i >= floor(n/2)} thì {@code 2i+1 >= n}, tức không có con trái.
     */
    private void siftDown(int index) {
        int n = heap.size();
        int half = n >>> 1;
        T item = heap.get(index);
        while (index < half) {
            int child = 2 * index + 1;
            int right = child + 1;
            T childItem = heap.get(child);
            if (right < n) {
                T rightItem = heap.get(right);
                if (comparator.compare(rightItem, childItem) < 0) {
                    child = right; // phải chọn con NHỎ hơn, nếu không vi phạm min-heap
                    childItem = rightItem;
                }
            }
            if (comparator.compare(childItem, item) >= 0) {
                break;
            }
            heap.set(index, childItem);
            index = child;
        }
        heap.set(index, item);
    }

    /**
     * Lấy top-K phần tử "lớn nhất" theo {@code cmp} từ một collection bất kỳ,
     * KHÔNG sắp xếp toàn bộ danh sách.
     *
     * <p>Kỹ thuật: duy trì một min-heap kích thước tối đa k chứa k phần tử LỚN
     * NHẤT đã gặp. Đỉnh của min-heap chính là NGƯỠNG CỬA để lọt vào top-k, và
     * đọc nó là O(1) — đây là lý do dùng MIN-heap để tìm phần tử LỚN nhất.
     * Với mỗi phần tử mới: nếu chưa đầy k thì thêm; nếu đầy rồi và phần tử mới
     * lớn hơn ngưỡng thì thay thế; ngược lại bỏ qua ngay (chỉ 1 phép so sánh).
     *
     * <p><b>Tối ưu:</b> k phần tử đầu được gom vào một lần rồi
     * {@link #heapify} O(k), thay vì chèn k lần ({@code O(k log k)}).
     *
     * <p>Độ phức tạp: <b>O(n log k)</b> thời gian, <b>O(k)</b> bộ nhớ — với
     * k &lt;&lt; n thì nhanh hơn hẳn sắp xếp toàn bộ O(n log n), và bộ nhớ
     * không phụ thuộc n nên chạy được trên luồng dữ liệu rất lớn.
     *
     * @param items danh sách nguồn (không bị sửa đổi)
     * @param k     số phần tử cần lấy (nếu k &gt;= items.size() thì trả về
     *              tất cả, đã sắp xếp giảm dần)
     * @param cmp   comparator xác định thứ tự "lớn hơn"
     * @return danh sách k phần tử lớn nhất, sắp xếp giảm dần
     */
    public static <T> List<T> topK(Collection<T> items, int k, Comparator<T> cmp) {
        if (k <= 0 || items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        // Gom k phần tử đầu rồi heapify MỘT lần: O(k) thay vì O(k log k).
        List<T> seed = new ArrayList<>(Math.min(k, items.size()));
        MinHeap<T> heap = null;
        for (T item : items) {
            if (heap == null) {
                seed.add(item);
                if (seed.size() == k) {
                    heap = new MinHeap<>(seed, cmp);
                }
                continue;
            }
            // Dấu ">" chặt: phần tử BẰNG ngưỡng thì bỏ qua, tiết kiệm một cặp
            // extractMin+insert (2 log k) mà kết quả vẫn hợp lệ.
            if (cmp.compare(item, heap.peek()) > 0) {
                heap.extractMin();
                heap.insert(item);
            }
        }
        if (heap == null) {
            heap = new MinHeap<>(seed, cmp); // items.size() < k
        }

        List<T> result = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            result.add(heap.extractMin()); // ra theo thứ tự TĂNG dần
        }
        java.util.Collections.reverse(result); // đảo lại thành GIẢM dần
        return result;
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        MinHeap<Integer> minHeap = new MinHeap<Integer>(Comparator.naturalOrder());
        List<Integer> values = java.util.Arrays.asList(5, 3, 8, 1, 9, 2, 7);
        for (int v : values) {
            minHeap.insert(v);
        }
        System.out.print("Extract theo thứ tự tăng dần: ");
        while (!minHeap.isEmpty()) {
            System.out.print(minHeap.extractMin() + " ");
        }
        System.out.println();

        // Floyd heapify O(n) thay vì chèn n lần O(n log n)
        MinHeap<Integer> built = new MinHeap<>(values, Comparator.naturalOrder());
        System.out.println("Heapify O(n) -> min = " + built.peek());

        List<Integer> top3 = topK(values, 3, Comparator.naturalOrder());
        System.out.println("Top-3 lớn nhất (không sắp xếp toàn bộ): " + top3);
    }
}
