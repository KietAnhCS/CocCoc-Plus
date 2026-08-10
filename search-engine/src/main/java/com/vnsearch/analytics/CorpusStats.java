package com.vnsearch.analytics;

import com.vnsearch.datastructure.BloomFilter;
import com.vnsearch.datastructure.MinHeap;
import com.vnsearch.model.WebDocument;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Số liệu mô tả <b>corpus đã crawl</b>: bao nhiêu trang, thuộc bao nhiêu tên
 * miền, phát hiện được bao nhiêu liên kết, viết bằng ngôn ngữ nào.
 *
 * <h2>Vì sao tính một lần rồi giữ lại</h2>
 *
 * <p>Mọi con số ở đây đều đòi hỏi <b>một lượt duyệt toàn bộ corpus</b>, và thân
 * bài trong chỉ mục được lưu ở dạng nén — duyệt tức là giải nén. Tính lại mỗi
 * lần bảng điều khiển làm mới (mặc định 10 giây một lần) sẽ đặt một khối lượng
 * công việc tỉ lệ với kích thước chỉ mục lên một endpoint chỉ để hiển thị.
 *
 * <p>Nhưng corpus chỉ thay đổi ở đúng một thời điểm: khi chỉ mục được dựng lại.
 * Nên số liệu được tính <i>ngay tại đó</i> — trong
 * {@code SearchEngineFacade.refreshDerivedState()} — và bảng điều khiển chỉ
 * việc đọc một đối tượng đã có sẵn. Đây cùng một khuôn với cách PageRank và bộ
 * gợi ý được dựng lại: <b>trạng thái dẫn xuất được làm mới cùng nguồn của nó,
 * không phải khi có người hỏi</b>.
 *
 * <h2>Vì sao có {@code danglingDocuments}</h2>
 *
 * <p>Trang không có liên kết đi ra là <i>nút cụt</i> trong đồ thị web, và nó
 * không chỉ là một con số vui: PageRank phải xử lý riêng những nút này (phân
 * phối lại điểm của chúng cho toàn đồ thị), nếu không tổng điểm rò rỉ dần về 0.
 * Tỉ lệ nút cụt cao nghĩa là phần lớn corpus là các trang lá — dấu hiệu crawler
 * đã dừng ở độ sâu tối đa chứ chưa đi hết.
 *
 * @param documents        số trang trong chỉ mục
 * @param distinctHosts    số tên miền phân biệt
 * @param totalOutlinks    tổng số liên kết đi ra đã phát hiện (tính cả trùng)
 * @param distinctLinkTargets số ĐÍCH liên kết phân biệt — chênh lệch so với
 *                         {@code documents} chính là phần web đã <i>nhìn
 *                         thấy</i> nhưng chưa crawl tới. Con số này là
 *                         <b>xấp xỉ</b> (đếm bằng Bloom Filter) và chỉ có thể
 *                         thiếu, không bao giờ thừa — xem {@code countDistinctTargets}
 * @param avgOutlinks      số liên kết trung bình mỗi trang
 * @param danglingDocuments số trang không có liên kết đi ra nào
 * @param avgDocLength     độ dài tài liệu trung bình, tính bằng <b>token</b>
 * @param medianDocLength  độ dài tài liệu trung vị — luôn báo cùng trung bình,
 *                         vì hai số lệch nhau nhiều nghĩa là corpus có vài trang
 *                         khổng lồ kéo trung bình đi
 * @param oldestCrawledAt  {@code null} khi corpus rỗng hoặc không trang nào có mốc thời gian
 * @param languages        phân bố ngôn ngữ, nhiều nhất trước
 * @param topHosts         tên miền có nhiều trang nhất
 * @param crawledPerDay    số trang crawl được mỗi ngày trong {@link #DAYS_TRACKED} ngày gần nhất
 */
public record CorpusStats(
        int documents,
        int distinctHosts,
        long totalOutlinks,
        int distinctLinkTargets,
        double avgOutlinks,
        int danglingDocuments,
        double avgDocLength,
        int medianDocLength,
        Instant oldestCrawledAt,
        Instant newestCrawledAt,
        List<UsageSnapshot.Counted> languages,
        List<UsageSnapshot.Counted> topHosts,
        List<DayCount> crawledPerDay) {

    /** Độ dài cửa sổ của biểu đồ "trang crawl theo ngày". */
    public static final int DAYS_TRACKED = 14;

    /** Số tên miền tối đa trả về trong {@link #topHosts()}. */
    private static final int TOP_HOSTS = 10;

    /**
     * Tỉ lệ dương tính giả của bộ lọc đếm đích liên kết — xem
     * {@link #countDistinctTargets}.
     */
    private static final double TARGET_FILTER_FPR = 0.01;

    /** Ước lượng số đích liên kết trên mỗi trang, dùng để định cỡ bộ lọc. */
    private static final int ESTIMATED_LINKS_PER_PAGE = 64;

    /** Chặn trên cho cỡ bộ lọc, để bộ nhớ không phụ thuộc kích thước corpus. */
    private static final int MAX_FILTER_ITEMS = 5_000_000;

    /** Số trang crawl được trong một ngày. {@code date} dạng {@code YYYY-MM-DD}. */
    public record DayCount(String date, long count) {
    }

    /** Corpus rỗng — dùng trước khi chỉ mục được dựng lần đầu. */
    public static CorpusStats empty() {
        return new CorpusStats(0, 0, 0, 0, 0.0, 0, 0.0, 0, null, null,
                List.of(), List.of(), List.of());
    }

    /**
     * Tính toàn bộ số liệu trong <b>một</b> lượt duyệt corpus.
     *
     * <p>Một lượt chứ không phải một lượt cho mỗi con số: với vài chục nghìn
     * tài liệu, mỗi lượt thừa là một lần duyệt lại toàn bộ danh sách liên kết.
     *
     * @param docLength độ dài tài liệu, <b>nhận từ ngoài vào</b> thay vì đọc
     *                  {@code getBodyText()}. Lý do rất cụ thể: {@code WebDocument}
     *                  lấy ra từ chỉ mục KHÔNG mang theo thân bài — thân bài
     *                  nằm ở dạng nén và chỉ được giải nén khi sinh đoạn trích.
     *                  Đo {@code getBodyText().length()} ở đây cho ra 0 cho
     *                  mọi tài liệu, tức một con số <i>trông như thật</i> mà
     *                  sai hoàn toàn. Chỉ mục đã có sẵn độ dài theo token
     *                  ({@code getDocLength}, O(1)) — vừa đúng vừa rẻ, và
     *                  token cũng chính là đơn vị mà BM25 dùng để chuẩn hoá.
     */
    public static CorpusStats from(Collection<WebDocument> documents,
                                    ToIntFunction<WebDocument> docLength,
                                    ZoneId zone) {
        if (documents == null || documents.isEmpty()) {
            return empty();
        }

        Map<String, Long> hostCounts = new HashMap<>();
        Map<String, Long> languageCounts = new HashMap<>();
        Map<LocalDate, Long> perDay = new HashMap<>();
        BloomFilter seenTargets = newTargetFilter(documents.size());
        int distinctTargets = 0;
        int[] lengths = new int[documents.size()];

        long totalOutlinks = 0;
        long totalTokens = 0;
        int dangling = 0;
        int index = 0;
        Instant oldest = null;
        Instant newest = null;

        for (WebDocument document : documents) {
            hostCounts.merge(hostOf(document.getUrl()), 1L, Long::sum);

            String language = document.getLanguage() == null || document.getLanguage().isBlank()
                    ? "und" : document.getLanguage();
            languageCounts.merge(language, 1L, Long::sum);

            List<String> outlinks = document.getOutlinks();
            if (outlinks == null || outlinks.isEmpty()) {
                dangling++;
            } else {
                totalOutlinks += outlinks.size();
                distinctTargets += countDistinctTargets(outlinks, seenTargets);
            }

            int length = Math.max(0, docLength.applyAsInt(document));
            lengths[index++] = length;
            totalTokens += length;

            Instant crawledAt = document.getCrawledAt();
            if (crawledAt != null) {
                oldest = oldest == null || crawledAt.isBefore(oldest) ? crawledAt : oldest;
                newest = newest == null || crawledAt.isAfter(newest) ? crawledAt : newest;
                perDay.merge(LocalDate.ofInstant(crawledAt, zone), 1L, Long::sum);
            }
        }

        java.util.Arrays.sort(lengths);
        int size = documents.size();

        return new CorpusStats(
                size,
                hostCounts.size(),
                totalOutlinks,
                distinctTargets,
                (double) totalOutlinks / size,
                dangling,
                (double) totalTokens / size,
                lengths[size / 2],
                oldest,
                newest,
                sortedDesc(languageCounts),
                MinHeap.topK(toCounted(hostCounts), TOP_HOSTS,
                        Comparator.comparingLong(UsageSnapshot.Counted::count)),
                lastDays(perDay, zone));
    }

    /**
     * Đếm <b>xấp xỉ</b> số đích liên kết phân biệt bằng {@link BloomFilter}.
     *
     * <h2>Vì sao không dùng {@code HashSet}</h2>
     *
     * <p>Bản đầu tiên của lớp này giữ một {@code HashSet<String>} mọi đích liên
     * kết. Trên corpus thật — 31.030 trang, trung bình 69 liên kết mỗi trang —
     * đó là <b>2,1 triệu chuỗi URL</b> nằm trong heap chỉ để hiện đúng một con
     * số trên bảng điều khiển. Nó đã làm <b>hết bộ nhớ</b> khi hai
     * {@code ApplicationContext} cùng sống trong một JVM lúc chạy kiểm thử: một
     * lỗi thật, phát hiện được vì bộ kiểm thử chạy nhiều context.
     *
     * <p>Bloom Filter đổi <i>độ chính xác tuyệt đối</i> lấy <i>bộ nhớ hằng
     * số</i>: khoảng 9,6 bit cho mỗi phần tử ở tỉ lệ dương tính giả 1%, tức vài
     * MB thay vì vài trăm MB. Đây đúng là cấu trúc mà chính crawler dùng cho
     * bài toán y hệt ("URL này gặp chưa"), nên nó cũng không phải một khái niệm
     * mới trong dự án.
     *
     * <h2>Sai số đi về MỘT phía, và đó là điều quan trọng</h2>
     *
     * <p>Bloom Filter chỉ có dương tính giả, không có âm tính giả. Nghĩa là nó
     * có thể nói "đã gặp rồi" về một URL thật ra chưa gặp — nên con số này
     * <b>chỉ có thể đếm THIẾU, không bao giờ đếm THỪA</b>. Với tỉ lệ 1%, sai số
     * dưới 1%. Một thống kê hiển thị sai 1% theo hướng biết trước thì dùng
     * được; một lần {@code OutOfMemoryError} thì không.
     */
    private static int countDistinctTargets(List<String> outlinks, BloomFilter seen) {
        int distinct = 0;
        for (String target : outlinks) {
            if (target == null) {
                continue;
            }
            if (!seen.mightContain(target)) {
                seen.add(target);
                distinct++;
            }
        }
        return distinct;
    }

    private static BloomFilter newTargetFilter(int documentCount) {
        long estimated = (long) documentCount * ESTIMATED_LINKS_PER_PAGE;
        int capacity = (int) Math.min(Math.max(estimated, 1_000), MAX_FILTER_ITEMS);
        return new BloomFilter(capacity, TARGET_FILTER_FPR);
    }

    /**
     * Chuỗi ngày <b>liên tục</b> tới hôm nay, kể cả ngày không crawl gì.
     *
     * <p>Bỏ ngày rỗng đi thì biểu đồ cột co lại và hai ngày cách nhau một tuần
     * đứng cạnh nhau trông như hai ngày liên tiếp — trục thời gian nói dối.
     */
    private static List<DayCount> lastDays(Map<LocalDate, Long> perDay, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        List<DayCount> days = new ArrayList<>(DAYS_TRACKED);
        for (int back = DAYS_TRACKED - 1; back >= 0; back--) {
            LocalDate date = today.minusDays(back);
            days.add(new DayCount(date.toString(), perDay.getOrDefault(date, 0L)));
        }
        return days;
    }

    private static List<UsageSnapshot.Counted> toCounted(Map<String, Long> counts) {
        List<UsageSnapshot.Counted> list = new ArrayList<>(counts.size());
        counts.forEach((label, count) -> list.add(new UsageSnapshot.Counted(label, count)));
        return list;
    }

    private static List<UsageSnapshot.Counted> sortedDesc(Map<String, Long> counts) {
        List<UsageSnapshot.Counted> list = toCounted(counts);
        list.sort(Comparator.comparingLong(UsageSnapshot.Counted::count).reversed());
        return list;
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "(không rõ)";
        }
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
}
