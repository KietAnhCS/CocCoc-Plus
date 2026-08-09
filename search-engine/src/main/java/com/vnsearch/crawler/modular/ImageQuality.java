package com.vnsearch.crawler.modular;

import com.vnsearch.crawler.bus.ImageFound;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chấm điểm một ảnh để chọn <b>ảnh đại diện</b> cho trang chứa nó.
 *
 * <h2>Bài toán</h2>
 *
 * <p>{@link ImageStore} chỉ giữ MỘT ảnh cho mỗi trang. Một trang tin thường có
 * 20–50 ảnh, trong đó đúng một tấm là ảnh chính của bài, còn lại là logo, icon
 * chia sẻ, ảnh của các bài "đọc thêm" ở chân trang. Lớp này quyết định tấm nào
 * được giữ.
 *
 * <h2>Ràng buộc: KHÔNG có bức ảnh trong tay</h2>
 *
 * <p>Hệ thống mặc định không tải nội dung ảnh ({@code app.crawler.images
 * .download=false}), nên mọi khái niệm "nét", "sắc sảo", "độ phân giải thật"
 * đều <b>không đo được</b>. Thứ có trong tay chỉ là siêu dữ liệu. Lớp này vì
 * thế không đo chất lượng ảnh — nó ước lượng <i>khả năng một ảnh là ảnh chính
 * của bài</i>, và đó là hai việc khác nhau. Ghi rõ ra đây để không ai đọc tên
 * lớp rồi tưởng nó làm nhiều hơn thực tế.
 *
 * <h2>Tín hiệu, và độ phủ đo được trên corpus thật</h2>
 *
 * <p>Đo trên 25.707 ảnh của 1.013 trang (phiên crawl 1.028 trang):
 *
 * <table border="1">
 *   <caption>Tín hiệu nào dùng được, và dùng được cho bao nhiêu ảnh</caption>
 *   <tr><th>Tín hiệu</th><th>Độ phủ</th><th>Vai trò</th></tr>
 *   <tr><td>{@code declaredWidth/Height} trong HTML</td><td>18,2%</td><td>kích thước, tin cậy nhất</td></tr>
 *   <tr><td>Tham số {@code ?w=} trong URL của CDN</td><td>14,5%</td><td>kích thước</td></tr>
 *   <tr><td>Dạng {@code /640x480/} trong đường dẫn</td><td>6,6%</td><td>kích thước</td></tr>
 *   <tr><td><b>Có ít nhất một tín hiệu kích thước</b></td><td><b>39,3%</b></td><td>—</td></tr>
 *   <tr><td>URL chứa {@code thumb/icon/logo/avatar}</td><td>24,3%</td><td>dấu hiệu ÂM</td></tr>
 *   <tr><td>Đuôi {@code .svg} hoặc {@code .gif}</td><td>12%</td><td>dấu hiệu ÂM</td></tr>
 * </table>
 *
 * <h2>Vì sao KHÔNG dùng thứ tự DOM làm tín hiệu chính</h2>
 *
 * <p>Cách hiển nhiên nhất là lấy ảnh đầu tiên trong DOM — {@code FeedController}
 * đang làm đúng vậy, và {@link ImageStore} từng ghi trong Javadoc rằng "ảnh đầu
 * tiên gần như luôn là ảnh chính".
 *
 * <p><b>Đo lại thì điều đó sai.</b> Trên 323 trang có từ hai ảnh khai báo kích
 * thước trở lên, ảnh đầu DOM cũng là ảnh lớn nhất chỉ trong <b>19%</b> số
 * trang. Nguyên nhân dễ thấy khi nhìn HTML thật: logo toà soạn, icon tìm kiếm
 * và ảnh đại diện tác giả nằm trong {@code <header>}, tức là ĐỨNG TRƯỚC ảnh
 * chính của bài.
 *
 * <p>Nên thứ tự DOM ở đây chỉ còn là <b>tiêu chí phá hoà</b> cuối cùng, dùng khi
 * mọi tín hiệu khác bằng nhau — vẫn có ích, vì trong hai ảnh không phân biệt
 * được thì ảnh xuất hiện sớm hơn vẫn nhỉnh hơn một chút.
 *
 * <h2>Ba bậc, không phải một thang điểm phẳng</h2>
 *
 * <p>Điểm được chia bậc chứ không cộng dồn, vì các tín hiệu không cùng đơn vị và
 * cộng chúng lại sẽ tạo ra những đánh đổi vô nghĩa — kiểu "một icon 300px thắng
 * một ảnh bài 280px". Bậc thì không cho phép điều đó: một ảnh trang trí không
 * bao giờ vượt được một ảnh nội dung, dù to đến đâu.
 */
public final class ImageQuality {

    /**
     * Đuôi tệp gần như chắc chắn không phải ảnh nội dung.
     *
     * <p>{@code svg} là đồ hoạ vector — logo, icon, biểu đồ giao diện. {@code
     * gif} ở web hiện đại hầu hết là ảnh động nhỏ hoặc pixel theo dõi. Cộng lại
     * 12% corpus.
     */
    private static final Pattern DECORATIVE_EXTENSION =
            Pattern.compile("\\.(svg|gif|ico|bmp)(\\?|#|$)", Pattern.CASE_INSENSITIVE);

    /**
     * Từ khoá trong URL cho biết đây là bản thu nhỏ hoặc ảnh giao diện.
     *
     * <p>Khớp trên URL đã hạ chữ thường. Bao 24,3% corpus — tín hiệu âm có độ
     * phủ lớn nhất, và là thứ duy nhất bắt được logo ở những trang không khai
     * báo kích thước gì cả.
     */
    private static final Pattern DECORATIVE_PATH = Pattern.compile(
            "thumb|icon|logo|avatar|sprite|placeholder|blank|banner|badge|button|" +
            "favicon|watermark|1x1|pixel|spacer");

    /** Tham số bề rộng do CDN gắn vào: {@code ?w=680}, {@code &width=1200}. */
    private static final Pattern WIDTH_PARAM =
            Pattern.compile("[?&](?:w|width|rw|mw)=(\\d{2,4})", Pattern.CASE_INSENSITIVE);

    /** Kích thước nhúng trong đường dẫn: {@code /640x480/}, {@code _1200x630.jpg}. */
    private static final Pattern SIZE_IN_PATH =
            Pattern.compile("[_\\-/](\\d{3,4})x(\\d{3,4})[_\\-./]");

    /**
     * Bề rộng tối thiểu để một ảnh được coi là ảnh nội dung.
     *
     * <p>Dưới mốc này gần như chắc chắn là icon hoặc logo: ảnh bài báo hẹp nhất
     * gặp trên corpus vẫn quanh 300–400px, còn logo đo được là 100×42 và
     * 236×61. Đặt ở 200 để chừa biên an toàn cho ảnh chân dung hẹp.
     */
    private static final int MIN_CONTENT_WIDTH = 200;

    private ImageQuality() {
    }

    // --- Ba bậc, từ tốt nhất xuống ------------------------------------------

    /** Ảnh nội dung, và <b>biết</b> nó đủ lớn. */
    private static final int TIER_SIZED_CONTENT = 3;

    /** Ảnh nội dung, không có tín hiệu kích thước nào — 60,7% corpus rơi vào đây. */
    private static final int TIER_UNKNOWN = 2;

    /** Có tín hiệu kích thước, nhưng NHỎ — gần như chắc là icon. */
    private static final int TIER_SMALL = 1;

    /** Trang trí: đuôi vector/ảnh động, hoặc URL tự khai là thumbnail. */
    private static final int TIER_DECORATIVE = 0;

    /**
     * So sánh hai ảnh của <b>cùng một trang</b>: trả về số dương nếu {@code a}
     * xứng đáng làm ảnh đại diện hơn {@code b}.
     *
     * <p>Thứ tự xét, dừng ngay khi phân định được:
     *
     * <ol>
     *   <li><b>Bậc</b> — ảnh nội dung luôn thắng ảnh trang trí.</li>
     *   <li><b>Bề rộng ước lượng</b> — trong cùng bậc, to hơn thì thắng.</li>
     *   <li><b>Có {@code alt}</b> — ảnh mang văn bản thay thế là ảnh truyền đạt
     *       nội dung, theo đúng ngữ nghĩa của chuẩn tiếp cận.</li>
     *   <li><b>Đến trước</b> — {@code a} chỉ thắng nếu THỰC SỰ hơn ở một tiêu
     *       chí trên; hoà thì giữ ảnh đã có. Nhờ vậy kết quả không phụ thuộc
     *       thứ tự thông điệp đến từ bus, vốn không xác định ở chế độ Kafka.</li>
     * </ol>
     */
    public static int compare(ImageFound a, ImageFound b) {
        if (b == null) {
            return 1;
        }
        if (a == null) {
            return -1;
        }

        int tierDiff = tier(a) - tier(b);
        if (tierDiff != 0) {
            return tierDiff;
        }

        int widthDiff = estimatedWidth(a) - estimatedWidth(b);
        if (widthDiff != 0) {
            return widthDiff;
        }

        // `alt` phân biệt ảnh NỘI DUNG với ảnh TRANG TRÍ theo đúng quy ước của
        // chuẩn tiếp cận: ảnh trang trí được quy định để trống thuộc tính này.
        return Boolean.compare(!a.missingAlt(), !b.missingAlt());
    }

    /** {@code true} nếu {@code candidate} nên thay thế {@code current}. */
    public static boolean isBetter(ImageFound candidate, ImageFound current) {
        return compare(candidate, current) > 0;
    }

    private static int tier(ImageFound image) {
        String url = image.imageUrl().toLowerCase(java.util.Locale.ROOT);

        if (DECORATIVE_EXTENSION.matcher(url).find() || DECORATIVE_PATH.matcher(url).find()) {
            return TIER_DECORATIVE;
        }

        int width = estimatedWidth(image);
        if (width <= 0) {
            // Không có tín hiệu nào. KHÔNG xếp xuống bậc thấp nhất: 60,7% ảnh
            // rơi vào đây, và phần lớn trong số đó là ảnh bài viết bình thường
            // trên những trang không khai báo kích thước. Coi chúng là đáng ngờ
            // sẽ khiến icon .png có khai báo kích thước thắng ảnh bài thật.
            return TIER_UNKNOWN;
        }
        return width >= MIN_CONTENT_WIDTH ? TIER_SIZED_CONTENT : TIER_SMALL;
    }

    /**
     * Bề rộng ước lượng, hoặc {@code 0} nếu không suy ra được.
     *
     * <p>Ưu tiên kích thước khai báo trong HTML vì đó là thứ trang tự nói về
     * ảnh của mình. Không có thì đọc từ URL — CDN ảnh của báo Việt Nam gắn
     * {@code ?w=} rất đều tay, và con số đó chính là bề rộng bản đã cắt.
     */
    static int estimatedWidth(ImageFound image) {
        if (image.declaredWidth() > 0) {
            return image.declaredWidth();
        }

        String url = image.imageUrl();

        Matcher param = WIDTH_PARAM.matcher(url);
        if (param.find()) {
            return parseOrZero(param.group(1));
        }

        Matcher inPath = SIZE_IN_PATH.matcher(url);
        if (inPath.find()) {
            return parseOrZero(inPath.group(1));
        }

        return 0;
    }

    private static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            // Mẫu regex đã chặn ở 2–4 chữ số nên nhánh này gần như không xảy ra;
            // giữ lại để một lần sửa mẫu về sau không biến thành ngoại lệ trên
            // đường chạy nóng của crawler.
            return 0;
        }
    }
}
