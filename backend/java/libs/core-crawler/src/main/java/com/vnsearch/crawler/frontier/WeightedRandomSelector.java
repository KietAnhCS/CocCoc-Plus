package com.vnsearch.crawler.frontier;

import java.util.Random;

/**
 * Bộ chọn hàng đợi trước theo <b>ngẫu nhiên có trọng số</b> — cách làm mà sơ
 * đồ URL Frontier mô tả cho khối {@code Front queue selector}.
 *
 * <p><b>Vấn đề nó giải quyết: bỏ đói.</b> Nếu luôn lấy mức ưu tiên cao nhất
 * còn URL, thì chừng nào mức 0 còn URL mới chảy vào, các mức thấp hơn
 * <b>không bao giờ</b> được phục vụ. Trên web điều đó xảy ra thường xuyên:
 * mỗi trang crawl được lại sinh thêm hàng chục URL nông, nên hàng đợi mức cao
 * gần như không bao giờ cạn.
 *
 * <p><b>Trọng số giảm theo luỹ thừa 2:</b> mức {@code i} có trọng số
 * {@code 2^(n-1-i)}. Với 5 mức, trọng số là {@code 16, 8, 4, 2, 1} trên tổng
 * 31:
 *
 * <table border="1">
 *   <caption>Xác suất chọn khi cả 5 mức đều còn URL</caption>
 *   <tr><th>Mức</th><td>0</td><td>1</td><td>2</td><td>3</td><td>4</td></tr>
 *   <tr><th>Xác suất</th><td>51,6%</td><td>25,8%</td><td>12,9%</td><td>6,5%</td><td>3,2%</td></tr>
 * </table>
 *
 * <p>Mức cao vẫn được ưu ái rõ rệt (một nửa số lượt), nhưng mức thấp nhất vẫn
 * nhận được khoảng 1 trên 31 lượt — <b>bỏ đói là không thể</b>. Đó chính là
 * tính chất mà bộ chọn tất định không có.
 *
 * <p><b>Chỉ tính trọng số trên các hàng đợi KHÔNG rỗng.</b> Nếu tính cả hàng
 * đợi rỗng, phần trọng số của chúng thành "lượt trống" và bộ chọn phải bốc
 * lại nhiều lần — với 4 trên 5 hàng đợi rỗng thì kỳ vọng số lần bốc lại tăng
 * vọt. Chuẩn hoá lại trên các hàng đợi còn hàng cho ra đúng một lần bốc.
 *
 * <p><b>Vẫn lặp lại được.</b> Mặc định dùng {@link Random} với hạt giống cố
 * định, nên hai lần chạy cùng dữ liệu cho ra cùng thứ tự — điều kiện cần để
 * so sánh hai lần thí nghiệm trong báo cáo. Truyền hạt giống khác nếu muốn
 * đổi.
 *
 * <p>Không thread-safe (do {@link Random} dùng chung); {@link UrlFrontier}
 * gọi nó bên trong khối {@code synchronized}.
 */
public final class WeightedRandomSelector implements FrontQueueSelector {

    /** Hạt giống mặc định — cố định để phiên crawl lặp lại được. */
    public static final long DEFAULT_SEED = 20240801L;

    /**
     * Trần số mức. {@code 2^(n-1)} phải nằm gọn trong {@code long}, và trên
     * thực tế quá 30 mức thì các mức cuối có xác suất nhỏ tới mức vô nghĩa.
     */
    private static final int MAX_LEVELS = 30;

    private final Random random;

    public WeightedRandomSelector() {
        this(DEFAULT_SEED);
    }

    public WeightedRandomSelector(long seed) {
        this(new Random(seed));
    }

    public WeightedRandomSelector(Random random) {
        if (random == null) {
            throw new IllegalArgumentException("random không được null");
        }
        this.random = random;
    }

    @Override
    public int select(int[] queueSizes) {
        int levels = queueSizes.length;
        if (levels > MAX_LEVELS) {
            throw new IllegalArgumentException(
                    "Chỉ hỗ trợ tối đa " + MAX_LEVELS + " mức, nhận được: " + levels);
        }

        // Lượt 1: cộng trọng số của các hàng đợi CÒN HÀNG.
        long totalWeight = 0;
        for (int i = 0; i < levels; i++) {
            if (queueSizes[i] > 0) {
                totalWeight += weightOf(i, levels);
            }
        }
        if (totalWeight == 0) {
            return -1; // mọi hàng đợi đều rỗng
        }

        // Lượt 2: bốc một điểm trong [0, totalWeight) rồi đi tới khi vượt qua nó.
        long pick = Math.floorMod(random.nextLong(), totalWeight);
        for (int i = 0; i < levels; i++) {
            if (queueSizes[i] == 0) {
                continue;
            }
            pick -= weightOf(i, levels);
            if (pick < 0) {
                return i;
            }
        }
        // Không tới được: tổng trọng số đã tính đúng ở lượt 1.
        throw new IllegalStateException("Không chọn được hàng đợi dù tổng trọng số > 0");
    }

    private static long weightOf(int level, int levels) {
        return 1L << (levels - 1 - level);
    }

    /** Demo minh hoạ nhỏ để chụp màn hình làm báo cáo. */
    public static void main(String[] args) {
        WeightedRandomSelector selector = new WeightedRandomSelector();
        int[] sizes = {10, 10, 10, 10, 10};
        int[] hits = new int[sizes.length];
        for (int i = 0; i < 100_000; i++) {
            hits[selector.select(sizes)]++;
        }
        System.out.println("Phân bố 100.000 lượt chọn trên 5 mức (cả 5 đều còn URL):");
        for (int i = 0; i < hits.length; i++) {
            System.out.printf("  mức %d: %5.2f%%  (lý thuyết %5.2f%%)%n",
                    i, hits[i] / 1000.0, weightOf(i, hits.length) * 100.0 / 31);
        }
    }
}
