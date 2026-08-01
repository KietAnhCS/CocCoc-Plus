package com.vnsearch.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CrawlListener} in tien do ra log — chinh la hanh vi ma truoc day
 * bi chon cung trong vong lap worker.
 *
 * <p>Nay no la MOT trong nhieu listener co the co, va quan trong hon: test co
 * the don gian KHONG dang ky no, thay vi phai chiu spam console.
 *
 * <p>Dung SLF4J thay {@code System.out.printf}: tat duoc theo cau hinh, co
 * phan muc, co timestamp, dinh tuyen ra file duoc.
 */
public final class ConsoleCrawlListener implements CrawlListener {

    private static final Logger log = LoggerFactory.getLogger(ConsoleCrawlListener.class);

    private final int everyN;

    public ConsoleCrawlListener(int everyN) {
        this.everyN = Math.max(1, everyN);
    }

    @Override
    public void onPageCrawled(CrawlEvent e) {
        if (e.pageNumber() % everyN != 0 && e.pageNumber() != e.maxPages()) {
            return;
        }
        log.info("[{}/{}] {} (depth={}, {} links, frontier={}, domains={})",
                e.pageNumber(), e.maxPages(), e.url(), e.depth(),
                e.outlinks(), e.frontierSize(), e.domainCount());
    }

    @Override
    public void onError(String url, Exception error) {
        log.warn("Khong the fetch {}: {}", url, error.getMessage());
    }

    /**
     * O muc DEBUG chu khong phai WARN: vut trang trung noi dung la crawler
     * lam DUNG viec cua no, khong phai su co. In o muc warn se lam nhieu log
     * vi tren bao dien tu ty le trung khong he nho.
     */
    @Override
    public void onDuplicateContent(String url) {
        log.debug("Trung noi dung, bo qua: {}", url);
    }

    @Override
    public void onFinished(int totalPages, long elapsedMs) {
        double seconds = elapsedMs / 1000.0;
        log.info("Ket thuc crawl: {} trang trong {} giay ({} trang/giay)",
                totalPages, String.format("%.1f", seconds),
                String.format("%.2f", seconds > 0 ? totalPages / seconds : 0.0));
    }
}
