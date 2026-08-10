package com.vnsearch.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kho phien: sinh token, het han, va — thu ma JWT khong lam duoc —
 * <b>thu hoi tuc thi</b>.
 */
class SessionStoreTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-10T10:00:00Z");

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
    private SessionStore store;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        store = new SessionStore(clock);
    }

    private static User user(String name, Role role) {
        return new User(name, "$2a$12$hashgia", role, true, Instant.EPOCH, null);
    }

    @Test
    void moPhienRoiTraCuuDuocVaiTro() {
        String token = store.open(user("quantri", Role.ADMIN));

        SessionStore.Session session = store.lookup(token).orElseThrow();

        assertEquals("quantri", session.username());
        assertEquals(Role.ADMIN, session.role());
    }

    @Test
    void tokenLaLoiDoanKhongRa() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            tokens.add(store.open(user("nguoidung" + i, Role.USER)));
        }

        assertEquals(200, tokens.size(), "moi phien phai co token rieng");
        // 32 byte -> 43 ky tu Base64-URL khong padding.
        tokens.forEach(token -> assertEquals(43, token.length()));
    }

    @Test
    void tokenLaKhongTraCuuRa() {
        assertTrue(store.lookup("token-bia-ra").isEmpty());
        assertTrue(store.lookup(null).isEmpty());
        assertTrue(store.lookup("").isEmpty());
    }

    @Test
    void phienHetHanSauMuoiHaiGio() {
        String token = store.open(user("nguoidung", Role.USER));

        clock.advance(Duration.ofHours(SessionStore.SESSION_HOURS - 1));
        assertTrue(store.lookup(token).isPresent());

        clock.advance(Duration.ofHours(2));
        assertTrue(store.lookup(token).isEmpty());
    }

    /**
     * Day la ly do chinh chon token mo thay vi JWT: dang xuat co hieu luc NGAY.
     */
    @Test
    void thuHoiCoHieuLucTucThi() {
        String token = store.open(user("nguoidung", Role.USER));

        assertTrue(store.revoke(token));
        assertTrue(store.lookup(token).isEmpty());
        // Thu hoi lan hai khong con gi de thu hoi, nhung cung khong duoc no.
        assertFalse(store.revoke(token));
    }

    /**
     * Ha vai tro ma khong dong phien cu thi nguoi vua bi ha van giu quyen ADMIN
     * cho toi khi phien het han — quyen bi thu hoi tren giay nhung con hieu luc
     * them nhieu gio.
     */
    @Test
    void dongMoiPhienCuaMotTaiKhoan() {
        String token1 = store.open(user("nguoidung", Role.ADMIN));
        String token2 = store.open(user("nguoidung", Role.ADMIN));
        String cuaNguoiKhac = store.open(user("nguoikhac", Role.USER));

        assertEquals(2, store.revokeAllFor("NguoiDung")); // khong phan biet hoa thuong

        assertTrue(store.lookup(token1).isEmpty());
        assertTrue(store.lookup(token2).isEmpty());
        assertTrue(store.lookup(cuaNguoiKhac).isPresent());
    }

    /**
     * Doi mat khau: dong moi phien KHAC, giu lai phien dang ngoi.
     *
     * <p>Ly do pho bien nhat de doi mat khau la nghi co nguoi khac dang dung
     * tai khoan cua minh — doi ma khong dong phien kia thi ke do van o trong,
     * con nguoi dung tuong minh da an toan.
     */
    @Test
    void dongMoiPhienKhacTruPhienDangDung() {
        String dangDung = store.open(user("nguoidung", Role.USER));
        String maytinhKhac = store.open(user("nguoidung", Role.USER));
        String dienThoai = store.open(user("nguoidung", Role.USER));
        String nguoiKhac = store.open(user("nguoikhac", Role.USER));

        assertEquals(2, store.revokeAllForExcept("nguoidung", dangDung));

        assertTrue(store.lookup(dangDung).isPresent(), "phien dang ngoi phai duoc giu");
        assertTrue(store.lookup(maytinhKhac).isEmpty());
        assertTrue(store.lookup(dienThoai).isEmpty());
        assertTrue(store.lookup(nguoiKhac).isPresent(), "khong dung toi tai khoan khac");
    }

    @Test
    void giuTokenLaNullThiDongTatCa() {
        store.open(user("nguoidung", Role.USER));
        store.open(user("nguoidung", Role.USER));

        assertEquals(2, store.revokeAllForExcept("nguoidung", null));
        assertEquals(0, store.activeCount());
    }

    @Test
    void demPhienConHieuLucBoQuaPhienHetHan() {
        store.open(user("a", Role.USER));
        store.open(user("b", Role.USER));
        assertEquals(2, store.activeCount());

        clock.advance(Duration.ofHours(SessionStore.SESSION_HOURS + 1));

        assertEquals(0, store.activeCount());
    }

    @Test
    void danhSachPhienKhongMangTheoToken() {
        store.open(user("nguoidung", Role.ADMIN));

        assertEquals(1, store.activeSessions().size());
        assertEquals("nguoidung", store.activeSessions().get(0).username());
    }
}
