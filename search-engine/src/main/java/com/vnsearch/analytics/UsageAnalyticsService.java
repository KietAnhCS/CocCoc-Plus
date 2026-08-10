package com.vnsearch.analytics;

import com.vnsearch.datastructure.MinHeap;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thu thập số liệu <b>sử dụng</b> (traffic) và tổng hợp cho bảng điều khiển
 * quản trị.
 *
 * <h2>Phân biệt với {@code CrawlAnalyticsService}</h2>
 *
 * <p>Hai lớp cùng tên "analytics" nhưng trả lời hai câu hỏi khác hẳn nhau, và
 * lẫn chúng vào nhau là cách nhanh nhất để có một bảng điều khiển vô nghĩa:
 *
 * <table border="1">
 *   <caption>Ai đo cái gì</caption>
 *   <tr><th>{@code CrawlAnalyticsService}</th><th>Lớp này</th></tr>
 *   <tr><td>Máy tìm kiếm <i>biết</i> những gì</td>
 *       <td>Người dùng <i>làm</i> những gì</td></tr>
 *   <tr><td>Trang đã crawl, host, ngôn ngữ, ảnh</td>
 *       <td>Phiên truy cập, truy vấn, liên kết được bấm, độ trễ</td></tr>
 *   <tr><td>Nguồn: bus sự kiện của crawler</td>
 *       <td>Nguồn: {@code POST /api/events} do giao diện gửi</td></tr>
 * </table>
 *
 * <h2>Vì sao lưu trong bộ nhớ chứ không vào cơ sở dữ liệu</h2>
 *
 * <p>Bảng điều khiển cần trả lời "chuyện gì đang xảy ra <i>lúc này</i>", và
 * mọi con số nó hiển thị đều có tầm nhìn tối đa 24 giờ. Một bảng SQL cho việc
 * đó kéo theo lược đồ, migration, và một phép ghi đĩa nằm ngay trên đường đi
 * của mỗi lượt tìm kiếm — chi phí thật, đổi lấy khả năng phân tích lịch sử mà
 * bảng này không hiển thị. Cái giá phải trả và được chấp nhận có ý thức:
 * <b>khởi động lại là mất số liệu</b>. Số liệu cần sống lâu hơn tiến trình thì
 * đã có đường khác — {@code /actuator/prometheus}, nơi Prometheus lưu bền.
 *
 * <h2>Mọi bảng theo dõi đều có trần</h2>
 *
 * <p>Đây là ràng buộc quan trọng nhất của lớp này, vì <b>toàn bộ dữ liệu vào
 * đây đều do bên ngoài quyết định</b>: bất kỳ ai gọi được {@code POST
 * /api/events} đều tự chọn chuỗi truy vấn, địa chỉ liên kết và mã phiên. Một
 * {@code ConcurrentHashMap} không chặn trên, đặt trên một đầu vào công khai
 * như vậy, chính là một lỗ rò bộ nhớ mà kẻ tấn công điều khiển được: gửi mỗi
 * request một mã phiên ngẫu nhiên là bảng lớn mãi cho tới khi hết heap.
 *
 * <p>Khi chạm trần, khoá <i>mới</i> bị bỏ qua (khoá cũ vẫn được đếm tiếp) và
 * {@link UsageSnapshot#truncated()} bật lên để giao diện nói thật với người
 * xem. Chọn giữ khoá cũ chứ không thay bằng khoá mới vì các truy vấn phổ biến
 * gần như luôn xuất hiện sớm; một bảng xếp hạng thiếu phần đuôi vẫn đúng ở
 * phần đầu — thứ duy nhất được hiển thị.
 *
 * <h2>Quyền riêng tư</h2>
 *
 * <p>Lớp này <b>không</b> nhận và không lưu địa chỉ IP, không lưu cookie, và
 * mã phiên là một chuỗi ngẫu nhiên do chính máy khách sinh ra — nó gom các
 * hành động của một phiên lại với nhau, nhưng không chỉ tới một con người nào.
 * Ranh giới này có ý: bảng điều khiển cần biết <i>có bao nhiêu phiên</i>, chứ
 * không cần biết <i>ai</i>.
 *
 * <p>Thread-safe hoàn toàn: mọi bộ đếm là {@link LongAdder}/{@link AtomicLong},
 * mọi bảng là {@link ConcurrentHashMap}, và việc xoay vòng ô giờ được đồng bộ
 * trên chính ô đó.
 */
@Service
public class UsageAnalyticsService {

    /** Trần cho từng bảng theo dõi — xem Javadoc lớp. */
    public static final int MAX_TRACKED_QUERIES = 5_000;
    public static final int MAX_TRACKED_LINKS = 5_000;
    public static final int MAX_TRACKED_SESSIONS = 20_000;
    public static final int MAX_TRACKED_USERS = 5_000;

    /** Số phiên phân biệt tối đa ghi nhớ cho MỘT ô giờ. */
    private static final int MAX_SESSIONS_PER_HOUR = 5_000;

    /** Độ dài cửa sổ của biểu đồ lưu lượng. Cũng là số ô trong vòng đệm. */
    public static final int HOURS_TRACKED = 24;

    /** Một phiên "đang hoạt động" nếu có sự kiện trong bấy nhiêu phút gần nhất. */
    public static final int ACTIVE_WINDOW_MINUTES = 5;

    /** Cắt chuỗi do bên ngoài gửi lên. Dài hơn thế thì không phải truy vấn thật. */
    private static final int MAX_QUERY_CHARS = 120;
    private static final int MAX_URL_CHARS = 300;
    private static final int MAX_SESSION_ID_CHARS = 64;

    /**
     * Biên trên (ms) của các khoảng trong biểu đồ phân bố độ trễ.
     *
     * <p>Các mốc không cách đều mà tăng theo cấp số nhân, vì phân bố độ trễ
     * cũng vậy: chia đều 0–1000 ms thành 10 khoảng thì 9 khoảng đầu rỗng và
     * toàn bộ dữ liệu dồn vào cột đầu tiên. Mốc 200 ms trùng với ngưỡng SLO
     * khai trong {@code application.properties} — hai chỗ nói về cùng một
     * ngưỡng thì nên nhìn thấy cùng một con số.
     */
    private static final int[] LATENCY_BOUNDS_MS = {10, 50, 100, 200, 500, 1_000};

    private static final String[] LATENCY_LABELS = {
            "< 10 ms", "10–50 ms", "50–100 ms", "100–200 ms",
            "200–500 ms", "500 ms–1 s", "> 1 s"
    };

    private final Clock clock;
    private final ZoneId zone;

    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong clicks = new AtomicLong();
    private final AtomicLong zeroResultSearches = new AtomicLong();
    private final LongAdder latencySumMs = new LongAdder();
    private final LongAdder latencySamples = new LongAdder();
    private final LongAdder[] latencyBuckets = newAdders(LATENCY_LABELS.length);

    /** Số sự kiện bị bỏ vì một bảng đã chạm trần. Chỉ dùng để báo "thiếu". */
    private final AtomicLong dropped = new AtomicLong();

    private final Map<String, LongAdder> queryCounts = new ConcurrentHashMap<>();
    /** username -> số lượt tìm. Chỉ có mục cho người ĐÃ ĐĂNG NHẬP. */
    private final Map<String, LongAdder> userSearches = new ConcurrentHashMap<>();
    private final Map<String, LinkStat> linkStats = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Hour[] hours = new Hour[HOURS_TRACKED];

    /** Dùng khi chạy thật: đồng hồ hệ thống. */
    public UsageAnalyticsService() {
        this(Clock.systemDefaultZone());
    }

    /**
     * Nhận {@link Clock} từ bên ngoài để kiểm thử điều khiển được thời gian.
     *
     * <p>Không có nó thì bài kiểm thử cho vòng đệm 24 giờ chỉ còn hai lựa
     * chọn: {@code Thread.sleep} một tiếng, hoặc không kiểm gì cả.
     */
    public UsageAnalyticsService(Clock clock) {
        this.clock = clock;
        this.zone = clock.getZone();
        for (int i = 0; i < hours.length; i++) {
            hours[i] = new Hour();
        }
    }

    // ------------------------------------------------------------------
    // Ghi nhận — được gọi từ EventController, nằm trên đường đi công khai
    // ------------------------------------------------------------------

    /** Một phiên vừa mở ứng dụng. Chỉ đăng ký phiên, không đếm gì thêm. */
    public void recordVisit(String sessionId) {
        recordVisit(sessionId, null);
    }

    /**
     * Như trên, nhưng gắn phiên với một <b>tài khoản đã đăng nhập</b>.
     *
     * <p>{@code username} là {@code null} với người dùng ẩn danh — và đó là
     * trạng thái mặc định, vì tìm kiếm không đòi đăng nhập. Chỉ khi người dùng
     * chủ động đăng nhập thì hành vi mới quy được về một danh tính.
     *
     * <p><b>Ranh giới riêng tư đổi ở đây, nên phải nói rõ.</b> Trước khi có
     * tài khoản, số liệu này là ẩn danh theo thiết kế — một mã phiên ngẫu
     * nhiên không chỉ tới ai. Nay, với người đã đăng nhập, quản trị viên đọc
     * được <i>người này đã tìm gì</i>. Đó là một quyền lực thật, và nó là lý do
     * bảng xếp hạng theo người dùng chỉ hiện tên, KHÔNG hiện truy vấn của từng
     * người: bảng điều khiển trả lời "ai dùng nhiều", không trả lời "người này
     * tìm gì".
     */
    public void recordVisit(String sessionId, String username) {
        Session session = touchSession(sessionId);
        attachUser(session, username);
    }

    /**
     * Một lượt tìm kiếm đã hoàn tất.
     *
     * @param resultCount số kết quả trả về; {@code 0} làm tăng
     *                    {@link UsageSnapshot#zeroResultSearches()}
     * @param tookMs      độ trễ máy chủ báo về; số âm bị bỏ qua thay vì làm
     *                    lệch trung bình
     */
    public void recordSearch(String sessionId, String query, int resultCount, long tookMs) {
        recordSearch(sessionId, null, query, resultCount, tookMs);
    }

    /** Như trên, kèm tài khoản đã đăng nhập ({@code null} = ẩn danh). */
    public void recordSearch(String sessionId, String username, String query,
                              int resultCount, long tookMs) {
        searches.incrementAndGet();
        Session session = touchSession(sessionId);
        attachUser(session, username);
        if (session != null) {
            session.searches.increment();
        }
        if (username != null && !username.isBlank()) {
            bump(userSearches, trimTo(username, MAX_SESSION_ID_CHARS), MAX_TRACKED_USERS);
        }
        hourNow().searches.increment();

        if (resultCount <= 0) {
            zeroResultSearches.incrementAndGet();
        }
        if (tookMs >= 0) {
            latencySumMs.add(tookMs);
            latencySamples.increment();
            latencyBuckets[latencyBucketIndex(tookMs)].increment();
        }

        String normalized = normalizeQuery(query);
        if (normalized != null) {
            bump(queryCounts, normalized, MAX_TRACKED_QUERIES);
        }
    }

    /**
     * Người dùng bấm vào một liên kết trong trang kết quả.
     *
     * @param position thứ hạng 1-based của liên kết lúc được bấm; {@code <= 0}
     *                 nghĩa là không rõ và không được đưa vào trung bình
     */
    public void recordClick(String sessionId, String url, int position) {
        recordClick(sessionId, null, url, position);
    }

    /** Như trên, kèm tài khoản đã đăng nhập ({@code null} = ẩn danh). */
    public void recordClick(String sessionId, String username, String url, int position) {
        String trimmed = trimTo(url, MAX_URL_CHARS);
        if (trimmed == null) {
            return;
        }
        clicks.incrementAndGet();
        Session session = touchSession(sessionId);
        attachUser(session, username);
        if (session != null) {
            session.clicks.increment();
        }
        hourNow().clicks.increment();

        LinkStat stat = linkStats.get(trimmed);
        if (stat == null) {
            if (linkStats.size() >= MAX_TRACKED_LINKS) {
                dropped.incrementAndGet();
                return;
            }
            stat = linkStats.computeIfAbsent(trimmed, key -> new LinkStat(hostOf(key)));
        }
        stat.count.increment();
        if (position > 0) {
            stat.positionSum.add(position);
            stat.positionSamples.increment();
        }
    }

    // ------------------------------------------------------------------
    // Đọc — chỉ /api/admin/** gọi tới
    // ------------------------------------------------------------------

    /**
     * Chụp toàn bộ số liệu.
     *
     * <p>Không đồng bộ với phần ghi: các bộ đếm được đọc lần lượt nên một
     * request đang chạy song song có thể được tính vào ô này mà chưa vào ô kia.
     * Với một bảng điều khiển làm mới mỗi vài giây thì sai lệch đó không quan
     * sát được, còn một khoá bao quanh toàn bộ phần ghi thì đặt một điểm tranh
     * chấp lên đúng đường đi của mọi truy vấn tìm kiếm — cái giá không đáng.
     *
     * @param topN số dòng tối đa cho mỗi bảng xếp hạng
     */
    public UsageSnapshot snapshot(int topN) {
        int limit = Math.max(1, Math.min(topN, 100));
        long totalSearches = searches.get();
        long totalClicks = clicks.get();
        long samples = latencySamples.sum();
        long zeroResults = zeroResultSearches.get();
        Instant now = clock.instant();

        long activeCutoff = now.toEpochMilli() - Duration.ofMinutes(ACTIVE_WINDOW_MINUTES).toMillis();
        long active = 0;
        long durationSumMillis = 0;
        long signedIn = 0;
        // Một vòng lặp cho cả ba con số: bảng phiên là thứ lớn nhất phải duyệt
        // ở đây (tới 20.000 mục) và duyệt nó ba lần không thêm được gì.
        for (Session session : sessions.values()) {
            if (session.lastSeenMillis >= activeCutoff) {
                active++;
            }
            if (session.username != null) {
                signedIn++;
            }
            durationSumMillis += session.lastSeenMillis - session.firstSeenMillis;
        }
        int sessionCount = sessions.size();

        return new UsageSnapshot(
                sessionCount,
                signedIn,
                active,
                ACTIVE_WINDOW_MINUTES,
                totalSearches,
                totalClicks,
                totalSearches == 0 ? 0.0 : (double) totalClicks / totalSearches,
                samples == 0 ? 0.0 : (double) latencySumMs.sum() / samples,
                zeroResults,
                totalSearches == 0 ? 0.0 : (double) zeroResults / totalSearches,
                sessionCount == 0 ? 0.0 : durationSumMillis / 60_000.0 / sessionCount,
                hourlySeries(now),
                latencySeries(),
                topCounted(queryCounts, limit),
                topLinks(limit),
                topHosts(limit),
                topCounted(userSearches, limit),
                dropped.get() > 0
                        || queryCounts.size() >= MAX_TRACKED_QUERIES
                        || linkStats.size() >= MAX_TRACKED_LINKS
                        || sessions.size() >= MAX_TRACKED_SESSIONS);
    }

    /** Xoá sạch mọi số liệu. Dành cho nút "đặt lại" của trang quản trị và cho test. */
    public void reset() {
        searches.set(0);
        clicks.set(0);
        zeroResultSearches.set(0);
        dropped.set(0);
        latencySumMs.reset();
        latencySamples.reset();
        for (LongAdder bucket : latencyBuckets) {
            bucket.reset();
        }
        queryCounts.clear();
        userSearches.clear();
        linkStats.clear();
        sessions.clear();
        for (Hour hour : hours) {
            synchronized (hour) {
                hour.reset(Long.MIN_VALUE);
            }
        }
    }

    // ------------------------------------------------------------------
    // Nội bộ
    // ------------------------------------------------------------------

    /**
     * Đăng ký hoặc làm mới một phiên.
     *
     * @return phiên đang theo dõi, hoặc {@code null} khi mã phiên rỗng hoặc
     *         bảng đã chạm trần — người gọi vẫn đếm sự kiện vào tổng, chỉ là
     *         không quy được cho phiên nào
     */
    private Session touchSession(String rawSessionId) {
        String sessionId = trimTo(rawSessionId, MAX_SESSION_ID_CHARS);
        if (sessionId == null) {
            return null;
        }
        long now = clock.millis();
        recordSessionInHour(sessionId);

        Session existing = sessions.get(sessionId);
        if (existing != null) {
            existing.lastSeenMillis = now;
            return existing;
        }
        if (sessions.size() >= MAX_TRACKED_SESSIONS) {
            dropped.incrementAndGet();
            return null;
        }
        return sessions.computeIfAbsent(sessionId, key -> new Session(now));
    }

    /**
     * Gắn tài khoản vào phiên.
     *
     * <p>Chỉ gắn MỘT lần, ở lần đầu biết được: nếu ghi đè mỗi lần, hai người
     * cùng dùng chung một máy (đăng nhập rồi đăng xuất) sẽ làm phiên đổi chủ
     * và số "người dùng đã đăng nhập" nhảy lung tung.
     */
    private void attachUser(Session session, String username) {
        if (session == null || username == null || username.isBlank()) {
            return;
        }
        if (session.username == null) {
            session.username = trimTo(username, MAX_SESSION_ID_CHARS);
        }
    }

    private void recordSessionInHour(String sessionId) {
        Hour hour = hourNow();
        Set<String> seen = hour.sessions;
        if (seen.size() < MAX_SESSIONS_PER_HOUR) {
            seen.add(sessionId);
        }
    }

    /**
     * Ô giờ hiện tại, xoay vòng khi bước sang giờ mới.
     *
     * <p>Vòng đệm 24 ô thay vì một danh sách lớn dần: bộ nhớ cố định, và giờ
     * thứ 25 tự động ghi đè giờ thứ 1 mà không cần một tác vụ dọn dẹp định kỳ
     * nào — thứ vốn là một nguồn lỗi riêng.
     */
    private Hour hourNow() {
        long epochHour = clock.millis() / 3_600_000L;
        Hour hour = hours[(int) Math.floorMod(epochHour, HOURS_TRACKED)];
        if (hour.epochHour != epochHour) {
            synchronized (hour) {
                // Kiểm tra lại BÊN TRONG khoá: hai luồng cùng thấy ô đã cũ thì
                // chỉ một luồng được phép xoá, luồng kia sẽ xoá mất số liệu mà
                // luồng đầu vừa ghi.
                if (hour.epochHour != epochHour) {
                    hour.reset(epochHour);
                }
            }
        }
        return hour;
    }

    private List<UsageSnapshot.HourPoint> hourlySeries(Instant now) {
        long currentHour = now.toEpochMilli() / 3_600_000L;
        List<UsageSnapshot.HourPoint> points = new ArrayList<>(HOURS_TRACKED);
        for (int back = HOURS_TRACKED - 1; back >= 0; back--) {
            long epochHour = currentHour - back;
            Hour hour = hours[(int) Math.floorMod(epochHour, HOURS_TRACKED)];
            String label = LocalTime.ofInstant(
                            Instant.ofEpochMilli(epochHour * 3_600_000L), zone)
                    .withMinute(0).toString();
            // Ô nào mang mốc giờ KHÁC là ô của một ngày trước còn sót lại trong
            // vòng đệm, không phải giờ đang xét — nó phải hiện bằng 0 chứ không
            // phải bằng số liệu cũ.
            boolean fresh = hour.epochHour == epochHour;
            points.add(new UsageSnapshot.HourPoint(
                    label,
                    fresh ? hour.sessions.size() : 0,
                    fresh ? hour.searches.sum() : 0,
                    fresh ? hour.clicks.sum() : 0));
        }
        return points;
    }

    private List<UsageSnapshot.LatencyBucket> latencySeries() {
        List<UsageSnapshot.LatencyBucket> buckets = new ArrayList<>(LATENCY_LABELS.length);
        for (int i = 0; i < LATENCY_LABELS.length; i++) {
            buckets.add(new UsageSnapshot.LatencyBucket(
                    LATENCY_LABELS[i], latencyBuckets[i].sum()));
        }
        return buckets;
    }

    /**
     * Top-K bằng {@link MinHeap#topK} chứ không phải sắp xếp cả bảng.
     *
     * <p>Bảng truy vấn có thể tới 5.000 mục còn bảng chỉ hiển thị 10 dòng.
     * {@code sort} là {@code O(n log n)} trên toàn bộ; min-heap kích thước k là
     * {@code O(n log k)} và không cấp phát một bản sao đã sắp của cả bảng —
     * cùng thuật toán mà tầng xếp hạng dùng để lấy top kết quả.
     */
    private List<UsageSnapshot.Counted> topCounted(Map<String, LongAdder> table, int k) {
        List<UsageSnapshot.Counted> all = new ArrayList<>(table.size());
        for (Map.Entry<String, LongAdder> entry : table.entrySet()) {
            all.add(new UsageSnapshot.Counted(entry.getKey(), entry.getValue().sum()));
        }
        return MinHeap.topK(all, k, Comparator.comparingLong(UsageSnapshot.Counted::count));
    }

    private List<UsageSnapshot.LinkCount> topLinks(int k) {
        List<UsageSnapshot.LinkCount> all = new ArrayList<>(linkStats.size());
        for (Map.Entry<String, LinkStat> entry : linkStats.entrySet()) {
            LinkStat stat = entry.getValue();
            long samples = stat.positionSamples.sum();
            all.add(new UsageSnapshot.LinkCount(
                    entry.getKey(),
                    stat.host,
                    stat.count.sum(),
                    samples == 0 ? 0.0 : (double) stat.positionSum.sum() / samples));
        }
        return MinHeap.topK(all, k, Comparator.comparingLong(UsageSnapshot.LinkCount::count));
    }

    /** Gộp bảng liên kết theo tên miền. Cùng một host thường có nhiều URL. */
    private List<UsageSnapshot.Counted> topHosts(int k) {
        Map<String, LongAdder> byHost = new ConcurrentHashMap<>();
        for (LinkStat stat : linkStats.values()) {
            byHost.computeIfAbsent(stat.host, host -> new LongAdder()).add(stat.count.sum());
        }
        return topCounted(byHost, k);
    }

    /** Tăng một ô đếm, tôn trọng trần của bảng. */
    private void bump(Map<String, LongAdder> table, String key, int cap) {
        LongAdder counter = table.get(key);
        if (counter != null) {
            counter.increment();
            return;
        }
        if (table.size() >= cap) {
            dropped.incrementAndGet();
            return;
        }
        table.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    /**
     * Chuẩn hoá truy vấn trước khi gộp.
     *
     * <p>"Hà Nội", "hà nội  " và "hà  nội" là <i>một</i> truy vấn với người
     * đọc bảng xếp hạng. Không chuẩn hoá thì chúng chiếm ba dòng, mỗi dòng một
     * phần ba số đếm, và không dòng nào lọt được vào top.
     */
    static String normalizeQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return collapsed.isEmpty() ? null : trimTo(collapsed, MAX_QUERY_CHARS);
    }

    private static String trimTo(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    /** Tên miền của một URL, hoặc {@code "(không rõ)"} khi chuỗi không phân giải được. */
    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return "(không rõ)";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return "(không rõ)";
        }
    }

    private static int latencyBucketIndex(long tookMs) {
        for (int i = 0; i < LATENCY_BOUNDS_MS.length; i++) {
            if (tookMs < LATENCY_BOUNDS_MS[i]) {
                return i;
            }
        }
        return LATENCY_BOUNDS_MS.length;
    }

    private static LongAdder[] newAdders(int size) {
        LongAdder[] adders = new LongAdder[size];
        for (int i = 0; i < size; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    /** Một phiên truy cập. Không chứa gì nhận dạng được con người. */
    private static final class Session {
        final long firstSeenMillis;
        volatile long lastSeenMillis;
        /** Tài khoản gắn với phiên, {@code null} khi người dùng ẩn danh. */
        volatile String username;
        final LongAdder searches = new LongAdder();
        final LongAdder clicks = new LongAdder();

        Session(long nowMillis) {
            this.firstSeenMillis = nowMillis;
            this.lastSeenMillis = nowMillis;
        }
    }

    /** Số liệu của một liên kết đã được bấm. */
    private static final class LinkStat {
        final String host;
        final LongAdder count = new LongAdder();
        final LongAdder positionSum = new LongAdder();
        final LongAdder positionSamples = new LongAdder();

        LinkStat(String host) {
            this.host = host;
        }
    }

    /** Một ô của vòng đệm 24 giờ. */
    private static final class Hour {
        /** Mốc giờ ô này đang giữ. {@code Long.MIN_VALUE} = chưa dùng. */
        volatile long epochHour = Long.MIN_VALUE;
        final LongAdder searches = new LongAdder();
        final LongAdder clicks = new LongAdder();
        final Set<String> sessions = ConcurrentHashMap.newKeySet();

        /** Gọi khi đang giữ khoá của chính ô này. */
        void reset(long newEpochHour) {
            searches.reset();
            clicks.reset();
            sessions.clear();
            // Ghi epochHour SAU CÙNG: nó là cờ báo "ô đã sẵn sàng". Ghi trước
            // thì một luồng khác có thể thấy ô thuộc giờ mới trong khi các bộ
            // đếm còn đang bị xoá, và số nó vừa ghi biến mất.
            epochHour = newEpochHour;
        }
    }
}
