package com.vnsearch.crawler.frontier;

/**
 * <b>Khối "Prioritizer"</b> trong sơ đồ URL Frontier.
 *
 * <p><b>Strategy pattern.</b> Chính sách ưu tiên là thứ dễ thay đổi nhất của
 * một crawler — mỗi bài toán thu thập dữ liệu lại xếp hạng URL theo tiêu chí
 * khác. Tách thành giao diện riêng để đổi chính sách mà không đụng vào
 * {@link UrlFrontier}, và để kiểm thử được chính sách đó một mình.
 *
 * <p><b>Trả về MỨC, không phải điểm.</b> Bản trước tính một điểm số
 * {@code double} rồi nhét vào một min-heap. Sơ đồ kiến trúc thì dùng
 * <i>n hàng đợi</i> {@code f1..fn}, mỗi hàng đợi ứng với một mức ưu tiên, và
 * hàng đợi được chọn bằng {@link FrontQueueSelector}. Trả về mức số nguyên
 * cho phép làm đúng như vậy: mức chính là chỉ số hàng đợi.
 *
 * <p>Quy ước: <b>0 là mức ưu tiên CAO NHẤT</b> (khớp với {@code f1} trong sơ
 * đồ), {@code levels() - 1} là thấp nhất.
 */
public interface Prioritizer {

    /** Số mức ưu tiên, cũng chính là số hàng đợi trước (front queue). */
    int levels();

    /**
     * Xếp một URL vào một mức ưu tiên.
     *
     * @param url            URL đã chuẩn hoá
     * @param host           host của URL, không {@code null}
     * @param depth          độ sâu BFS tính từ seed
     * @param knownBacklinks số liên kết đã biết trỏ tới URL này
     * @return mức trong khoảng {@code [0, levels())}, 0 là cao nhất
     */
    int levelOf(String url, String host, int depth, int knownBacklinks);
}
