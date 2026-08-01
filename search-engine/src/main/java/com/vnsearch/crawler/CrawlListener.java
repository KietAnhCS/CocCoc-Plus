package com.vnsearch.crawler;

/**
 * <b>Observer pattern</b> — tach viec QUAN SAT mot phien crawl khoi viec
 * THUC THI no.
 *
 * <p><b>Van de cua ban cu.</b> Logic in tien do bi chon thang trong vong lap
 * worker:
 * <pre>
 *   System.out.printf("[%d/%d] %s (depth=%d, %d links, frontier=%d, domains=%d)%n", ...);
 * </pre>
 * Hau qua: khong tat duoc khi chay test (spam output), khong day len WebSocket
 * cho UI theo doi thoi gian thuc, khong ghi ra file de phan tich sau, va khong
 * do duoc (chi co chuoi, khong co so lieu co cau truc).
 *
 * <p><b>Ban nay.</b> {@link CrawlerService} phat su kien; nguoi quan tam tu
 * dang ky. Mot phien crawl co the co nhieu listener cung luc: mot in console,
 * mot cap nhat trang thai job, mot thu thap so lieu.
 *
 * <p>Moi phuong thuc deu la {@code default} rong, nen cai dat chi can ghi de
 * dung su kien minh quan tam.
 */
public interface CrawlListener {

    /** Mot trang da duoc tai va trich xuat thanh cong. */
    default void onPageCrawled(CrawlEvent event) {
    }

    /** Mot URL that bai sau khi da het so lan thu lai. */
    default void onError(String url, Exception error) {
    }

    /**
     * Mot trang bi vut vi TRUNG NOI DUNG voi mot trang da luu — nhanh "Yes"
     * cua khoi {@code Content Seen?} trong so do kien truc.
     *
     * <p>Tach rieng khoi {@link #onError}: day khong phai loi ma la crawler
     * lam dung viec cua no. Nhung van dang theo doi, vi ty le trung cao bat
     * thuong la dau hieu bay nhen (spider trap) hoac chuan hoa URL con thieu.
     */
    default void onDuplicateContent(String url) {
    }

    /** Phien crawl ket thuc (du vi ly do gi: du trang, het viec, hay het gio). */
    default void onFinished(int totalPages, long elapsedMs) {
    }

    /**
     * So lieu co CAU TRUC ve mot trang vua crawl — khac han mot dong log dang
     * chuoi, du lieu nay do duoc va tong hop duoc.
     *
     * @param pageNumber  trang thu may trong phien (bat dau tu 1)
     * @param maxPages    tran so trang cua phien
     * @param url         URL vua crawl
     * @param depth       do sau BFS cua trang nay
     * @param outlinks    so lien ket ra thu duoc
     * @param frontierSize so URL con dang cho
     * @param domainCount so host phan biet con URL cho
     */
    record CrawlEvent(int pageNumber, int maxPages, String url, int depth,
                       int outlinks, int frontierSize, int domainCount) {
    }
}
