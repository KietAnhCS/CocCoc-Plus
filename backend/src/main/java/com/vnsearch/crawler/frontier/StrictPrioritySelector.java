package com.vnsearch.crawler.frontier;

/**
 * Bộ chọn hàng đợi trước <b>tất định</b>: luôn lấy mức ưu tiên cao nhất còn URL.
 *
 * <p>Dùng khi cần thứ tự lặp lại được y hệt giữa các lần chạy — kiểm thử, và
 * các phép đo cần so sánh hai phiên với nhau.
 *
 * <p><b>Đánh đổi:</b> chính sách này <b>bỏ đói</b> các mức thấp. Chừng nào
 * mức 0 còn URL mới chảy vào thì mức 4 không bao giờ tới lượt — và trên web
 * thì mỗi trang crawl được lại sinh thêm hàng chục URL nông, nên mức 0 gần
 * như không bao giờ cạn. Đó là lý do mặc định của {@link UrlFrontier} là
 * {@link WeightedRandomSelector} chứ không phải lớp này.
 *
 * <p>Không giữ trạng thái nên dùng chung được cho mọi luồng.
 */
public final class StrictPrioritySelector implements FrontQueueSelector {

    @Override
    public int select(int[] queueSizes) {
        for (int i = 0; i < queueSizes.length; i++) {
            if (queueSizes[i] > 0) {
                return i;
            }
        }
        return -1;
    }
}
