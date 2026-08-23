package com.vnsearch.analytics;

import java.time.Instant;

/**
 * Toàn bộ nội dung của bảng điều khiển quản trị, trong <b>một</b> phản hồi.
 *
 * <p><b>Vì sao một lời gọi chứ không phải bốn.</b> Bảng điều khiển làm mới
 * theo chu kỳ. Bốn endpoint riêng nghĩa là bốn request mỗi chu kỳ, bốn lần
 * kiểm tra khoá, và — quan trọng hơn — bốn thời điểm khác nhau hiện cạnh nhau
 * trên cùng một màn hình. Gộp lại thì mọi con số trên màn hình thuộc về cùng
 * một khoảnh khắc, và {@link #generatedAt()} nói rõ khoảnh khắc đó là lúc nào.
 *
 * <p>Ba khối tách bạch theo <i>nguồn</i> chứ không theo chỗ hiển thị:
 *
 * <pre>
 *   traffic  người dùng làm gì      ← UsageAnalyticsService (bộ nhớ, 24 giờ)
 *   crawl    máy tìm kiếm biết gì   ← CorpusStats (tính lúc dựng chỉ mục)
 *   index    chỉ mục đang thế nào   ← SearchEngineFacade (đọc trực tiếp)
 * </pre>
 *
 * @param generatedAt thời điểm máy chủ chụp số liệu, để giao diện hiện "cập
 *                    nhật lúc…" thay vì để người xem đoán
 */
public record AdminDashboard(
        Instant generatedAt,
        UsageSnapshot traffic,
        CorpusStats crawl,
        IndexStats index,
        AccountStats accounts) {

    /**
     * Tình trạng tài khoản và phiên đăng nhập.
     *
     * <p>Khác {@link UsageSnapshot#signedInVisitors()}: con số kia đếm các
     * <i>phiên theo dõi số liệu</i> có gắn tài khoản (sống 24 giờ, mất khi máy
     * chủ khởi động lại), còn {@link #activeSessions()} đếm các <i>phiên đăng
     * nhập</i> thật sự còn hiệu lực. Hai con số trả lời hai câu hỏi khác nhau
     * và có thể lệch nhau — hiện cả hai còn hơn hiện một cái rồi để người đọc
     * tưởng nó là cái kia.
     */
    public record AccountStats(int total, int admins, int disabled, int activeSessions) {
    }

    /**
     * Sức khoẻ của chỉ mục — cùng những con số mà {@code /api/admin/stats} trả
     * về, nhưng ở dạng có kiểu.
     *
     * @param cacheHitRate tỉ lệ trúng cache LRU, {@code 0..1}. Thấp bất thường
     *                     nghĩa là truy vấn quá tản mát để cache có ích, hoặc
     *                     cache quá nhỏ.
     * @param bloomFilterBits số bit Bloom Filter của lần crawl gần nhất; {@code 0}
     *                     khi chưa có lần crawl nào trong tiến trình này
     */
    public record IndexStats(
            int documents,
            int terms,
            long sizeBytes,
            double cacheHitRate,
            String scorer,
            long bloomFilterBits) {
    }
}
