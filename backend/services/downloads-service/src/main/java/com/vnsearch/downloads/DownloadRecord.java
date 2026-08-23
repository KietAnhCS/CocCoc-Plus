package com.vnsearch.downloads;

import java.time.Instant;
import java.util.UUID;

/**
 * Mot dong trong so tai xuong.
 *
 * <p><b>Day la ENTITY, khong phai DTO.</b> No mang ca {@code localPath} — du
 * lieu ca nhan lo ra cau truc thu muc va ten nguoi dung he dieu hanh. Ban gui
 * ra ngoai la {@link PublicView}, va phep chuyen doi la mot buoc BAT BUOC chu
 * khong phai tuy chon: tra thang entity ra API la cach ro ri du lieu pho bien
 * nhat, vi no khong trong giong mot loi bao mat ma trong giong mot dong code
 * ngan gon.
 *
 * @param totalBytes tong so byte, hoac {@code null} khi may chu khong gui
 *                   Content-Length. NULL co nghia la KHONG BIET, khac han 0.
 */
public record DownloadRecord(
        UUID id,
        String username,
        String sourceUrl,
        String fileName,
        String mimeType,
        Long totalBytes,
        long receivedBytes,
        DownloadState state,
        String localPath,
        String deviceId,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    /**
     * Ban chieu gui ra ngoai. KHONG co {@code localPath}.
     *
     * @param percent phan tram hoan tat, hoac {@code null} khi chua biet tong
     *                so byte. Tinh o may chu de moi may khach hien giong nhau.
     * @param onThisDevice tep co nam tren THIET BI DANG HOI hay khong — giao
     *                dien dung no de quyet dinh hien nut "Mo tep" hay dong chu
     *                "Da tai tren may khac".
     */
    public record PublicView(
            UUID id,
            String sourceUrl,
            String fileName,
            String mimeType,
            Long totalBytes,
            long receivedBytes,
            Integer percent,
            DownloadState state,
            boolean onThisDevice,
            Instant startedAt,
            Instant finishedAt) {
    }

    public PublicView toPublic(String thietBiDangHoi) {
        return new PublicView(id, sourceUrl, fileName, mimeType, totalBytes, receivedBytes,
                phanTram(), state,
                deviceId != null && deviceId.equals(thietBiDangHoi),
                startedAt, finishedAt);
    }

    /**
     * Phan tram hoan tat, hoac {@code null} khi khong tinh duoc.
     *
     * <p>Tra {@code null} thay vi 0 khi chua biet tong so byte: giao dien can
     * phan biet "dang tai, chua biet bao lau nua" (hien thanh chay khong xac
     * dinh) voi "dang tai, moi duoc 0%" (hien thanh tien do o vach dau). Hai
     * thu trong khac nhau va noi len hai dieu khac nhau.
     */
    private Integer phanTram() {
        if (totalBytes == null || totalBytes <= 0) {
            return null;
        }
        return (int) Math.min(100, receivedBytes * 100 / totalBytes);
    }
}
