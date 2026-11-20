package com.vnsearch.crawler.frontier;

import com.vnsearch.datastructure.MinHeap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Tầng hàng đợi sau ({@code b1..bn})</b> của sơ đồ URL Frontier, kèm
 * {@code Back queue router}, {@code Mapping Table} và {@code Back queue
 * selector} — tức toàn bộ nội dung của sơ đồ politeness.
 *
 * <p><b>Bất biến cốt lõi: mỗi hàng đợi sau chỉ chứa URL của ĐÚNG MỘT host.</b>
 * Nhờ vậy "chờ đủ 1 giây giữa hai lần chạm cùng một máy chủ" trở thành "chờ
 * đủ 1 giây giữa hai lần lấy từ cùng một hàng đợi" — một điều kiện kiểm tra
 * được tại chỗ, không cần tra cứu gì thêm.
 *
 * <h2>Ba khối, ba trường</h2>
 * <table border="1">
 *   <caption>Ánh xạ từ sơ đồ sang mã</caption>
 *   <tr><th>Khối trong sơ đồ</th><th>Trong lớp này</th></tr>
 *   <tr><td>Mapping Table</td><td>{@code hostToQueue}</td></tr>
 *   <tr><td>Back queue router</td><td>{@link #refillFrom(FrontQueues)}</td></tr>
 *   <tr><td>b1..bn</td><td>{@code queues} + {@code boundHost}</td></tr>
 *   <tr><td>Back queue selector</td><td>{@link #poll(long)} + {@code ready}</td></tr>
 * </table>
 *
 * <h2>Vì sao nạp theo yêu cầu chứ không định tuyến ngay lúc thêm</h2>
 *
 * <p>Số hàng đợi sau là <b>cố định</b>, còn số host thì không — một phiên
 * crawl 6 tờ báo chạm tới hơn 40 host vì các subdomain. Nếu định tuyến ngay
 * khi URL được thêm vào, những host vượt quá số hàng đợi sẽ không có chỗ để
 * đi.
 *
 * <p>Lời giải là của Mercator: hàng đợi sau được <b>nạp lại khi cạn</b>, kéo
 * từ tầng trước cho tới khi gặp một host chưa có chủ. Host thừa cứ nằm yên ở
 * tầng trước — tầng trước chính là vùng đệm. Nhờ vậy số hàng đợi sau chặn
 * được số host <i>đang hoạt động cùng lúc</i> mà không chặn số host
 * <i>từng gặp</i>.
 *
 * <h2>Vì sao dùng min-heap thay cho quét tuyến tính</h2>
 *
 * <p>Bản trước quét qua <b>mọi</b> domain ở mỗi lần lấy URL để tìm domain đã
 * hết thời gian hoãn — $O(D)$, và quét trong lúc đang giữ khoá. Ở đây các
 * hàng đợi còn hàng nằm trong một {@link MinHeap} sắp theo <b>thời điểm khả
 * dụng kế tiếp</b>, nên chỉ cần nhìn phần tử nhỏ nhất: nếu nó chưa tới giờ
 * thì chắc chắn không hàng đợi nào tới giờ. Chi phí xuống $O(\log n)$.
 *
 * <p><b>Bất biến giữ cho heap không hỏng:</b> {@code availableAt[i]} chỉ được
 * sửa khi {@code i} đang <b>ở ngoài</b> heap (vừa bị {@code extractMin} lấy
 * ra, chưa được chèn lại). Nếu sửa khoá của một phần tử đang nằm trong heap,
 * thứ tự heap sẽ sai một cách âm thầm.
 *
 * <h2>Vì sao không huỷ liên kết host khi hàng đợi cạn</h2>
 *
 * <p>Hàng đợi cạn vẫn <b>giữ nguyên</b> host và {@code availableAt} của nó,
 * chỉ rời khỏi heap và vào danh sách chờ nạp. Nếu huỷ liên kết ngay, đồng hồ
 * lịch sự của host đó mất theo, và một URL mới của chính host ấy có thể được
 * tải lại tức thì — vi phạm đúng thứ tầng này sinh ra để bảo vệ.
 *
 * <p>Đây cũng là cách {@code hostToQueue} được chặn kích thước: nó có tối đa
 * đúng {@code queueCount} mục, vì mỗi lần một slot đổi host thì host cũ bị gỡ
 * khỏi bảng. Bản trước dùng một {@code Map<domain, thời điểm truy cập>} lớn
 * dần theo <b>mọi</b> host từng gặp và không bao giờ co lại.
 *
 * <p><b>Không thread-safe.</b> {@link UrlFrontier} bọc mọi lời gọi trong khối
 * {@code synchronized}.
 */
public final class BackQueues {

    private final List<Deque<CrawlTask>> queues;

    /** Host đang chiếm mỗi hàng đợi; {@code null} nghĩa là chưa từng được gán. */
    private final String[] boundHost;

    /** Thời điểm sớm nhất được phép lấy URL tiếp theo từ hàng đợi này. */
    private final long[] availableAt;

    /** Mapping Table: host -&gt; chỉ số hàng đợi. Tối đa {@code queueCount} mục. */
    private final Map<String, Integer> hostToQueue = new HashMap<>();

    /** Back queue selector: các hàng đợi CÒN HÀNG, sắp theo {@code availableAt}. */
    private final MinHeap<Integer> ready;
    private final boolean[] inReady;

    /**
     * Các hàng đợi đang rỗng, chờ được nạp lại.
     *
     * <p>Có thể chứa mục cũ: khi một hàng đợi rỗng được nạp lại gián tiếp
     * (URL của host nó đang giữ chảy về), ta không đi tìm để xoá khỏi hàng
     * này — tốn $O(n)$ — mà để lại và <b>lọc bằng {@code empty[]}</b> lúc
     * lấy ra. Cùng kỹ thuật "xoá lười" mà các hàng đợi ưu tiên hay dùng.
     */
    private final Deque<Integer> freeSlots = new ArrayDeque<>();
    private final boolean[] empty;

    private final long politenessDelayMs;
    private int pending;

    public BackQueues(int queueCount, long politenessDelayMs) {
        if (queueCount <= 0) {
            throw new IllegalArgumentException("queueCount phải > 0, nhận được: " + queueCount);
        }
        if (politenessDelayMs < 0) {
            throw new IllegalArgumentException(
                    "politenessDelayMs phải >= 0, nhận được: " + politenessDelayMs);
        }
        this.queues = new ArrayList<>(queueCount);
        for (int i = 0; i < queueCount; i++) {
            queues.add(new ArrayDeque<>());
        }
        this.boundHost = new String[queueCount];
        this.availableAt = new long[queueCount];
        this.inReady = new boolean[queueCount];
        this.empty = new boolean[queueCount];
        this.politenessDelayMs = politenessDelayMs;
        this.ready = new MinHeap<>((a, b) -> {
            int byTime = Long.compare(availableAt[a], availableAt[b]);
            // Khoá phụ là chỉ số hàng đợi. Không có nó, hai hàng đợi cùng
            // thời điểm khả dụng sẽ được heap sắp theo thứ tự tuỳ ý — phiên
            // crawl vẫn chạy đúng nhưng thứ tự URL đổi theo cách chèn, nên
            // không lặp lại được. Vì slot được gán theo đúng thứ tự phát hiện
            // host, so theo chỉ số cũng chính là so theo thứ tự phát hiện.
            return byTime != 0 ? byTime : Integer.compare(a, b);
        });

        for (int i = 0; i < queueCount; i++) {
            empty[i] = true;
            freeSlots.addLast(i);
        }
    }

    /**
     * <b>Back queue router.</b> Kéo URL từ tầng trước để lấp các hàng đợi đang
     * rỗng, mỗi hàng đợi nhận đúng một host.
     *
     * <p>URL kéo lên mà host của nó đã có chủ thì được đẩy thẳng vào hàng đợi
     * của chủ đó rồi kéo tiếp — không bị trả ngược về tầng trước, nên không
     * có URL nào bị lặp vòng.
     */
    public void refillFrom(FrontQueues front) {
        while (!front.isEmpty()) {
            int slot = nextFreeSlot();
            if (slot < 0) {
                return; // mọi hàng đợi đều đang có việc
            }
            if (!fillSlot(slot, front)) {
                return; // tầng trước cạn trước khi gán được host mới
            }
        }
    }

    /**
     * <b>Back queue selector.</b> Lấy URL từ hàng đợi khả dụng sớm nhất.
     *
     * @return {@code null} nếu không hàng đợi nào còn hàng, hoặc hàng đợi sớm
     *         nhất vẫn đang trong thời gian hoãn
     */
    public CrawlTask poll(long now) {
        if (ready.isEmpty()) {
            return null;
        }
        int slot = ready.peek();
        if (availableAt[slot] > now) {
            return null; // phần tử nhỏ nhất chưa tới giờ => không có phần tử nào tới giờ
        }

        ready.extractMin();
        inReady[slot] = false; // từ đây tới lúc chèn lại, availableAt[slot] mới được phép đổi

        CrawlTask task = queues.get(slot).pollFirst();
        pending--;
        availableAt[slot] = now + politenessDelayMs;

        if (queues.get(slot).isEmpty()) {
            markEmpty(slot);
        } else {
            ready.insert(slot);
            inReady[slot] = true;
        }
        return task;
    }

    /**
     * Thời điểm sớm nhất mà một hàng đợi nào đó khả dụng trở lại, hoặc
     * {@link Long#MAX_VALUE} nếu không hàng đợi nào còn hàng. Dùng để ngủ
     * đúng khoảng cần thiết thay vì bận rộn thăm dò.
     */
    public long earliestAvailableAt() {
        return ready.isEmpty() ? Long.MAX_VALUE : availableAt[ready.peek()];
    }

    /** Số URL đang nằm trong tầng sau. */
    public int pendingCount() {
        return pending;
    }

    /** Số host đang chiếm một hàng đợi — luôn &le; số hàng đợi. */
    public int boundHostCount() {
        return hostToQueue.size();
    }

    public int queueCount() {
        return queues.size();
    }

    /** Host đang chiếm một hàng đợi, {@code null} nếu chưa từng được gán. */
    public String hostOfQueue(int slot) {
        return boundHost[slot];
    }

    // --- nội bộ ---

    /** Lấy một hàng đợi rỗng, bỏ qua các mục cũ đã được nạp lại. */
    private int nextFreeSlot() {
        while (!freeSlots.isEmpty()) {
            int slot = freeSlots.peekFirst();
            if (empty[slot]) {
                return slot;
            }
            freeSlots.pollFirst(); // mục cũ
        }
        return -1;
    }

    /**
     * Kéo từ tầng trước cho tới khi gán được một host cho {@code slot}.
     *
     * @return {@code false} nếu tầng trước cạn trước khi gán được
     */
    private boolean fillSlot(int slot, FrontQueues front) {
        while (true) {
            CrawlTask task = front.poll();
            if (task == null) {
                return false;
            }
            Integer owner = hostToQueue.get(task.host());
            if (owner != null && owner != slot) {
                push(owner, task); // host này đã có chủ, trả về đúng hàng đợi của nó
                continue;
            }
            bind(slot, task.host());
            push(slot, task);
            return true;
        }
    }

    private void bind(int slot, String host) {
        String previous = boundHost[slot];
        if (previous != null && !previous.equals(host)) {
            hostToQueue.remove(previous); // giữ Mapping Table không phình quá số hàng đợi
        }
        boundHost[slot] = host;
        hostToQueue.put(host, slot);
    }

    private void push(int slot, CrawlTask task) {
        queues.get(slot).addLast(task);
        pending++;
        empty[slot] = false;
        if (!inReady[slot]) {
            ready.insert(slot);
            inReady[slot] = true;
        }
    }

    private void markEmpty(int slot) {
        if (!empty[slot]) {
            empty[slot] = true;
            freeSlots.addLast(slot);
        }
    }
}
