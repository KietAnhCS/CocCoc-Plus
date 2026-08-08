package com.vnsearch.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Chan <b>SSRF</b> (Server-Side Request Forgery) tren cac URL hat giong den tu
 * ben ngoai.
 *
 * <p><b>Lo hong nay da tung ton tai va da duoc kiem chung.</b> Truoc lop nay,
 * {@code POST /api/admin/crawl} nhan bat ky URL nao ma khong hoi gi:
 *
 * <pre>
 *   $ curl -X POST .../api/admin/crawl \
 *          -d '{"seedUrls":["http://169.254.169.254/latest/meta-data/"]}'
 *   {"jobId":"0fa63240-...","status":"STARTED"}
 * </pre>
 *
 * Tren mot may ao dam may, dia chi do tra ve <b>khoa IAM tam thoi</b>. Va chuoi
 * khai thac khep kin: noi dung tai ve duoc dua vao chi muc, roi doc lai qua
 * {@code GET /api/search} — tuc mot kenh rut du lieu hoan chinh, ke tan cong
 * khong can nhin thay phan hoi cua request ban dau.
 *
 * <p><b>Vi sao phai kiem tra SAU khi phan giai DNS.</b> Loc tren chuoi URL la
 * vo dung: ke tan cong dang ky mot ten mien cong khai, tro ban ghi A cua no ve
 * {@code 127.0.0.1} hoac {@code 169.254.169.254}. Chuoi
 * {@code http://evil.example.com/} khong co gi dang ngo, nhung ket noi thi di
 * thang vao mang noi bo. Nen phep kiem tra duy nhat co gia tri la:
 * <b>phan giai ten mien, roi xet DIA CHI IP thu duoc</b>.
 *
 * <p>Mot ten mien co the tra ve nhieu dia chi; lop nay loai neu <b>bat ky</b>
 * dia chi nao trong so do thuoc dai cam — chu khong phai "neu tat ca". Chi can
 * mot dia chi noi bo la du de ket noi di vao trong.
 *
 * <p><b>Han che da biet (TOCTOU).</b> Giua luc kiem tra va luc thuc su ket noi,
 * ban ghi DNS co the doi — day la ky thuat <i>DNS rebinding</i>. Chan triet de
 * doi phai ghim dia chi IP da kiem tra roi ket noi thang toi IP do (dat lai
 * header {@code Host}), tuc phai sua ca tang tai trang. O quy mo nay, phep kiem
 * tra sau phan giai da chan duoc toan bo cac ca khai thac truc tiep; ca
 * rebinding duoc ghi nhan la rui ro con lai, khong phai bi bo sot.
 *
 * <p>Lop nay <b>khong</b> ap dung cho hat giong trong ma nguon
 * ({@code MultiDomainCrawlRunner}) — nhung URL do do lap trinh vien viet ra,
 * khong phai dau vao nguoi dung. Chi dau vao ben ngoai moi can loc.
 */
public final class SeedUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SeedUrlValidator.class);

    /**
     * Ten mien noi bo cua trinh dieu phoi container, khong phan giai duoc thanh
     * IP cong khai nhung van tro toi dich vu noi bo.
     */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost", "metadata", "metadata.google.internal",
            "instance-data", "169.254.169.254");

    /**
     * Thông báo DUY NHẤT trả về cho bên gọi khi một URL bị từ chối vì lý do
     * mạng. Chi tiết thật (địa chỉ IP, tên máy) chỉ đi vào log phía máy chủ.
     *
     * <p><b>Vì sao mọi thông báo đều giống nhau.</b> Trước đây lớp này trả về
     * ba câu khác nhau: {@code "tro toi dia chi noi bo (10.0.3.17)"},
     * {@code "Khong phan giai duoc ten may"}, và không lỗi gì cả. Ba câu đó là
     * một <i>oracle</i> hoàn chỉnh — kẻ gọi đoán được host nào tồn tại, host nào
     * nằm trong mạng nội bộ, và còn đọc được cả địa chỉ IP thật. Lớp chặn vẫn
     * hoạt động đúng, nó chỉ nói quá nhiều: chặn được kết nối nhưng vẫn biến hệ
     * thống thành một máy quét mạng.
     */
    static final String REJECTED =
            "Seed URL khong duoc phep crawl. Kiem tra lai dia chi.";

    private SeedUrlValidator() {
    }

    /**
     * Nem ngoai le neu URL khong an toan de crawl.
     *
     * @throws IllegalArgumentException kem ly do doc duoc, de tang REST tra ve
     *                                  400 thay vi 500
     */
    public static void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("Seed URL rong");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Seed URL khong hop le: " + rawUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "Chi chap nhan http/https, nhan duoc: " + rawUrl);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Seed URL khong co ten may: " + rawUrl);
        }

        if (isBlockedHostname(host)) {
            log.warn("Chặn seed URL: tên máy nằm trong danh sách chặn ({})", host);
            throw new IllegalArgumentException(REJECTED);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // Không phân giải được thì cũng không crawl được — loại sớm ở đây.
            //
            // Thông báo trả về phải GIỐNG HỆT trường hợp bị chặn vì địa chỉ nội
            // bộ. Nếu hai ca này trả về hai câu khác nhau, kẻ gọi phân biệt được
            // "host này không tồn tại" với "host này tồn tại và ở trong mạng" —
            // tức một phép quét mạng nội bộ, dùng đúng chính lớp chặn SSRF làm
            // công cụ. Xem Javadoc mục "Vì sao mọi thông báo đều giống nhau".
            log.warn("Chặn seed URL: không phân giải được tên máy ({})", host);
            throw new IllegalArgumentException(REJECTED);
        }

        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                log.warn("Chặn seed URL trỏ tới địa chỉ nội bộ: {} -> {}",
                        host, address.getHostAddress());
                throw new IllegalArgumentException(REJECTED);
            }
        }
    }

    /**
     * Tên máy có nằm trong danh sách chặn theo TÊN (không phải theo địa chỉ) không?
     *
     * <p>Công khai để {@link HtmlDownloader} dùng lại: phép kiểm tra này phải
     * chạy ở <b>mọi</b> lần tải trang, không chỉ riêng hạt giống.
     */
    public static boolean isBlockedHostname(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return BLOCKED_HOSTNAMES.contains(lowerHost) || lowerHost.endsWith(".localhost");
    }

    /**
     * Dia chi co thuoc dai KHONG duoc phep crawl khong?
     *
     * <p>Dung cac phep kiem tra san co cua {@link InetAddress} thay vi tu so
     * sanh dai bit: chung da xu ly dung ca IPv4 lan IPv6, ke ca dang IPv4 nhung
     * trong vo IPv6 ({@code ::ffff:127.0.0.1}) — mot bien the ma phep so sanh
     * chuoi tu viet gan nhu chac chan bo sot.
     */
    public static boolean isBlockedAddress(InetAddress address) {
        return address.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress() // 169.254.0.0/16 (metadata dam may), fe80::/10
                || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
                || address.isAnyLocalAddress()  // 0.0.0.0, ::
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address)   // fc00::/7
                || isCarrierGradeNat(address);  // 100.64.0.0/10
    }

    /** {@code fc00::/7} — dai dia chi rieng cua IPv6, khong co san phep kiem tra. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * {@code 100.64.0.0/10} — dai dung chung cua nha mang (RFC 6598).
     * {@link InetAddress#isSiteLocalAddress()} khong tinh dai nay la noi bo,
     * nhung trong mot mang dam may no van tro toi ha tang khong cong khai.
     */
    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 100
                && (bytes[1] & 0xFF) >= 64
                && (bytes[1] & 0xFF) <= 127;
    }
}
