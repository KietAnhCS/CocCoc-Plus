package com.vnsearch.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luat ve tai khoan: bam mat khau, chong do mat khau, va — quan trong nhat —
 * <b>khong co duong nao de tu cap cho minh vai tro ADMIN</b>.
 */
class UserServiceTest {

    /** Kho tai khoan trong bo nho. Du cho moi bai o day, khong cham dia. */
    private static final class InMemoryUserStore implements UserStore {
        private final Map<String, User> users = new LinkedHashMap<>();
        /** Bat len de gia lap dia hong — kiem duong xu ly loi ghi. */
        private boolean failWrites;

        @Override
        public Optional<User> find(String username) {
            return username == null ? Optional.empty()
                    : Optional.ofNullable(users.get(username.toLowerCase(Locale.ROOT)));
        }

        @Override
        public List<User> findAll() {
            List<User> all = new ArrayList<>(users.values());
            all.sort(Comparator.comparing(User::createdAt));
            return all;
        }

        @Override
        public void save(User user) throws IOException {
            if (failWrites) {
                throw new IOException("dia hong (gia lap)");
            }
            users.put(user.username().toLowerCase(Locale.ROOT), user);
        }

        @Override
        public boolean delete(String username) {
            return users.remove(username.toLowerCase(Locale.ROOT)) != null;
        }

        @Override
        public int count() {
            return users.size();
        }
    }

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

    private InMemoryUserStore store;
    private MovableClock clock;
    private UserService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryUserStore();
        clock = new MovableClock();
        service = new UserService(store, clock);
    }

    // ------------------------------------------------------------------
    // Dang ky
    // ------------------------------------------------------------------

    @Test
    void registrationCreatesAccountWithUserRole() throws IOException {
        User user = service.register("nguyenvana", "matkhaudaidu");

        assertEquals("nguyenvana", user.username());
        assertEquals(Role.USER, user.role());
        assertTrue(user.enabled());
        assertEquals(clock.instant(), user.createdAt());
    }

    /**
     * Bai quan trong nhat cua tep nay.
     *
     * <p>{@code register} KHONG nhan tham so vai tro — do la thu chan lo hong
     * leo thang quyen kinh dien "them {@code role: ADMIN} vao JSON dang ky".
     * Bai nay chot lai giao dien do: neu mot ngay nao do co nguoi them tham so
     * vai tro vao {@code register}, bai nay khong bien dich duoc nua.
     */
    @Test
    void thereIsNoPathToSelfRegisterAsAdmin() throws IOException {
        User user = service.register("keTanCong", "matkhaudaidu");

        assertEquals(Role.USER, user.role());
        // Chi createAccount — ham NOI BO, khong noi voi request nao — moi dat
        // duoc vai tro, va no chi duoc goi tu tai khoan moi va tu API quan tri.
        User admin = service.createAccount("quantri", "matkhaudaidu", Role.ADMIN);
        assertEquals(Role.ADMIN, admin.role());
    }

    @Test
    void passwordIsNeverStoredInPlaintext() throws IOException {
        service.register("nguyenvana", "matkhauRatBiMat");

        String hash = store.find("nguyenvana").orElseThrow().passwordHash();

        assertNotEquals("matkhauRatBiMat", hash);
        assertFalse(hash.contains("matkhauRatBiMat"));
        // BCrypt luon bat dau bang $2 va nhung ca salt trong chuoi.
        assertTrue(hash.startsWith("$2"), "phai la hash BCrypt, nhan duoc: " + hash);
    }

    /** Cung mot mat khau, hai nguoi -> hai hash KHAC nhau, nho salt. */
    @Test
    void twoUsersWithTheSamePasswordGetDifferentHashes() throws IOException {
        service.register("nguoi.mot", "cungmotmatkhau");
        service.register("nguoi.hai", "cungmotmatkhau");

        assertNotEquals(
                store.find("nguoi.mot").orElseThrow().passwordHash(),
                store.find("nguoi.hai").orElseThrow().passwordHash());
    }

    @Test
    void usernameIsCaseInsensitive() throws IOException {
        service.register("NguyenVanA", "matkhaudaidu");

        assertEquals("nguyenvana", store.findAll().get(0).username());
        assertThrows(UserService.AuthException.class,
                () -> service.register("NGUYENVANA", "matkhaukhac1"));
    }

    @Test
    void rejectsInvalidUsername() {
        assertThrows(UserService.AuthException.class, () -> service.register("ab", "matkhaudaidu"));
        assertThrows(UserService.AuthException.class,
                () -> service.register("co khoang trang", "matkhaudaidu"));
        assertThrows(UserService.AuthException.class,
                () -> service.register("ten/co/gach-cheo", "matkhaudaidu"));
    }

    @Test
    void rejectsPasswordThatIsTooShortOrTooLong() {
        assertThrows(UserService.AuthException.class, () -> service.register("nguoidung", "ngan"));
        assertThrows(UserService.AuthException.class,
                () -> service.register("nguoidung", "x".repeat(500)));
    }

    /**
     * Ghi dia that bai thi KHONG duoc bao dang ky thanh cong: tai khoan chi ton
     * tai trong RAM se bien mat o lan khoi dong sau, va nguoi dung khong hieu vi sao.
     */
    @Test
    void registrationFailsWhenTheWriteFails() {
        store.failWrites = true;

        assertThrows(IOException.class, () -> service.register("nguoidung", "matkhaudaidu"));
    }

    // ------------------------------------------------------------------
    // Dang nhap
    // ------------------------------------------------------------------

    @Test
    void successfulLoginReturnsTheAccountAndRecordsTheTimestamp() throws IOException {
        service.register("nguoidung", "matkhaudaidu");
        clock.advance(Duration.ofHours(1));

        User user = service.authenticate("nguoidung", "matkhaudaidu");

        assertEquals("nguoidung", user.username());
        assertNotNull(user.lastLoginAt());
        assertEquals(clock.instant(), user.lastLoginAt());
    }

    /**
     * Sai TEN va sai MAT KHAU phai cho ra CUNG mot thong bao.
     *
     * <p>Phan biet hai ca bien trang dang nhap thanh mot cong cu liet ke tai
     * khoan: ke tan cong thu mot danh sach ten va biet ten nao co that.
     */
    @Test
    void wrongUsernameAndWrongPasswordReportIdentically() throws IOException {
        service.register("nguoidung", "matkhaudaidu");

        String saiMatKhau = assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.authenticate("nguoidung", "matkhausai1")).getMessage();
        String saiTen = assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.authenticate("khongtontai", "matkhausai1")).getMessage();

        assertEquals(saiTen, saiMatKhau);
    }

    @Test
    void disabledAccountCannotLogIn() throws IOException {
        service.register("nguoidung", "matkhaudaidu");
        service.setEnabled("nguoidung", false);

        assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.authenticate("nguoidung", "matkhaudaidu"));
    }

    // ------------------------------------------------------------------
    // Chong do mat khau
    // ------------------------------------------------------------------

    @Test
    void locksTemporarilyAfterFiveFailedAttempts() throws IOException {
        service.register("nguoidung", "matkhaudaidu");

        for (int i = 0; i < UserService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(UserService.InvalidCredentialsException.class,
                    () -> service.authenticate("nguoidung", "matkhausai1"));
        }

        // Ngay ca MAT KHAU DUNG cung bi tu choi trong thoi gian khoa.
        String message = assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.authenticate("nguoidung", "matkhaudaidu")).getMessage();
        assertTrue(message.contains("khoá tạm"), "phai noi ro dang bi khoa tam: " + message);
    }

    /**
     * Khoa TAM chu khong vinh vien: khoa vinh vien bien mot cuoc do mat khau
     * thanh mot cuoc tan cong tu choi dich vu nham vao nguoi dung that.
     */
    @Test
    void loginWorksAgainOnceTheLockExpires() throws IOException {
        service.register("nguoidung", "matkhaudaidu");
        for (int i = 0; i < UserService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(UserService.InvalidCredentialsException.class,
                    () -> service.authenticate("nguoidung", "matkhausai1"));
        }

        clock.advance(Duration.ofMinutes(UserService.LOCKOUT_MINUTES + 1));

        assertEquals("nguoidung", service.authenticate("nguoidung", "matkhaudaidu").username());
    }

    @Test
    void successfulLoginClearsTheFailureCounter() throws IOException {
        service.register("nguoidung", "matkhaudaidu");
        for (int i = 0; i < UserService.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertThrows(UserService.InvalidCredentialsException.class,
                    () -> service.authenticate("nguoidung", "matkhausai1"));
        }

        service.authenticate("nguoidung", "matkhaudaidu");

        // Bo dem da ve 0 nen bon lan sai nua van chua bi khoa.
        for (int i = 0; i < UserService.MAX_FAILED_ATTEMPTS - 1; i++) {
            assertThrows(UserService.InvalidCredentialsException.class,
                    () -> service.authenticate("nguoidung", "matkhausai1"));
        }
        assertEquals("nguoidung", service.authenticate("nguoidung", "matkhaudaidu").username());
    }

    // ------------------------------------------------------------------
    // Doi mat khau
    // ------------------------------------------------------------------

    @Test
    void afterPasswordChangeTheOldPasswordNoLongerWorks() throws IOException {
        service.register("nguoidung", "matkhaucu123");

        service.changePassword("nguoidung", "matkhaucu123", "matkhaumoi456");

        assertEquals("nguoidung", service.authenticate("nguoidung", "matkhaumoi456").username());
        assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.authenticate("nguoidung", "matkhaucu123"));
    }

    /**
     * Bai quan trong nhat cua nhom nay.
     *
     * <p>Nguoi goi DA co token hop le, nen hoi mat khau hien tai nghe thua.
     * Nhung do chinh la kich ban can chan: mot chiec token bi danh cap. Khong
     * hoi mat khau cu thi ke cam token doi duoc mat khau va KHOA CHINH CHU NHAN
     * ra ngoai — bien mot phien bi lo tam thoi thanh mat tai khoan vinh vien.
     */
    @Test
    void changingPasswordRequiresTheCurrentPassword() throws IOException {
        service.register("nguoidung", "matkhaucu123");

        assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.changePassword("nguoidung", "doan-mo", "matkhaumoi456"));

        // Mat khau CU van con nguyen hieu luc.
        assertEquals("nguoidung", service.authenticate("nguoidung", "matkhaucu123").username());
    }

    @Test
    void newPasswordStillMustPassTheLengthRule() throws IOException {
        service.register("nguoidung", "matkhaucu123");

        assertThrows(UserService.AuthException.class,
                () -> service.changePassword("nguoidung", "matkhaucu123", "ngan"));
    }

    @Test
    void cannotChangePasswordToTheSameOne() throws IOException {
        service.register("nguoidung", "matkhaucu123");

        UserService.AuthException e = assertThrows(UserService.AuthException.class,
                () -> service.changePassword("nguoidung", "matkhaucu123", "matkhaucu123"));

        assertTrue(e.getMessage().contains("khác"), "phai noi ro vi sao: " + e.getMessage());
    }

    /** Sai mat khau hien tai nhieu lan cung bi khoa tam — neu khong, day la mot may do. */
    @Test
    void repeatedWrongPasswordChangesAlsoLockTemporarily() throws IOException {
        service.register("nguoidung", "matkhaucu123");

        for (int i = 0; i < UserService.MAX_FAILED_ATTEMPTS; i++) {
            assertThrows(UserService.InvalidCredentialsException.class,
                    () -> service.changePassword("nguoidung", "doan-mo", "matkhaumoi456"));
        }

        String message = assertThrows(UserService.InvalidCredentialsException.class,
                () -> service.changePassword("nguoidung", "matkhaucu123", "matkhaumoi456"))
                .getMessage();
        assertTrue(message.contains("khoá tạm"), "phai bao dang bi khoa tam: " + message);
    }

    /**
     * Phien dung KHOA API khong co tai khoan dung sau, nen khong co mat khau de
     * doi. Thong bao phai noi ro dieu do thay vi mot cau "khong tim thay".
     */
    @Test
    void apiKeySessionCannotChangeThePassword() {
        UserService.AuthException e = assertThrows(UserService.AuthException.class,
                () -> service.changePassword("admin-api-key", "gi-do", "matkhaumoi456"));

        assertTrue(e.getMessage().contains("khoá quản trị"), e.getMessage());
    }

    // ------------------------------------------------------------------
    // Quan tri
    // ------------------------------------------------------------------

    @Test
    void changingRoleKeepsThePassword() throws IOException {
        service.register("nguoidung", "matkhaudaidu");
        String hashTruoc = store.find("nguoidung").orElseThrow().passwordHash();

        User updated = service.changeRole("nguoidung", Role.ADMIN);

        assertEquals(Role.ADMIN, updated.role());
        assertEquals(hashTruoc, updated.passwordHash());
        assertEquals("nguoidung", service.authenticate("nguoidung", "matkhaudaidu").username());
    }

    @Test
    void changingRoleOfAMissingAccountReportsAnError() {
        assertThrows(UserService.AuthException.class,
                () -> service.changeRole("khongtontai", Role.ADMIN));
    }

    /** Ban cong khai KHONG duoc mang hash mat khau ra ngoai. */
    @Test
    void publicViewContainsNoHash() throws IOException {
        User.PublicView view = service.register("nguoidung", "matkhaudaidu").toPublic();

        assertEquals("nguoidung", view.username());
        assertFalse(view.toString().contains("$2"), "PublicView khong duoc chua hash");
    }
}
