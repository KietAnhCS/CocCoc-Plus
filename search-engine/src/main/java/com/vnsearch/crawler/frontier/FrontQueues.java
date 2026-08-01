package com.vnsearch.crawler.frontier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * <b>Tầng hàng đợi trước ({@code f1..fn})</b> của sơ đồ URL Frontier, kèm
 * {@link FrontQueueSelector} đứng ở đầu ra.
 *
 * <p>Mỗi hàng đợi ứng với đúng <b>một mức ưu tiên</b>, và trong một mức thì
 * thứ tự là <b>FIFO thuần</b>. Đây là điểm khác căn bản so với bản trước, vốn
 * dùng một min-heap so sánh điểm số:
 *
 * <table border="1">
 *   <caption>Heap theo điểm so với n hàng đợi FIFO</caption>
 *   <tr><th></th><th>Một heap theo điểm</th><th>n hàng đợi FIFO</th></tr>
 *   <tr><td>Thêm</td><td>$O(\log n)$</td><td><b>O(1)</b></td></tr>
 *   <tr><td>Lấy</td><td>$O(\log n)$</td><td><b>O(số mức)</b>, tức hằng số nhỏ</td></tr>
 *   <tr><td>Trong cùng một mức</td><td>thứ tự phụ thuộc cách heap sắp xếp</td><td>đúng thứ tự phát hiện</td></tr>
 *   <tr><td>Chống bỏ đói</td><td>không làm được</td><td>đổi bộ chọn là xong</td></tr>
 * </table>
 *
 * <p>Ý quan trọng nhất nằm ở dòng cuối: khi ưu tiên là <i>chỉ số hàng đợi</i>
 * chứ không phải <i>khoá so sánh</i>, chính sách phục vụ tách hẳn khỏi cấu
 * trúc lưu trữ và trở thành một tham số thay được.
 *
 * <p>Giữ thứ tự phát hiện trong mỗi mức cũng là điều kiện để một phiên crawl
 * <b>lặp lại được</b>: cùng tập seed cho ra cùng thứ tự URL.
 *
 * <p><b>Không thread-safe.</b> {@link UrlFrontier} bọc mọi lời gọi trong khối
 * {@code synchronized} — cùng quy ước với {@code MinHeap}.
 */
public final class FrontQueues {

    private final List<Deque<CrawlTask>> queues;
    private final FrontQueueSelector selector;

    /**
     * Kích thước từng hàng đợi, duy trì song song để đưa cho bộ chọn mà không
     * phải dựng mảng mới ở mỗi lần lấy.
     */
    private final int[] sizes;

    private int total;

    public FrontQueues(int levels, FrontQueueSelector selector) {
        if (levels <= 0) {
            throw new IllegalArgumentException("levels phải > 0, nhận được: " + levels);
        }
        if (selector == null) {
            throw new IllegalArgumentException("selector không được null");
        }
        this.queues = new ArrayList<>(levels);
        for (int i = 0; i < levels; i++) {
            queues.add(new ArrayDeque<>());
        }
        this.selector = selector;
        this.sizes = new int[levels];
    }

    /** O(1) — xếp một URL vào hàng đợi của mức tương ứng. */
    public void add(CrawlTask task, int level) {
        if (level < 0 || level >= queues.size()) {
            throw new IllegalArgumentException(
                    "level phải trong [0, " + queues.size() + "), nhận được: " + level);
        }
        queues.get(level).addLast(task);
        sizes[level]++;
        total++;
    }

    /**
     * Lấy URL kế tiếp theo chính sách của {@link FrontQueueSelector}.
     *
     * @return {@code null} nếu mọi hàng đợi đều rỗng
     */
    public CrawlTask poll() {
        if (total == 0) {
            return null;
        }
        int level = selector.select(sizes);
        if (level < 0) {
            return null;
        }
        CrawlTask task = queues.get(level).pollFirst();
        if (task == null) {
            // Bộ chọn trả về một hàng đợi rỗng: sizes[] và hàng đợi đã lệch nhau.
            throw new IllegalStateException("Bộ chọn trả về hàng đợi rỗng: mức " + level);
        }
        sizes[level]--;
        total--;
        return task;
    }

    public boolean isEmpty() {
        return total == 0;
    }

    public int size() {
        return total;
    }

    public int levels() {
        return queues.size();
    }

    /** Số URL đang chờ ở một mức — dùng cho thống kê và kiểm thử. */
    public int sizeOfLevel(int level) {
        return sizes[level];
    }
}
