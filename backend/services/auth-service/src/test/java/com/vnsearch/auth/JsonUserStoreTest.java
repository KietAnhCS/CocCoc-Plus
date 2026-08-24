package com.vnsearch.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kho tai khoan tren dia: ghi ben, nap lai, va khong chet vi mot ban ghi hong. */
class JsonUserStoreTest {

    private static User user(String name, Role role) {
        return new User(name, "$2a$12$hashgia", role, true,
                Instant.parse("2026-08-10T10:00:00Z"), null);
    }

    @Test
    void writeThenReloadKeepsTheAccount(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");

        JsonUserStore store = new JsonUserStore(file.toString());
        store.save(user("nguoidung", Role.USER));
        store.save(user("quantri", Role.ADMIN));

        // Mo lai nhu mot lan khoi dong moi cua ung dung.
        JsonUserStore reopened = new JsonUserStore(file.toString());

        assertEquals(2, reopened.count());
        assertEquals(Role.ADMIN, reopened.find("quantri").orElseThrow().role());
        assertEquals("$2a$12$hashgia", reopened.find("nguoidung").orElseThrow().passwordHash());
    }

    @Test
    void missingFileGivesAnEmptyStoreNotAnError(@TempDir Path dir) throws IOException {
        JsonUserStore store = new JsonUserStore(dir.resolve("chua-ton-tai.json").toString());

        assertEquals(0, store.count());
        assertTrue(store.find("ai-do").isEmpty());
    }

    @Test
    void createsTheParentDirectoryItself(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("chua/co/thu/muc/users.json");

        JsonUserStore store = new JsonUserStore(nested.toString());
        store.save(user("nguoidung", Role.USER));

        assertTrue(Files.exists(nested));
    }

    @Test
    void usernameIsCaseInsensitive(@TempDir Path dir) throws IOException {
        JsonUserStore store = new JsonUserStore(dir.resolve("users.json").toString());
        store.save(user("NguyenVanA", Role.USER));

        assertTrue(store.find("nguyenvana").isPresent());
        assertTrue(store.find("NGUYENVANA").isPresent());
    }

    @Test
    void savesOverwriteByUsername(@TempDir Path dir) throws IOException {
        JsonUserStore store = new JsonUserStore(dir.resolve("users.json").toString());
        store.save(user("nguoidung", Role.USER));
        store.save(user("nguoidung", Role.ADMIN));

        assertEquals(1, store.count());
        assertEquals(Role.ADMIN, store.find("nguoidung").orElseThrow().role());
    }

    @Test
    void deletesAnAccount(@TempDir Path dir) throws IOException {
        JsonUserStore store = new JsonUserStore(dir.resolve("users.json").toString());
        store.save(user("nguoidung", Role.USER));

        assertTrue(store.delete("NguoiDung"));
        assertFalse(store.delete("nguoidung"));
        assertEquals(0, store.count());
    }

    /**
     * Mot ban ghi thieu truong bi BO QUA, khong lam sap ca kho.
     *
     * <p>Tep nay la van ban tren dia — con nguoi sua duoc bang tay, va mot lan
     * sua nham khong duoc phep khoa toan bo nguoi dung ra ngoai.
     */
    @Test
    void corruptRecordIsSkippedWithoutBreakingTheStore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Files.writeString(file, """
                [
                  {"username":"tot","passwordHash":"$2a$12$x","role":"ADMIN","enabled":true,
                   "createdAt":"2026-08-10T10:00:00Z","lastLoginAt":null},
                  {"username":"thieu-hash","role":"USER","enabled":true,
                   "createdAt":"2026-08-10T10:00:00Z","lastLoginAt":null}
                ]
                """, StandardCharsets.UTF_8);

        JsonUserStore store = new JsonUserStore(file.toString());

        assertEquals(1, store.count());
        assertTrue(store.find("tot").isPresent());
        assertTrue(store.find("thieu-hash").isEmpty());
    }

    /** Truong la (phien ban sau them vao) khong duoc lam vo phep doc. */
    @Test
    void unknownFieldDoesNotBreakReading(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Files.writeString(file, """
                [
                  {"username":"nguoidung","passwordHash":"$2a$12$x","role":"USER","enabled":true,
                   "createdAt":"2026-08-10T10:00:00Z","lastLoginAt":null,
                   "truongCuaPhienBanSau":"gia tri"}
                ]
                """, StandardCharsets.UTF_8);

        assertEquals(1, new JsonUserStore(file.toString()).count());
    }

    /** Vai tro la trong tep -> ha ve USER, khong nem ngoai le luc khoi dong. */
    @Test
    void unknownRoleFallsBackToUser(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Files.writeString(file, """
                [
                  {"username":"nguoidung","passwordHash":"$2a$12$x","role":"SIEU_QUAN_TRI",
                   "enabled":true,"createdAt":"2026-08-10T10:00:00Z","lastLoginAt":null}
                ]
                """, StandardCharsets.UTF_8);

        JsonUserStore store = new JsonUserStore(file.toString());

        assertEquals(Role.USER, store.find("nguoidung").orElseThrow().role());
    }

    /** Khong de lai tep tam sau khi ghi xong. */
    @Test
    void leavesNoTemporaryFileBehind(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        new JsonUserStore(file.toString()).save(user("nguoidung", Role.USER));

        assertFalse(Files.exists(dir.resolve("users.json.tmp")));
    }
}
