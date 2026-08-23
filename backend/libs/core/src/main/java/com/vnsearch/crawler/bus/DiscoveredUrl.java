package com.vnsearch.crawler.bus;

/**
 * Một URL đã vượt qua chặng {@code URL Filter -> URL Seen Detector} và <b>xứng
 * đáng được crawl</b> — thông điệp khép kín vòng lặp của sơ đồ:
 *
 * <pre>
 *   URL Extractor -> URL Filter -> URL Seen Detector -> URL Storage
 *                                          |
 *                                          v
 *                                   [ DiscoveredUrl ]
 *                                          |
 *                                          v
 *                                    URL Frontier   (quay lại đầu sơ đồ)
 * </pre>
 *
 * <h2>Vì sao {@code host} là một trường riêng, không phải tự suy từ {@code url}</h2>
 *
 * <p>Nó là <b>khoá phân hoạch</b> của Kafka, và đó là chi tiết quan trọng
 * nhất của thiết kế này.
 *
 * <p>Bài toán: {@code UrlSeenFilter} dùng một Bloom Filter <i>trong bộ nhớ
 * tiến trình</i>. Khi chỉ có một tiến trình thì nó là nguồn sự thật duy nhất.
 * Nhưng khi crawler được nhân lên N bản để chạy song song, mỗi bản có một
 * Bloom Filter riêng, và một URL đã được bản A ghi nhận thì bản B vẫn coi là
 * mới — chống trùng sập hoàn toàn.
 *
 * <p>Ba cách chữa, và vì sao chọn cách thứ ba:
 *
 * <table border="1">
 *   <caption>Chống trùng khi có nhiều tiến trình</caption>
 *   <tr><th>Cách</th><th>Đánh giá</th></tr>
 *   <tr>
 *     <td>Bloom Filter dùng chung trên Redis</td>
 *     <td>Đúng, nhưng thêm một hệ thống nữa phải vận hành, và mỗi lần tra là
 *         một vòng mạng — trên đường nóng của crawler thì đó là chi phí thật.</td>
 *   </tr>
 *   <tr>
 *     <td>Chấp nhận trùng, lọc lại lúc lập chỉ mục</td>
 *     <td>Bác bỏ: phần lãng phí nằm ở <i>băng thông tải trang</i>, mà lọc muộn
 *         thì trang đã tải rồi.</td>
 *   </tr>
 *   <tr>
 *     <td><b>Phân hoạch theo host</b> (đang dùng)</td>
 *     <td>Kafka bảo đảm mọi thông điệp cùng khoá vào cùng một phân hoạch, và
 *         mỗi phân hoạch chỉ có <b>một</b> consumer trong một group. Nên mọi
 *         URL của {@code vnexpress.net} luôn về đúng một tiến trình, và Bloom
 *         Filter của tiến trình đó là nguồn sự thật <i>đầy đủ</i> cho host ấy.
 *         Không thêm hệ thống nào, không thêm vòng mạng nào.</td>
 *   </tr>
 * </table>
 *
 * <p><b>Phần thưởng kèm theo.</b> Chính sách lịch sự cũng là thuộc tính theo
 * host. Gom một host về một tiến trình nghĩa là bộ hoãn 1 giây của
 * {@code UrlFrontier} lại trở thành chính xác trong môi trường nhiều tiến
 * trình — không cần một bộ điều phối phân tán nào. Một quyết định, hai bài
 * toán được giải.
 *
 * <p><b>Giới hạn đã biết.</b> Phân hoạch theo host thì tải phân bố <i>không
 * đều</i>: một host khổng lồ vẫn nằm gọn trên một tiến trình. Đây là đánh đổi
 * có chủ ý — tính đúng của phép chống trùng quan trọng hơn việc san đều tải,
 * và trần thông lượng thực tế của một host vốn đã bị chính chính sách lịch sự
 * chặn ở 1 trang/giây.
 *
 * @param url       URL đã chuẩn hoá, đã qua URL Filter và URL Seen Detector
 * @param host      host của URL — <b>khoá phân hoạch Kafka</b>, xem trên
 * @param depth     độ sâu BFS mà URL này sẽ mang khi vào frontier
 * @param sourceUrl trang đã sinh ra liên kết này, giữ để lần vết và để dựng đồ thị liên kết
 * @param jobId     phiên crawl mà URL này thuộc về — quyết định frontier nào
 *                  nhận nó khi có nhiều phiên chạy song song; xem
 *                  {@link PageEvent}
 */
public record DiscoveredUrl(String url, String host, int depth, String sourceUrl, String jobId) {

    public DiscoveredUrl {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("DiscoveredUrl.url không được rỗng");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("DiscoveredUrl.host không được rỗng, url=" + url);
        }
        if (depth < 0) {
            throw new IllegalArgumentException("DiscoveredUrl.depth phải >= 0, nhận được: " + depth);
        }
    }
}
