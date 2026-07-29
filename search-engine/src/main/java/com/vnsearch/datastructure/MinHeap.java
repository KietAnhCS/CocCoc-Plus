package com.vnsearch.datastructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Min-Heap tu cai dat tren mang (array-based, dung {@link ArrayList} lam
 * bo nho lien tuc), generic voi {@link Comparator} tuy y truyen vao.
 *
 * <p>Bieu dien: phan tu tai chi so {@code i} co con trai o {@code 2i+1},
 * con phai o {@code 2i+2}, cha o {@code (i-1)/2}. Day la cach bieu dien
 * "complete binary tree" chuan cho heap, khong can con tro.
 *
 * <p>Do phuc tap thoi gian:
 * <ul>
 *   <li>{@link #insert(Object)}: O(log n) - them cuoi mang roi siftUp.</li>
 *   <li>{@link #extractMin()}: O(log n) - doi goc voi phan tu cuoi, xoa
 *       cuoi, roi siftDown tu goc.</li>
 *   <li>{@link #peek()}: O(1).</li>
 * </ul>
 * Do phuc tap khong gian: O(n) cho n phan tu dang co trong heap.
 *
 * @param <T> loai phan tu trong heap
 */
public class MinHeap<T> {

    private final List<T> heap = new ArrayList<>();
    private final Comparator<T> comparator;

    public MinHeap(Comparator<T> comparator) {
        this.comparator = comparator;
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
            throw new java.util.NoSuchElementException("Heap rong");
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
            throw new java.util.NoSuchElementException("Heap rong");
        }
        T min = heap.get(0);
        T last = heap.remove(heap.size() - 1);
        if (!heap.isEmpty()) {
            heap.set(0, last);
            siftDown(0);
        }
        return min;
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (comparator.compare(heap.get(i), heap.get(parent)) >= 0) {
                break;
            }
            swap(i, parent);
            i = parent;
        }
    }

    private void siftDown(int i) {
        int n = heap.size();
        while (true) {
            int left = 2 * i + 1;
            int right = 2 * i + 2;
            int smallest = i;
            if (left < n && comparator.compare(heap.get(left), heap.get(smallest)) < 0) {
                smallest = left;
            }
            if (right < n && comparator.compare(heap.get(right), heap.get(smallest)) < 0) {
                smallest = right;
            }
            if (smallest == i) {
                break;
            }
            swap(i, smallest);
            i = smallest;
        }
    }

    private void swap(int i, int j) {
        T tmp = heap.get(i);
        heap.set(i, heap.get(j));
        heap.set(j, tmp);
    }

    /**
     * Lay top-K phan tu "lon nhat" theo {@code cmp} tu mot collection bat ky,
     * KHONG sort toan bo danh sach.
     *
     * <p>Ky thuat: duy tri mot min-heap kich thuoc toi da k. Voi moi phan tu
     * moi, neu heap chua day k thi them vao; neu day roi va phan tu moi lon
     * hon phan tu nho nhat dang co trong heap (dinh cua min-heap) thi thay
     * the. Cuoi cung heap chua dung k phan tu lon nhat, extract lien tuc se
     * ra thu tu tang dan nen dao nguoc lai de duoc thu tu giam dan (lon
     * nhat truoc).
     *
     * <p>Do phuc tap: O(n log k) — voi k &lt;&lt; n thi nhanh hon han sort
     * toan bo O(n log n).
     *
     * @param items danh sach nguon (khong bi sua doi)
     * @param k     so phan tu can lay (neu k &gt;= items.size() thi tra ve
     *              tat ca, da sap xep giam dan)
     * @param cmp   comparator xac dinh thu tu "lon hon"
     * @return danh sach k phan tu lon nhat, sap xep giam dan
     */
    public static <T> List<T> topK(Collection<T> items, int k, Comparator<T> cmp) {
        if (k <= 0) {
            return new ArrayList<>();
        }
        MinHeap<T> heap = new MinHeap<>(cmp);
        for (T item : items) {
            if (heap.size() < k) {
                heap.insert(item);
            } else if (cmp.compare(item, heap.peek()) > 0) {
                heap.extractMin();
                heap.insert(item);
            }
        }
        List<T> result = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) {
            result.add(heap.extractMin());
        }
        java.util.Collections.reverse(result);
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

        List<Integer> top3 = topK(values, 3, Comparator.naturalOrder());
        System.out.println("Top-3 lon nhat (khong sort toan bo): " + top3);
    }
}
