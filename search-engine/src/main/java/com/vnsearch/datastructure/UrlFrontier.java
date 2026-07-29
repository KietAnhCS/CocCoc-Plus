package com.vnsearch.datastructure;

/**
 * TODO (PHASE 3): Hang doi URL cho crawler, co uu tien (priority queue),
 * xay tren MinHeap tu cai o tren.
 *
 * Diem uu tien = f(do sau, so backlink da biet, domain co phai .vn khong).
 *
 * Chong crawl don dap 1 domain: giu Map<String domain, Long lastAccessTime>,
 * ap dung politeness delay toi thieu 1s/domain truoc khi tra URL tiep theo
 * cua cung domain do.
 *
 * Phai thread-safe vi nhieu crawler thread se cung lay URL tu frontier nay.
 *
 * Method du kien:
 *   - void addUrl(String url, int depth, double priority)   O(log n)
 *   - String nextUrl()  (blocking neu can cho politeness delay)  O(log n)
 *   - boolean isEmpty() / int size()
 */
public class UrlFrontier {
    // TODO: implement in PHASE 3
}
