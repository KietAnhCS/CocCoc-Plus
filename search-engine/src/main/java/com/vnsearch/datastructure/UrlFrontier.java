package com.vnsearch.datastructure;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hang doi URL cho crawler, co uu tien, xay tren {@link MinHeap} tu cai o
 * tren (khong dung {@code java.util.PriorityQueue} co san).
 *
 * <p>Diem uu tien = f(do sau, so backlink da biet, domain co phai .vn
 * khong) — trang cang nong (nhieu backlink), cang gan seed (do sau nho),
 * domain .vn (uu tien theo yeu cau de bai) thi cang duoc crawl som.
 *
 * <p>Ky thuat tai su dung MinHeap: MinHeap luon extractMin() ra phan tu
 * NHO NHAT, nhung ta muon lay ra phan tu co priority CAO NHAT truoc. Giai
 * phap: sap xep heap theo {@code -priority} thay vi {@code priority} —
 * phan tu co priority cao nhat se co {@code -priority} nho nhat, nen van
 * la phan tu extractMin() tra ve dau tien. Day la ky thuat pho bien de
 * bien mot min-heap thanh "max-heap theo tieu chi X" ma khong can viet lai
 * cau truc heap.
 *
 * <p>Chong crawl don dap 1 domain: giu {@code Map<String domain, Long lastAccessTime>},
 * ap dung politeness delay toi thieu {@link #POLITENESS_DELAY_MS} giua 2
 * lan lay URL cua CUNG mot domain. Khi phan tu uu tien nhat hien tai
 * thuoc domain vua duoc truy cap gan day, {@link #nextUrl()} se tam thoi
 * "gac" no lai (dua vao danh sach skipped), tim phan tu ke tiep thuoc
 * domain khac dang duoc phep, roi tra tat ca phan tu bi gac lai vao heap.
 *
 * <p>Thread-safe: toan bo thao tac doc/ghi cau truc du lieu noi bo deu
 * nam trong khoi {@code synchronized}, cho phep nhieu crawler thread goi
 * dong thoi.
 *
 * <p>Do phuc tap thoi gian: {@link #addUrl} O(log n); {@link #nextUrl}
 * O(d log n) voi d la so phan tu bi tam gac do dang trong politeness
 * delay (thuong nho trong thuc te).
 */
public class UrlFrontier {

    /** Khoang cach toi thieu giua 2 lan truy cap cung 1 domain. */
    public static final long POLITENESS_DELAY_MS = 1000L;

    /** Mot URL can crawl, kem do sau (dung de gioi han maxDepth o CrawlerService). */
    public record Task(String url, int depth) {
    }

    private record FrontierEntry(String url, int depth, double priority) {
    }

    private final MinHeap<FrontierEntry> heap =
            new MinHeap<>(Comparator.comparingDouble(e -> -e.priority()));
    private final Set<String> enqueued = new HashSet<>();
    private final Map<String, Long> lastAccessTime = new HashMap<>();
    private final Object lock = new Object();

    /** O(log n) - them URL moi vao hang doi neu chua co (theo do sau + so backlink da biet). */
    public void addUrl(String url, int depth, int knownBacklinks) {
        synchronized (lock) {
            if (enqueued.contains(url)) {
                return;
            }
            double priority = computePriority(url, depth, knownBacklinks);
            heap.insert(new FrontierEntry(url, depth, priority));
            enqueued.add(url);
        }
    }

    private double computePriority(String url, int depth, int knownBacklinks) {
        double score = 0;
        score -= depth * 2.0; // cang sau cang it uu tien
        score += Math.min(knownBacklinks, 50) * 0.5; // nhieu backlink -> uu tien hon
        if (isVnDomain(url)) {
            score += 5.0; // uu tien domain .vn theo yeu cau de bai
        }
        return score;
    }

    private boolean isVnDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.endsWith(".vn");
        } catch (Exception e) {
            return false;
        }
    }

    private String extractDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Lay URL uu tien nhat hien co ma domain cua no khong vi pham
     * politeness delay. Blocking: neu tat ca cac URL dang cho deu thuoc
     * domain vua truy cap gan day, thread se ngu ngan (50ms) roi thu lai.
     * Tra ve {@code null} khi hang doi that su rong (bao hieu co the
     * dung crawler).
     */
    public Task nextUrl() {
        while (true) {
            synchronized (lock) {
                if (heap.isEmpty()) {
                    return null;
                }
                List<FrontierEntry> skipped = new ArrayList<>();
                FrontierEntry chosen = null;
                while (!heap.isEmpty()) {
                    FrontierEntry entry = heap.extractMin();
                    String domain = extractDomain(entry.url());
                    long now = System.currentTimeMillis();
                    Long last = lastAccessTime.get(domain);
                    if (last == null || now - last >= POLITENESS_DELAY_MS) {
                        lastAccessTime.put(domain, now);
                        chosen = entry;
                        enqueued.remove(entry.url());
                        break;
                    } else {
                        skipped.add(entry);
                    }
                }
                for (FrontierEntry e : skipped) {
                    heap.insert(e);
                }
                if (chosen != null) {
                    return new Task(chosen.url(), chosen.depth());
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    public int size() {
        synchronized (lock) {
            return heap.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return heap.isEmpty();
        }
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        UrlFrontier frontier = new UrlFrontier();
        frontier.addUrl("https://example.com/a", 1, 2);
        frontier.addUrl("https://congty.gov.vn/b", 1, 2); // domain .vn that -> +5 diem uu tien
        frontier.addUrl("https://example.com/c", 3, 0); // sau hon -> it uu tien hon

        Task t1 = frontier.nextUrl();
        System.out.println("Task 1 (uu tien cao nhat, phai la domain .vn): " + t1);
        Task t2 = frontier.nextUrl();
        System.out.println("Task 2: " + t2);
        Task t3 = frontier.nextUrl();
        System.out.println("Task 3 (sau nhat -> it uu tien nhat): " + t3);
    }
}
