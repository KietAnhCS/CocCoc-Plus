package com.vnsearch.crawler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiem thu quy tac gian chu ky ghi diem kiem tra.
 *
 * <p>Bo test nay bao ve mot tinh chat ve HIEU NANG, khong phai ve tinh dung dan:
 * moi lan ghi la ghi lai toan bo corpus, nen neu chu ky khong gian ra thi tong
 * chi phi ca phien la O(n^2). Do la loi da do duoc — thong luong crawl tut 37%
 * giua phien.
 */
class CheckpointCrawlListenerTest {

    private static final int EVERY_N = 250;

    @Test
    @DisplayName("Lan ghi dau tien luon duoc phep")
    void firstCheckpointIsAlwaysAllowed() {
        // Chua co diem kiem tra nao (lastCheckpoint = 0). Neu quy tac gian chu
        // ky chan ca lan dau thi phien crawl ngan se khong co luoi an toan nao.
        assertTrue(CheckpointCrawlListener.isDueForCheckpoint(250, 0, EVERY_N));
    }

    @Test
    @DisplayName("Corpus con nho: van ghi deu moi everyN trang")
    void writesEveryNWhileCorpusIsSmall() {
        // Voi lastCheckpoint = 500, nguong la max(250, 500 * 0,25 = 125) = 250.
        // Tuc khi corpus con nho, everyN moi la rang buoc, dung nhu truoc day.
        assertTrue(CheckpointCrawlListener.isDueForCheckpoint(750, 500, EVERY_N));
        assertFalse(CheckpointCrawlListener.isDueForCheckpoint(700, 500, EVERY_N));
    }

    @Test
    @DisplayName("Corpus lon: nguong gian theo 25% kich thuoc hien tai")
    void thresholdGrowsWithCorpusSize() {
        // lastCheckpoint = 20.000 -> nguong la max(250, 5.000) = 5.000 trang.
        // Day la diem mau chot: o quy mo nay, ghi moi 250 trang la ghi lai
        // 20.000 tai lieu cu chi de them 250 tai lieu moi.
        assertFalse(CheckpointCrawlListener.isDueForCheckpoint(20_250, 20_000, EVERY_N));
        assertFalse(CheckpointCrawlListener.isDueForCheckpoint(24_000, 20_000, EVERY_N));
        assertTrue(CheckpointCrawlListener.isDueForCheckpoint(25_000, 20_000, EVERY_N));
    }

    @Test
    @DisplayName("Ca phien 30.000 trang chi ghi khoang 20 lan, khong phai 120 lan")
    void wholeSessionWritesFarFewerTimes() {
        int lastCheckpoint = 0;
        int writes = 0;

        // Mo phong dung cach CrawlerService goi: chi xet o cac boi so cua everyN.
        for (int pages = EVERY_N; pages <= 30_000; pages += EVERY_N) {
            if (CheckpointCrawlListener.isDueForCheckpoint(pages, lastCheckpoint, EVERY_N)) {
                lastCheckpoint = pages;
                writes++;
            }
        }

        // Chu ky co dinh cu cho 30.000 / 250 = 120 lan ghi.
        assertTrue(writes < 30, "Phai it hon 30 lan ghi, thuc te: " + writes);

        // Nhung van phai du day de con la mot luoi an toan that su.
        assertTrue(writes > 10, "Phai nhieu hon 10 lan ghi, thuc te: " + writes);

        // Va diem kiem tra cuoi phai du gan cuoi phien: mat toi da ~20% cong crawl.
        assertTrue(lastCheckpoint >= 24_000,
                "Diem kiem tra cuoi qua xa cuoi phien: " + lastCheckpoint);
    }
}
