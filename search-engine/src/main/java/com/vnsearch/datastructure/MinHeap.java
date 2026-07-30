package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Min-Heap tu cai dat tren mang (array-based, dung {@link ArrayList} lam
 * bo nho lien tuc), generic voi {@link Comparator} tuy y truyen vao.
 *
 * <p>Bieu dien: phan tu tai chi so {@code i} co con trai o {@code 2i+1},
 * con phai o {@code 2i+2}, cha o {@code (i-1)/2}. Day la cach bieu dien
 * "complete binary tree" chuan cho heap, khong can con tro.
 *
 * <p><b>Toi uu "hole" (lo trong):</b> {@link #siftUp} va {@link #siftDown}
 * KHONG dung {@code swap}. Thay vao do chung giu gia tri can di chuyen
 * trong mot bien tam, chi KEO phan tu tren duong di vao cho trong, roi dat
 * gia tri dung MOT lan o cuoi. Tiet kiem ~2/3 so phep gan:
 * <pre>
 *   swap  : 3 gan moi buoc      -> 3*log n
 *   "hole": 1 gan moi buoc + 1  -> log n + 1
 * </pre>
 * Day cung la ky thuat ma {@code java.util.PriorityQueue} cua JDK dung.
 *
 * <p><b>Dung heap tu mot tap co san:</b> constructor
 * {@link #MinHeap(Collection, Comparator)} dung thuat toan <b>Floyd
 * heapify</b> — siftDown tu chi so {@code n/2 - 1} lui ve 0 — cho ra
 * {@code O(n)} thay vi {@code O(n log n)} neu insert lan luot tung phan tu.
 * Chung minh: tong chi phi la
 * {@code sum_{h=0..log n} (n/2^(h+1)) * h = n * sum h/2^(h+1) <= 2n}.
 *
 * <p>Do phuc tap thoi gian:
 * <ul>
 *   <li>{@link #insert(Object)}: O(log n)</li>
 *   <li>{@link #extractMin()}: O(log n)</li>
 *   <li>{@link #peek()}: O(1)</li>
 *   <li>{@link #MinHeap(Collection, Comparator)}: <b>O(n)</b> (Floyd heapify)</li>
 *   <li>{@link #topK}: O(n log k), bo nho O(k)</li>
 * </ul>
 * Do phuc tap khong gian: O(n) cho n phan tu dang co trong heap.
 *
 * <p><b>Khong thread-safe.</b> Nguoi goi phai tu dong bo neu dung tu nhieu
 * thread (vi du {@link UrlFrontier} boc moi thao tac trong khoi
 * {@code synchronized}).
 *
 * @param <T> loai phan tu trong heap
 */
public class MinHeap<T> {

    private final List<T> heap;
    private final Comparator<T> comparator;

    public MinHeap(Comparator<T> comparator) {
        this.comparator = Objects.requireNonNull(comparator, "comparator khong duoc null");
        this.heap = new ArrayList<>();
    }

    /**
     * <b>O(n)</b> - dung heap tu mot tap phan tu co san bang thuat toan Floyd
     * heapify, nhanh hon han viec goi {@link #insert} n lan ({@code O(n log n)}).
     */
    public MinHeap(Collection<? extends T> items, Comparator<T> comparator) {
        this.comparator = Objects.requireNonNull(comparator, "comparator khong duoc null");
        this.heap = new ArrayList<>(Objects.requireNonNull(items, "items khong duoc null"));
        heapify();
    }

    /** O(n) - Floyd heapify: siftDown tu node trong cung cuoi cung lui ve goc. */
    private void heapify() {
        // Chi so >= size/2 deu la la (khong co con) nen khong can siftDown.
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

    /** O(1) - xem phan tu nho nhat ma khong xoa. */
    public T peek() {
        if (heap.isEmpty()) {
            throw new NoSuchElementException("Heap rong");
        }
        return heap.get(0);
    }

    /** O(log n) - them phan tu moi va khoi phuc tinh chat heap. */
    public void insert(T item) {
        heap.add(item);
        siftUp(heap.size() - 1);
    }

    /** O(log n) - lay va xoa phan tu nho nhat. */
    public T extractMin() {
        if (heap.isEmpty()) {
            throw new NoSuchElementException("Heap rong");
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
     * Toi uu "hole": giu {@code item} trong bien tam, chi KEO cha xuong cho
     * trong, dat {@code item} dung mot lan o cuoi. 1 phep gan moi buoc thay
     * vi 3 cua {@code swap}.
     */
    private void siftUp(int index) {
        T item = heap.get(index);
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            T parentItem = heap.get(parent);
            if (comparator.compare(item, parentItem) >= 0) {
                break; // da dung cho
            }
            heap.set(index, parentItem); // keo cha xuong lap "lo"
            index = parent;
        }
        heap.set(index, item); // dat MOT lan duy nhat
    }

    /**
     * Toi uu "hole" cho chieu nguoc lai. Vong lap chan bang
     * {@code index < n/2} vi moi node co chi so {@code >= n/2} deu la la:
     * neu {@code i >= floor(n/2)} thi {@code 2i+1 >= n}, tuc khong co con trai.
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
                    child = right; // phai chon con NHO hon, neu khong vi pham min-heap
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
     * Lay top-K phan tu "lon nhat" theo {@code cmp} tu mot collection bat ky,
     * KHONG sort toan bo danh sach.
     *
     * <p>Ky thuat: duy tri mot min-heap kich thuoc toi da k chua k phan tu LON
     * NHAT da gap. Dinh cua min-heap chinh la NGUONG CUA de lot vao top-k, va
     * doc no la O(1) — day la ly do dung MIN-heap de tim phan tu LON nhat.
     * Voi moi phan tu moi: neu chua day k thi them; neu day roi va phan tu moi
     * lon hon nguong thi thay the; nguoc lai bo qua ngay (chi 1 phep so sanh).
     *
     * <p><b>Toi uu:</b> k phan tu dau duoc gom vao mot lan roi
     * {@link #heapify} O(k), thay vi insert k lan ({@code O(k log k)}).
     *
     * <p>Do phuc tap: <b>O(n log k)</b> thoi gian, <b>O(k)</b> bo nho — voi
     * k &lt;&lt; n thi nhanh hon han sort toan bo O(n log n), va bo nho khong
     * phu thuoc n nen chay duoc tren luong du lieu rat lon.
     *
     * @param items danh sach nguon (khong bi sua doi)
     * @param k     so phan tu can lay (neu k &gt;= items.size() thi tra ve
     *              tat ca, da sap xep giam dan)
     * @param cmp   comparator xac dinh thu tu "lon hon"
     * @return danh sach k phan tu lon nhat, sap xep giam dan
     */
    public static <T> List<T> topK(Collection<T> items, int k, Comparator<T> cmp) {
        if (k <= 0 || items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        // Gom k phan tu dau roi heapify MOT lan: O(k) thay vi O(k log k).
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
            // Dau ">" chat: phan tu BANG nguong thi bo qua, tiet kiem mot cap
            // extractMin+insert (2 log k) ma ket qua van hop le.
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
            result.add(heap.extractMin()); // ra theo thu tu TANG dan
        }
        java.util.Collections.reverse(result); // dao lai thanh GIAM dan
        return result;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        MinHeap<Integer> minHeap = new MinHeap<Integer>(Comparator.naturalOrder());
        List<Integer> values = java.util.Arrays.asList(5, 3, 8, 1, 9, 2, 7);
        for (int v : values) {
            minHeap.insert(v);
        }
        System.out.print("Extract theo thu tu tang dan: ");
        while (!minHeap.isEmpty()) {
            System.out.print(minHeap.extractMin() + " ");
        }
        System.out.println();

        // Floyd heapify O(n) thay vi insert n lan O(n log n)
        MinHeap<Integer> built = new MinHeap<>(values, Comparator.naturalOrder());
        System.out.println("Heapify O(n) -> min = " + built.peek());

        List<Integer> top3 = topK(values, 3, Comparator.naturalOrder());
        System.out.println("Top-3 lon nhat (khong sort toan bo): " + top3);
    }
}
