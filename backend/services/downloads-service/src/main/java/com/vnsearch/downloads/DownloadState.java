package com.vnsearch.downloads;

/**
 * Trang thai mot luot tai xuong.
 *
 * <p>Cac gia tri phai KHOP CHINH XAC voi rang buoc {@code ck_downloads_state}
 * trong V1__so_tai_xuong.sql. Them mot gia tri o day ma quen them vao rang
 * buoc thi moi phep ghi voi gia tri do se bi CSDL tu choi — mot loi lo ra
 * ngay, va do la huong dung: hong to con hon luu mot trang thai ma khong ai
 * biet nghia la gi.
 */
public enum DownloadState {

    /** Dang tai. Duy nhat trang thai nay va PAUSED cho phep cap nhat tien do. */
    IN_PROGRESS,

    /** Nguoi dung tam dung. Van con the tiep tuc. */
    PAUSED,

    COMPLETED,

    /** Nguoi dung huy. Khac INTERRUPTED: day la lua chon, khong phai su co. */
    CANCELLED,

    /**
     * Dut giua chung vi loi mang, het dia, hoac dong ung dung.
     *
     * <p>Tach khoi CANCELLED vi giao dien doi xu khac nhau: mot luot bi gian
     * doan co nut "thu lai", mot luot bi huy thi khong. Gop chung mot trang
     * thai la mat kha nang do do — khong ai tra loi duoc "bao nhieu phan tram
     * luot tai that bai vi loi".
     */
    INTERRUPTED;

    /** Da ket thuc chua — quyet dinh viec co bat buoc {@code finished_at} hay khong. */
    public boolean daKetThuc() {
        return this == COMPLETED || this == CANCELLED || this == INTERRUPTED;
    }

    /**
     * Chuyen tu trang thai nay sang {@code moi} co hop le khong.
     *
     * <p><b>Vi sao can luat nay o tang Java du CSDL da co rang buoc.</b> Rang
     * buoc CHECK chan duoc mot trang thai KHONG TON TAI, nhung khong chan duoc
     * mot chuyen doi VO LY: COMPLETED roi quay lai IN_PROGRESS van hop le voi
     * CHECK. Va do chinh la dieu se xay ra khi mot goi tin cap nhat tien do
     * den TRE hon goi tin bao hoan tat — chuyen thuong xuyen tren mang.
     *
     * <p>Khong co phep kiem nay, mot tep da tai xong se hien lai thanh "dang
     * tai 87%" va nam do vinh vien.
     */
    public boolean chuyenDuocSang(DownloadState moi) {
        if (this == moi) {
            return true;
        }
        return switch (this) {
            case IN_PROGRESS -> true;                   // sang bat ky trang thai nao
            case PAUSED -> moi != COMPLETED;            // phai tiep tuc truoc khi xong
            case COMPLETED, CANCELLED -> false;         // trang thai cuoi
            case INTERRUPTED -> moi == IN_PROGRESS;     // chi duoc thu lai
        };
    }
}
