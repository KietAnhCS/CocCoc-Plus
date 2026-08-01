package com.vnsearch.crawler.frontier;

/**
 * <b>Khối "Front queue selector"</b> trong sơ đồ URL Frontier — chọn lấy URL
 * kế tiếp từ hàng đợi trước nào.
 *
 * <p><b>Strategy pattern.</b> Đây là chỗ duy nhất quyết định sự đánh đổi giữa
 * <i>tôn trọng ưu tiên</i> và <i>không bỏ đói mức thấp</i>, nên nó xứng đáng
 * là một trục thay thế được:
 *
 * <ul>
 *   <li>{@link WeightedRandomSelector} — mặc định, đúng như sơ đồ mô tả: mức
 *       cao được chọn với xác suất lớn hơn, nhưng mức thấp vẫn có phần.</li>
 *   <li>{@link StrictPrioritySelector} — luôn lấy mức cao nhất còn URL. Kết
 *       quả tất định nên dùng cho kiểm thử và cho các phiên chạy cần lặp lại
 *       được y hệt.</li>
 * </ul>
 */
@FunctionalInterface
public interface FrontQueueSelector {

    /**
     * Chọn một hàng đợi trước đang có phần tử.
     *
     * @param queueSizes số phần tử của từng hàng đợi, chỉ số 0 là mức ưu tiên
     *                   cao nhất. <b>Không được sửa mảng này.</b>
     * @return chỉ số hàng đợi được chọn, hoặc {@code -1} nếu mọi hàng đợi đều rỗng
     */
    int select(int[] queueSizes);
}
