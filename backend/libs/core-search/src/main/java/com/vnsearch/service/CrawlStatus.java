package com.vnsearch.service;

/**
 * <b>State pattern</b> — trang thai cua mot job crawl, kem <b>may trang thai</b>
 * quy dinh chuyen tiep nao hop le.
 *
 * <p><b>Van de cua ban cu.</b> Truoc day trang thai la mot {@code String}:
 * <pre>
 *   volatile String status = "STARTED";
 *   ...
 *   job.status = "RUNNING";
 *   job.status = "DONE";
 * </pre>
 * Bon van de, tat ca deu la loi IM LANG:
 * <ol>
 *   <li>Go sai khong bi bat. {@code job.status = "DONEE"} bien dich binh
 *       thuong, va tang UI doc {@code "DONE"} se khong bao gio khop.</li>
 *   <li>Khong co rang buoc chuyen trang thai. Khong gi ngan
 *       {@code "DONE"} -&gt; {@code "RUNNING"}.</li>
 *   <li>Khong liet ke duoc tap trang thai — muon biet co nhung gi phai grep
 *       toan bo codebase.</li>
 *   <li>{@code switch} tren {@code String} khong co kiem tra day du nhanh.</li>
 * </ol>
 *
 * <p><b>Ban nay.</b> Enum + phuong thuc truu tuong
 * {@link #canTransitionTo(CrawlStatus)} cai de o tung hang, tuc moi trang thai
 * tu biet minh di duoc di dau. Them mot trang thai moi thi trinh bien dich bat
 * buoc phai khai bao chuyen tiep cua no.
 *
 * <pre>
 *   STARTED ──→ RUNNING ──→ DONE
 *      │           │
 *      └───────────┴──────→ FAILED
 * </pre>
 */
public enum CrawlStatus {

    /** Job da duoc tao va xep hang, chua chay. */
    STARTED {
        @Override
        public boolean canTransitionTo(CrawlStatus next) {
            return next == RUNNING || next == FAILED;
        }
    },

    /** Worker dang crawl. */
    RUNNING {
        @Override
        public boolean canTransitionTo(CrawlStatus next) {
            return next == DONE || next == FAILED;
        }
    },

    /** Hoan tat thanh cong — trang thai CUOI. */
    DONE {
        @Override
        public boolean canTransitionTo(CrawlStatus next) {
            return false;
        }
    },

    /** That bai — trang thai CUOI. */
    FAILED {
        @Override
        public boolean canTransitionTo(CrawlStatus next) {
            return false;
        }
    };

    /** Tu trang thai nay co duoc phep chuyen sang {@code next} khong. */
    public abstract boolean canTransitionTo(CrawlStatus next);

    /**
     * Trang thai cuoi (khong con chuyen tiep nao). Tang UI dung de biet khi
     * nao ngung hoi lai — truoc day phai so chuoi voi {@code "DONE"} va
     * {@code "FAILED"} rai rac o nhieu noi.
     */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}
