package com.vnsearch.crawler.bus;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * Toàn bộ liên kết ra của một trang, do {@code URL Extractor} bóc được và
 * <b>gửi ngược lại</b> cho crawler.
 *
 * <p>Đây chính là chiều còn lại của mũi tên hai chiều giữa Kafka và cụm
 * Modular Services trong sơ đồ kiến trúc — service không chỉ tiêu thụ, nó còn
 * sản xuất.
 *
 * <h2>Vì sao cần thông điệp này, tách khỏi {@link DiscoveredUrl}</h2>
 *
 * <p>Hai thông điệp trông giống nhau nhưng phục vụ hai mục đích <b>loại trừ
 * nhau</b>, và gộp lại thì một trong hai chắc chắn sai.
 *
 * <table border="1">
 *   <caption>Hai luồng, hai tập URL khác nhau</caption>
 *   <tr><th></th><th>{@link DiscoveredUrl}</th><th>{@code OutlinksExtracted}</th></tr>
 *   <tr><td>Đi tới</td><td>URL Frontier</td><td>Content Storage</td></tr>
 *   <tr><td>Trả lời câu hỏi</td><td>"Nên crawl gì tiếp?"</td><td>"Trang này trỏ đi đâu?"</td></tr>
 *   <tr><td>Đã lọc chưa</td><td><b>Rồi</b> — qua URL Filter + URL Seen</td><td><b>Chưa</b> — nguyên vẹn</td></tr>
 *   <tr><td>Dùng cho</td><td>Vòng lặp crawl</td><td>PageRank</td></tr>
 * </table>
 *
 * <p><b>Vì sao PageRank cần tập CHƯA lọc.</b> {@code URL Seen Detector} loại
 * bỏ mọi URL đã gặp — mà những URL đó chính là các trang <i>đã nằm trong
 * corpus</i>, tức là đúng những cạnh mà PageRank cần đếm. Nếu dùng tập đã lọc
 * để dựng đồ thị, đồ thị sẽ mất gần hết cạnh nội bộ và mọi trang có điểm gần
 * như nhau — PageRank trở thành một cột số vô nghĩa mà vẫn chạy trót lọt,
 * không có gì báo lỗi.
 *
 * <p>Đó là loại hỏng tệ nhất: kết quả sai nhưng hệ thống vẫn xanh. Nên hai
 * tập được giữ riêng ngay từ tầng thông điệp, chứ không phải "lọc rồi dùng
 * lại cho cả hai việc".
 *
 * @param sourceUrl trang đã được bóc liên kết
 * @param host      host của {@code sourceUrl} — khoá phân hoạch, giữ cùng
 *                  quy ước với {@link PageEvent} và {@link DiscoveredUrl}
 * @param outlinks  toàn bộ liên kết ra đã chuẩn hoá và khử trùng, <b>chưa lọc</b>
 * @param jobId     phiên crawl sở hữu trang này — quyết định Content Storage
 *                  nào nhận danh sách; xem {@link PageEvent}
 */
public record OutlinksExtracted(String sourceUrl, String host, List<String> outlinks,
                                 String jobId) {

    /**
     * Sao chép phòng thủ danh sách.
     *
     * <p>Không có dòng này thì người gọi vẫn giữ tham chiếu tới danh sách gốc
     * và sửa được nó <i>sau khi</i> thông điệp đã tạo — phá tính bất biến mà
     * cả bus này dựa vào. {@code List.copyOf} vừa sao chép vừa trả về bản
     * không sửa được, nên phía consumer cũng không thể sửa nhầm.
     */
    public OutlinksExtracted {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("OutlinksExtracted.sourceUrl không được rỗng");
        }
        outlinks = outlinks == null ? List.of() : List.copyOf(outlinks);
    }

    /** Giá trị dẫn xuất — {@code @JsonIgnore} vì cùng lý do với {@link PageEvent#htmlSizeBytes()}. */
    @JsonIgnore
    public int size() {
        return outlinks.size();
    }
}
