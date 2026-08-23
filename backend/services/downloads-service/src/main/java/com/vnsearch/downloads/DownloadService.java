package com.vnsearch.downloads;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nghiệp vụ sổ tải xuống.
 *
 * <p>Nơi duy nhất biết luật chuyển trạng thái và luật idempotent. Controller
 * chỉ dịch HTTP; repository chỉ nói SQL.
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private static final int SIZE_TOI_DA = 200;

    private final DownloadRepository repository;
    private final Clock clock;

    public DownloadService(DownloadRepository repository) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
    }

    /** Trạng thái mới không hợp lệ với trạng thái hiện tại. */
    public static class ChuyenTrangThaiKhongHopLe extends RuntimeException {
        public ChuyenTrangThaiKhongHopLe(DownloadState cu, DownloadState moi) {
            super("Không chuyển được từ " + cu + " sang " + moi + ".");
        }
    }

    /**
     * Bắt đầu theo dõi một lượt tải — <b>idempotent</b> theo {@code id}.
     *
     * <p>Gọi lại với cùng id KHÔNG tạo dòng mới và KHÔNG báo lỗi: nó trả về
     * dòng cũ. Nhờ vậy máy khách cứ việc thử lại khi mất mạng mà không phải
     * biết lần trước đã ghi được hay chưa.
     */
    @Transactional
    public DownloadRecord batDau(String username, UUID id, String sourceUrl, String fileName,
                                 String mimeType, Long totalBytes, String localPath,
                                 String deviceId) {
        Optional<DownloadRecord> daCo = repository.tim(id, username);
        if (daCo.isPresent()) {
            // Lần gọi lặp lại. Giữ nguyên thời điểm bắt đầu: nó là sự thật lịch
            // sử, không phải thứ được phép ghi đè mỗi lần thử lại.
            return daCo.get();
        }
        DownloadRecord moi = new DownloadRecord(id, username, sourceUrl, fileName, mimeType,
                totalBytes, 0L, DownloadState.IN_PROGRESS, localPath, deviceId,
                clock.instant(), null, clock.instant());
        repository.luu(moi);
        return moi;
    }

    /**
     * Cập nhật tiến độ hoặc trạng thái.
     *
     * <p><b>Chặn chuyển trạng thái vô lý.</b> Gói tin cập nhật tiến độ đến
     * TRỄ hơn gói tin báo hoàn tất là chuyện thường xuyên trên mạng; không chặn
     * thì một tệp đã tải xong sẽ hiện lại thành "đang tải 87%" và nằm đó vĩnh
     * viễn. Xem {@link DownloadState#chuyenDuocSang}.
     *
     * <p>Ném ngoại lệ thay vì bỏ qua trong im lặng: một lần chuyển sai là dấu
     * hiệu máy khách đang gửi sai thứ tự, và đó là thứ cần biết chứ không phải
     * thứ cần giấu.
     *
     * <p>{@code receivedBytes} chỉ được phép TĂNG. Ràng buộc này bảo vệ ràng
     * buộc {@code ck_downloads_total} ở tầng CSDL và cũng bảo vệ thanh tiến độ
     * khỏi nhảy lùi khi hai gói tin đến ngược thứ tự.
     */
    @Transactional
    public Optional<DownloadRecord> capNhat(String username, UUID id, Long receivedBytes,
                                            DownloadState trangThaiMoi, String localPath) {
        Optional<DownloadRecord> hienTai = repository.tim(id, username);
        if (hienTai.isEmpty()) {
            return Optional.empty();
        }
        DownloadRecord cu = hienTai.get();
        DownloadState trangThai = trangThaiMoi == null ? cu.state() : trangThaiMoi;

        if (!cu.state().chuyenDuocSang(trangThai)) {
            throw new ChuyenTrangThaiKhongHopLe(cu.state(), trangThai);
        }

        long soByte = receivedBytes == null ? cu.receivedBytes()
                : Math.max(cu.receivedBytes(), receivedBytes);

        // Ràng buộc ck_downloads_finished đòi: đã kết thúc thì PHẢI có
        // finished_at, chưa kết thúc thì PHẢI không có. Tính ở đây thay vì bắt
        // máy khách gửi lên — một máy khách quên gửi sẽ làm cả phép ghi đổ.
        Instant ketThuc = trangThai.daKetThuc()
                ? (cu.finishedAt() == null ? clock.instant() : cu.finishedAt())
                : null;

        DownloadRecord moi = new DownloadRecord(cu.id(), username, cu.sourceUrl(), cu.fileName(),
                cu.mimeType(), cu.totalBytes(), soByte, trangThai,
                localPath == null ? cu.localPath() : localPath,
                cu.deviceId(), cu.startedAt(), ketThuc, clock.instant());
        repository.luu(moi);
        return Optional.of(moi);
    }

    public List<DownloadRecord> danhSach(String username, int trang, int soLuong) {
        int limit = Math.min(Math.max(soLuong, 1), SIZE_TOI_DA);
        return repository.cuaNguoiDung(username, Math.max(trang, 0) * limit, limit);
    }

    public List<DownloadRecord> dangChay(String username) {
        return repository.dangChay(username);
    }

    public boolean xoa(String username, UUID id) {
        return repository.xoa(id, username);
    }

    /** Xoá sổ tải xuống (chỉ những mục đã kết thúc). */
    public int xoaHet(String username) {
        int soDong = repository.xoaHet(username);
        // Ghi lại mọi lần xoá hàng loạt: đây là dữ liệu bị huỷ vĩnh viễn, và
        // dòng log này là câu trả lời duy nhất cho câu hỏi "sổ tải xuống của
        // tôi đâu rồi".
        log.info("Xoá sổ tải xuống của {}: {} mục", username, soDong);
        return soDong;
    }

    public int dem(String username) {
        return repository.dem(username);
    }
}
