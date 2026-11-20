package com.vnsearch.analytics;

import java.util.List;

/**
 * Ảnh chụp <b>bất biến</b> của toàn bộ số liệu sử dụng tại một thời điểm.
 *
 * <p><b>Vì sao là một bản chụp chứ không phải các getter rời.</b> Bảng điều
 * khiển hiển thị nhiều con số cạnh nhau và người đọc sẽ <i>so sánh</i> chúng —
 * "3.000 lượt tìm, 900 lượt bấm, vậy CTR 30%". Nếu giao diện gọi ba getter
 * riêng trên một dịch vụ đang được ghi liên tục, ba con số đó thuộc ba thời
 * điểm khác nhau và phép chia của người đọc ra một tỉ lệ chưa từng tồn tại.
 * Chụp một lần rồi trả về một đối tượng đông cứng làm cho mọi con số trên một
 * màn hình <i>nhất quán với nhau</i>.
 *
 * <p><b>Vì sao dùng {@code record} chứ không phải {@code Map<String,Object>}.</b>
 * Map thì Jackson vẫn tuần tự hoá được, nhưng khi đó lược đồ JSON chỉ tồn tại
 * trong đầu người viết: đổi tên một khoá không làm gãy phép biên dịch nào, và
 * lỗi lộ ra ở giao diện, lúc chạy. Record biến lược đồ thành kiểu.
 *
 * <p>Mọi trường đếm đều là <b>xấp xỉ có chặn</b>: các bảng theo dõi đều có trần
 * bộ nhớ (xem {@link UsageAnalyticsService}), nên khi vượt trần thì số liệu
 * thiếu chứ không bao giờ làm sập tiến trình. {@link #truncated()} nói thẳng
 * điều đó cho giao diện thay vì để người xem tin nhầm rằng bảng là đầy đủ.
 *
 * @param visitors        số phiên phân biệt đang theo dõi (một "người truy cập")
 * @param signedInVisitors số phiên gắn với một TÀI KHOẢN đã đăng nhập. Phần
 *                        chênh so với {@code visitors} là người dùng ẩn danh —
 *                        và đó là trạng thái bình thường, vì tìm kiếm không
 *                        đòi đăng nhập.
 * @param activeVisitors  số phiên có hoạt động trong {@code activeWindowMinutes} phút gần nhất
 * @param searches        tổng lượt tìm kiếm
 * @param clicks          tổng lượt bấm vào một liên kết kết quả
 * @param clickThroughRate {@code clicks / searches}, {@code 0} khi chưa có lượt tìm nào
 * @param avgLatencyMs    độ trễ trung bình do MÁY CHỦ đo và giao diện báo lại
 * @param zeroResultSearches số truy vấn không ra kết quả nào — chỉ số chất lượng chỉ mục
 * @param avgSessionMinutes thời lượng phiên trung bình: khoảng cách giữa sự kiện
 *                        đầu và cuối của cùng một phiên. Phiên chỉ có một sự kiện
 *                        tính là 0 phút — không suy đoán thời gian người dùng
 *                        ngồi đọc sau hành động cuối, vì không có gì đo được điều đó.
 * @param hourly          24 điểm, một điểm mỗi giờ, cũ nhất trước
 * @param latency         phân bố độ trễ theo khoảng
 * @param topQueries      truy vấn phổ biến nhất
 * @param topLinks        liên kết được bấm nhiều nhất
 * @param topHosts        tên miền được bấm nhiều nhất (gộp từ {@code topLinks} nguồn)
 * @param topUsers        tài khoản tìm kiếm nhiều nhất. <b>Chỉ tên và số lượt</b>
 *                        — cố ý không kèm truy vấn của từng người: bảng điều
 *                        khiển trả lời "ai dùng nhiều", không trả lời "người
 *                        này tìm gì". Ranh giới đó là một lựa chọn, không phải
 *                        một thiếu sót.
 * @param truncated       {@code true} khi có ít nhất một bảng đã chạm trần
 */
public record UsageSnapshot(
        long visitors,
        long signedInVisitors,
        long activeVisitors,
        int activeWindowMinutes,
        long searches,
        long clicks,
        double clickThroughRate,
        double avgLatencyMs,
        long zeroResultSearches,
        double zeroResultRate,
        double avgSessionMinutes,
        List<HourPoint> hourly,
        List<LatencyBucket> latency,
        List<Counted> topQueries,
        List<LinkCount> topLinks,
        List<Counted> topHosts,
        List<Counted> topUsers,
        boolean truncated) {

    /** Một cặp nhãn — số đếm. Dùng cho mọi bảng xếp hạng đơn giản. */
    public record Counted(String label, long count) {
    }

    /**
     * Một liên kết người dùng đã bấm vào từ trang kết quả.
     *
     * @param position thứ hạng TRUNG BÌNH của liên kết lúc được bấm. Đây là số
     *                 liệu đáng giá nhất trong bảng: nó đo <i>chất lượng xếp
     *                 hạng</i>. Người dùng bấm chủ yếu ở hạng 1–3 nghĩa là bộ
     *                 xếp hạng đang đặt đúng thứ tự; bấm rải đều tới hạng 8–10
     *                 nghĩa là họ phải tự đi tìm.
     */
    public record LinkCount(String url, String host, long count, double position) {
    }

    /**
     * Lưu lượng trong MỘT giờ.
     *
     * @param hour     nhãn giờ địa phương dạng {@code "14:00"}
     * @param visitors số phiên phân biệt xuất hiện trong giờ đó
     */
    public record HourPoint(String hour, long visitors, long searches, long clicks) {
    }

    /**
     * Một khoảng của biểu đồ phân bố độ trễ.
     *
     * <p><b>Vì sao phân bố chứ không chỉ trung bình.</b> Độ trễ truy vấn có
     * đuôi rất dài: phần lớn truy vấn trúng cache và về trong vài mili giây,
     * một số ít phải chấm điểm hàng nghìn ứng viên. Trung bình của hai nhóm đó
     * không mô tả nhóm nào cả. Biểu đồ cột cho thấy ngay <i>có bao nhiêu người
     * thật sự phải chờ</i>.
     *
     * @param label nhãn khoảng, ví dụ {@code "50–100 ms"}
     */
    public record LatencyBucket(String label, long count) {
    }
}
