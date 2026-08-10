package com.vnsearch.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * So lieu su dung: gom phien, xep hang truy van, vong dem 24 gio, va cac tran
 * bo nho — thu duy nhat dung giua mot endpoint CONG KHAI va heap cua tien trinh.
 */
class UsageAnalyticsServiceTest {

    /** Dong ho dieu khien duoc: khong co no thi khong kiem duoc vong dem gio. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MovableClock clock;
    private UsageAnalyticsService service;

    @BeforeEach
    void setUp() {
        clock = new MovableClock(Instant.parse("2026-08-10T10:00:00Z"));
        service = new UsageAnalyticsService(clock);
    }

    @Test
    void demLuotTimVaLuotBamThanhTiLeCtr() {
        service.recordSearch("s1", "ha noi", 12, 30);
        service.recordSearch("s1", "hue", 5, 20);
        service.recordClick("s1", "https://vnexpress.net/a", 1);

        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(2, snapshot.searches());
        assertEquals(1, snapshot.clicks());
        assertEquals(0.5, snapshot.clickThroughRate(), 1e-9);
    }

    /** Chua co luot tim nao thi CTR phai la 0, khong phai NaN. */
    @Test
    void khongChiaChoKhongKhiChuaCoDuLieu() {
        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(0.0, snapshot.clickThroughRate());
        assertEquals(0.0, snapshot.zeroResultRate());
        assertEquals(0.0, snapshot.avgLatencyMs());
        assertEquals(0.0, snapshot.avgSessionMinutes());
    }

    @Test
    void moiMaPhienLaMotNguoiTruyCap() {
        service.recordVisit("s1");
        service.recordVisit("s1");
        service.recordVisit("s2");

        assertEquals(2, service.snapshot(10).visitors());
    }

    /**
     * Chuan hoa truy van: ba cach go cung mot thu phai gop lam mot dong, neu
     * khong khong dong nao lot duoc vao bang xep hang.
     */
    @Test
    void gopTruyVanKhacHoaVaKhoangTrang() {
        service.recordSearch("s1", "Ha Noi", 3, 10);
        service.recordSearch("s1", "  ha noi ", 3, 10);
        service.recordSearch("s2", "ha  noi", 3, 10);

        List<UsageSnapshot.Counted> top = service.snapshot(10).topQueries();

        assertEquals(1, top.size());
        assertEquals("ha noi", top.get(0).label());
        assertEquals(3, top.get(0).count());
    }

    @Test
    void xepHangLienKetTheoSoLuotBamVaThuHangTrungBinh() {
        service.recordClick("s1", "https://vnexpress.net/a", 1);
        service.recordClick("s2", "https://vnexpress.net/a", 3);
        service.recordClick("s3", "https://tuoitre.vn/b", 2);

        List<UsageSnapshot.LinkCount> links = service.snapshot(10).topLinks();

        assertEquals("https://vnexpress.net/a", links.get(0).url());
        assertEquals(2, links.get(0).count());
        assertEquals(2.0, links.get(0).position(), 1e-9); // (1 + 3) / 2
        assertEquals("vnexpress.net", links.get(0).host());
    }

    @Test
    void gopLienKetTheoTenMien() {
        service.recordClick("s1", "https://www.vnexpress.net/a", 1);
        service.recordClick("s1", "https://vnexpress.net/b", 2);
        service.recordClick("s1", "https://tuoitre.vn/c", 1);

        List<UsageSnapshot.Counted> hosts = service.snapshot(10).topHosts();

        assertEquals("vnexpress.net", hosts.get(0).label());
        assertEquals(2, hosts.get(0).count());
    }

    @Test
    void demTruyVanKhongCoKetQua() {
        service.recordSearch("s1", "co ket qua", 7, 10);
        service.recordSearch("s1", "khong ket qua", 0, 10);

        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(1, snapshot.zeroResultSearches());
        assertEquals(0.5, snapshot.zeroResultRate(), 1e-9);
    }

    /** Do tre am (may khach khong biet) khong duoc lam lech trung binh. */
    @Test
    void boQuaDoTreKhongHopLe() {
        service.recordSearch("s1", "a", 1, 100);
        service.recordSearch("s1", "b", 1, -1);

        assertEquals(100.0, service.snapshot(10).avgLatencyMs(), 1e-9);
    }

    @Test
    void phanBoDoTreRoiDungKhoang() {
        service.recordSearch("s1", "a", 1, 5);      // < 10 ms
        service.recordSearch("s1", "b", 1, 150);    // 100-200 ms
        service.recordSearch("s1", "c", 1, 5_000);  // > 1 s

        List<UsageSnapshot.LatencyBucket> latency = service.snapshot(10).latency();

        assertEquals(1, latency.get(0).count());
        assertEquals(1, latency.get(3).count());
        assertEquals(1, latency.get(latency.size() - 1).count());
    }

    @Test
    void chuoiGioLuonDu24DiemVaDiemCuoiLaGioHienTai() {
        service.recordSearch("s1", "a", 1, 10);

        List<UsageSnapshot.HourPoint> hourly = service.snapshot(10).hourly();

        assertEquals(UsageAnalyticsService.HOURS_TRACKED, hourly.size());
        assertEquals(1, hourly.get(hourly.size() - 1).searches());
        assertEquals(0, hourly.get(0).searches());
    }

    /**
     * Vong dem 24 o: sau tron mot ngay, o cu bi ghi de chu khong duoc phep
     * hien lai nhu so lieu cua gio hien tai.
     */
    @Test
    void oGioCuBiGhiDeSauMotNgay() {
        service.recordSearch("s1", "a", 1, 10);
        clock.advance(Duration.ofHours(24));
        service.recordSearch("s2", "b", 1, 10);

        List<UsageSnapshot.HourPoint> hourly = service.snapshot(10).hourly();

        assertEquals(1, hourly.get(hourly.size() - 1).searches());
        // Cung o vat ly, nhung thuoc gio KHAC -> phai bang 0.
        assertEquals(0, hourly.get(0).searches());
        assertEquals(0, hourly.get(0).visitors());
    }

    /** Gio bi bo trong phai hien 0, khong duoc bien mat khoi truc thoi gian. */
    @Test
    void gioKhongCoHoatDongVanCoMatVoiGiaTriKhong() {
        service.recordSearch("s1", "a", 1, 10);
        clock.advance(Duration.ofHours(2));

        List<UsageSnapshot.HourPoint> hourly = service.snapshot(10).hourly();

        assertEquals(0, hourly.get(hourly.size() - 1).searches());
        assertEquals(0, hourly.get(hourly.size() - 2).searches());
        assertEquals(1, hourly.get(hourly.size() - 3).searches());
    }

    @Test
    void phienNgungHoatDongKhongConDuocTinhLaDangHoatDong() {
        service.recordVisit("s1");
        clock.advance(Duration.ofMinutes(UsageAnalyticsService.ACTIVE_WINDOW_MINUTES + 1));
        service.recordVisit("s2");

        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(2, snapshot.visitors());
        assertEquals(1, snapshot.activeVisitors());
    }

    @Test
    void thoiLuongPhienLaKhoangCachGiuaSuKienDauVaCuoi() {
        service.recordVisit("s1");
        clock.advance(Duration.ofMinutes(6));
        service.recordSearch("s1", "a", 1, 10);

        assertEquals(6.0, service.snapshot(10).avgSessionMinutes(), 1e-9);
    }

    /**
     * Tran bang truy van: day la lop bao ve giua mot endpoint cong khai va heap.
     * Vuot tran thi so lieu THIEU (va noi ra), chu khong duoc phep lon vo han.
     */
    @Test
    void banTruyVanCoTranVaBaoLaDaCat() {
        for (int i = 0; i <= UsageAnalyticsService.MAX_TRACKED_QUERIES; i++) {
            service.recordSearch("s1", "truy van so " + i, 1, 10);
        }

        UsageSnapshot snapshot = service.snapshot(10);

        assertTrue(snapshot.truncated(), "phai bao la bang da cham tran");
        // Tong luot tim VAN dung — chi bang xep hang thieu muc, khong phai bo dem.
        assertEquals(UsageAnalyticsService.MAX_TRACKED_QUERIES + 1, snapshot.searches());
    }

    @Test
    void banLienKetCoTran() {
        for (int i = 0; i <= UsageAnalyticsService.MAX_TRACKED_LINKS; i++) {
            service.recordClick("s1", "https://example.com/" + i, 1);
        }

        assertTrue(service.snapshot(10).truncated());
    }

    @Test
    void duLieuRacKhongLamHongGiGiaCa() {
        service.recordSearch("s1", "   ", 1, 10);   // truy van rong
        service.recordSearch(null, "a", 1, 10);      // khong co ma phien
        service.recordClick("s1", "khong-phai-url", 1);
        service.recordClick("s1", null, 1);

        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(2, snapshot.searches());
        assertEquals(1, snapshot.clicks());          // lan goi voi url null bi bo
        assertEquals("(không rõ)", snapshot.topLinks().get(0).host());
        assertTrue(snapshot.topQueries().stream().noneMatch(q -> q.label().isBlank()));
    }

    @Test
    void datLaiXoaSachSoLieu() {
        service.recordSearch("s1", "a", 1, 10);
        service.recordClick("s1", "https://a.com/1", 1);

        service.reset();
        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals(0, snapshot.searches());
        assertEquals(0, snapshot.clicks());
        assertEquals(0, snapshot.visitors());
        assertTrue(snapshot.topQueries().isEmpty());
        assertFalse(snapshot.truncated());
    }

    /**
     * Duong ghi nam tren MOI luot tim kiem cua MOI nguoi dung, nen no that su
     * chay da luong. Bai nay khong chung minh duoc khong co dua tranh, nhung no
     * bat duoc loai loi de gap nhat: mot bo dem khong nguyen tu lam mat so dem.
     */
    @Test
    void demDungKhiNhieuLuongCungGhi() throws InterruptedException {
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    service.recordSearch("s" + id, "truy van chung", 3, 12);
                    service.recordClick("s" + id, "https://example.com/a", 1);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "pool phai ket thuc");

        UsageSnapshot snapshot = service.snapshot(10);

        assertEquals((long) threads * perThread, snapshot.searches());
        assertEquals((long) threads * perThread, snapshot.clicks());
        assertEquals(threads, snapshot.visitors());
        assertEquals((long) threads * perThread, snapshot.topQueries().get(0).count());
    }
}
