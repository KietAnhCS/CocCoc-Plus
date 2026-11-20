package com.vnsearch.index;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Nen/giai nen van ban than bai cua tai lieu.
 *
 * <p><b>Vi sao than bai phai nam trong chi muc, va vi sao no phai duoc nen.</b>
 * Xep hang KHONG can den than bai — diem so tinh tu posting list. Chi mot cho
 * duy nhat can toi no: sinh doan trich cho <b>top-K (10 tai lieu)</b> that su
 * duoc tra ve. Nhung "chi 10 tai lieu" la biet TRUOC khi truy van chay xong,
 * con van ban thi phai co san TU TRUOC do.
 *
 * <p>Hai duong di kha di, va lua chon o day co danh doi ro rang:
 *
 * <table border="1">
 *   <tr><th></th><th>Doc theo yeu cau tu CSDL</th><th>Giu trong bo nho, da nen</th></tr>
 *   <tr><td>Bo nho</td><td>gan bang 0</td><td>~1/4 ban goc</td></tr>
 *   <tr><td>Do tre moi truy van</td><td>them mot vong I/O</td><td>khong</td></tr>
 *   <tr><td>Chay khi KHONG co CSDL</td><td><b>khong sinh duoc snippet</b></td><td>binh thuong</td></tr>
 * </table>
 *
 * <p>O cot cuoi cung nam mot tinh chat ma du an nay co y giu: nguoi vua clone ve
 * chay duoc NGAY, khong can cai PostgreSQL. Buoc CSDL thanh bat buoc chi de tiet
 * kiem them mot chut bo nho la danh doi sai — nen chon cot ben phai.
 *
 * <p><b>Vi sao {@link Deflater} chu khong phai GZIP.</b> Cung mot thuat toan
 * nen, nhung GZIP boc them 10 byte header va 8 byte trailer cho MOI lan goi. Voi
 * hang chuc nghin tai lieu thi phan bao bi do la thuan lo. Deflate tho khong co
 * bao bi nao, va o day khong can: do dai da biet tu chinh mang byte.
 *
 * <p><b>Vi sao khac {@link CompressedPostings}.</b> Lop kia co y KHONG dung nen
 * tong quat, vi posting list can truy cap ngau nhien theo tung term. Van ban
 * than bai thi nguoc lai — luon doc tron ven mot tai lieu — nen nen tong quat la
 * dung cong cu. Hai lua chon trai nguoc nhau cho hai bai toan trai nguoc nhau.
 */
public final class CompressedText {

    /** Muc nen. 6 la mac dinh cua zlib — diem can bang giua ty le va toc do. */
    private static final int LEVEL = Deflater.DEFAULT_COMPRESSION;

    private static final byte[] EMPTY = new byte[0];

    private CompressedText() {
    }

    /** Nen mot chuoi thanh mang byte. {@code null} hoac rong cho ra mang rong. */
    public static byte[] compress(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3);
        Deflater deflater = new Deflater(LEVEL);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) {
            stream.write(raw);
        } catch (IOException e) {
            // Ghi vao bo nho thi khong the loi vi I/O; neu xay ra thi la loi lap
            // trinh chu khong phai tinh huong can xu ly.
            throw new UncheckedIOException("Khong nen duoc van ban", e);
        } finally {
            // BAT BUOC: Deflater giu bo dem NGOAI heap cua JVM, ma bo thu gom rac
            // khong quan ly. Quen goi end() la ro ri bo nho native — thu khong
            // hien ra trong bat ky phep do heap nao.
            deflater.end();
        }
        return out.toByteArray();
    }

    /** Giai nen mang byte do {@link #compress} tao ra. */
    public static String decompress(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return "";
        }
        try (InflaterInputStream stream = new InflaterInputStream(
                new java.io.ByteArrayInputStream(compressed))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Khong giai nen duoc van ban", e);
        }
    }
}
