package com.vnsearch.crawler.bus;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Một ảnh mà {@code Image Download Service} tìm thấy trên một trang.
 *
 * <p>Là <b>đầu ra</b> của Modular Service thứ hai trong sơ đồ, và cũng là ví
 * dụ rõ nhất cho lập luận "vì sao tách service": nội dung ảnh không liên quan
 * gì tới chỉ mục văn bản, nhưng nó lại là thứ tốn băng thông nhất trong cả hệ
 * thống. Để nó chung tiến trình với crawler nghĩa là một đợt tải ảnh sẽ làm
 * chậm việc tải trang; tách ra thì hai việc co giãn độc lập.
 *
 * <h2>Vì sao mặc định KHÔNG tải nội dung ảnh</h2>
 *
 * <p>Trường {@code contentHash} và {@code sizeBytes} chỉ có giá trị khi
 * {@code app.crawler.images.download=true}. Mặc định là {@code false}, và đó
 * là lựa chọn có chủ ý chứ không phải chưa làm xong:
 *
 * <ul>
 *   <li><b>Chi phí.</b> Một trang tin trung bình có 20–40 ảnh, mỗi ảnh
 *       100–500&nbsp;KB. Tải hết cho một corpus 30.000 trang là hàng trăm GB —
 *       vượt xa toàn bộ phần văn bản, cho một hệ thống <i>tìm kiếm văn
 *       bản</i>.</li>
 *   <li><b>Bề mặt tấn công.</b> Mỗi lần tải ảnh là một lần mở kết nối tới một
 *       địa chỉ do trang đích chỉ định. Đó chính là đường SSRF mà
 *       {@code HtmlDownloader} đã phải vá cho HTML; bật tải ảnh là mở lại
 *       đúng đường ấy ở một khối khác. Nên khi bật, service <b>bắt buộc</b>
 *       dùng lại {@code SeedUrlValidator} chứ không tự mở kết nối.</li>
 *   <li><b>Pháp lý.</b> Siêu dữ liệu ảnh là dữ liệu về trang; bản sao ảnh là
 *       bản sao tác phẩm có bản quyền.</li>
 * </ul>
 *
 * <p>Ở chế độ mặc định, service vẫn làm đủ việc có ích: đếm ảnh, phân bố theo
 * host, phát hiện ảnh thiếu {@code alt} (tín hiệu chất lượng trang), và ghi
 * lại địa chỉ để một lần chạy sau có thể tải nếu cần.
 *
 * @param pageUrl     trang chứa ảnh
 * @param host        host của trang — khoá phân hoạch
 * @param imageUrl    địa chỉ tuyệt đối, đã chuẩn hoá, của ảnh
 * @param altText     nội dung thuộc tính {@code alt}; rỗng nếu trang không có
 * @param declaredWidth  chiều rộng khai báo trong HTML, {@code -1} nếu không khai báo
 * @param declaredHeight chiều cao khai báo trong HTML, {@code -1} nếu không khai báo
 * @param sizeBytes   kích thước thật, {@code -1} khi chưa tải nội dung
 * @param contentHash vân tay SHA-256 của nội dung, {@code null} khi chưa tải
 */
public record ImageFound(
        String pageUrl,
        String host,
        String imageUrl,
        String altText,
        int declaredWidth,
        int declaredHeight,
        long sizeBytes,
        String contentHash) {

    public ImageFound {
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new IllegalArgumentException("ImageFound.pageUrl không được rỗng");
        }
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("ImageFound.imageUrl không được rỗng");
        }
        altText = altText == null ? "" : altText;
    }

    /** Bản ghi chỉ có siêu dữ liệu — chế độ mặc định, không tải nội dung ảnh. */
    public static ImageFound metadataOnly(String pageUrl, String host, String imageUrl,
                                           String altText, int width, int height) {
        return new ImageFound(pageUrl, host, imageUrl, altText, width, height, -1L, null);
    }

    /**
     * {@code true} khi nội dung ảnh đã thực sự được tải về và băm.
     *
     * <p><b>{@code @JsonIgnore} là BẮT BUỘC, không phải cho gọn.</b> Jackson
     * coi mọi phương thức dạng {@code isXxx()} là một thuộc tính đọc được, nên
     * nếu không chặn thì nó ghi thêm một trường {@code "downloaded"} vào JSON.
     * Trường đó <i>không</i> ứng với component nào của record, nên khi
     * consumer đọc lại, Jackson ném:
     *
     * <pre>UnrecognizedPropertyException: Unrecognized field "downloaded"</pre>
     *
     * <p>Hậu quả: <b>mọi</b> thông điệp ảnh chết ở phía consumer, rồi bị đẩy
     * sang dead-letter topic. Ở chế độ in-process thì không có gì lộ ra — đối
     * tượng đi thẳng từ tay này sang tay kia, không ai serialize cả.
     *
     * <p>Lỗi này đã xảy ra thật và bị {@code KafkaCrawlBusIT} bắt. Nó là lý do
     * cụ thể để bộ test tích hợp tồn tại.
     */
    @JsonIgnore
    public boolean isDownloaded() {
        return contentHash != null;
    }

    /**
     * Ảnh thiếu văn bản thay thế — tín hiệu chất lượng trang mà Analytics
     * Service tổng hợp lại.
     *
     * <p>Cũng {@code @JsonIgnore}: giá trị suy ra được từ {@code altText}, nên
     * ghi vào thông điệp là vừa thừa vừa tạo thêm một trường có thể lệch với
     * nguồn của nó.
     */
    @JsonIgnore
    public boolean missingAlt() {
        return altText.isBlank();
    }
}
