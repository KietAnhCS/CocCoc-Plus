package com.vnsearch.datastructure;

/**
 * TODO (PHASE 2): Min-Heap tu cai dat tren mang (array-based), generic voi
 * Comparator, dung de lay top-K ket qua co diem cao nhat ma khong can sort
 * toan bo danh sach.
 *
 * Method can cai dat:
 *   - void insert(T item)     O(log n)
 *   - T extractMin()          O(log n)
 *   - T peek()                O(1)
 *   - void siftUp(int i) / siftDown(int i)  O(log n)
 *   - static <T> List<T> topK(Collection<T> items, int k, Comparator<T> cmp)
 *       Duy tri heap kich thuoc k trong luc duyet qua n phan tu -> O(n log k),
 *       tot hon han sort toan bo O(n log n) khi k << n.
 *
 * @param <T> loai phan tu trong heap
 */
public class MinHeap<T> {
    // TODO: implement in PHASE 2
}
