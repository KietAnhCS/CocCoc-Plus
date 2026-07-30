package com.vnsearch.crawler;

import java.util.Set;

/**
 * <b>Builder pattern</b> — cau hinh BAT BIEN cho mot phien crawl.
 *
 * <p><b>Van de cua ban cu.</b> Truoc day {@code CrawlConfig} co truong
 * {@code public} va setter tra ve {@code this}, nghia la:
 * <pre>
 *   CrawlConfig cfg = new CrawlConfig().maxPages(5000);
 *   crawler.crawl(seeds, cfg);
 *   cfg.maxPages = -1;        // sua GIUA phien crawl, khong ai chan
 * </pre>
 * Va khong co kiem tra tinh hop le o dau ca: {@code maxPages = -1},
 * {@code threadCount = 0}, {@code maxDepth = -5} deu duoc chap nhan, roi hong
 * o mot cho khac hoan toan (chia cho 0 khi tinh {@code count % progressEveryN},
 * hoac {@code newFixedThreadPool(0)} nem ngoai le kho hieu).
 *
 * <p><b>Ban nay.</b> Doi tuong bat bien, dung qua {@link Builder}, va MOI
 * kiem tra tinh hop le nam trong {@link Builder#build()} — mot cho duy nhat.
 * Cau hinh sai bi bat NGAY, truoc khi crawl bat dau, thay vi sau 30 phut.
 *
 * <p>Bat bien con cho mot loi ich ve dong thoi: 12 worker thread cung doc
 * cau hinh nay ma khong can {@code volatile} hay dong bo gi.
 */
public final class CrawlConfig {

    private final int maxDepth;
    private final int maxPages;
    private final int threadCount;
    private final Set<String> allowedDomains;
    private final int maxDurationMinutes;
    private final int progressEveryN;

    private CrawlConfig(Builder builder) {
        this.maxDepth = builder.maxDepth;
        this.maxPages = builder.maxPages;
        this.threadCount = builder.threadCount;
        this.allowedDomains = Set.copyOf(builder.allowedDomains); // ban sao BAT BIEN
        this.maxDurationMinutes = builder.maxDurationMinutes;
        this.progressEveryN = builder.progressEveryN;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxPages() {
        return maxPages;
    }

    public int threadCount() {
        return threadCount;
    }

    /** Tap domain duoc phep crawl; rong nghia la KHONG gioi han. */
    public Set<String> allowedDomains() {
        return allowedDomains;
    }

    public int maxDurationMinutes() {
        return maxDurationMinutes;
    }

    public int progressEveryN() {
        return progressEveryN;
    }

    @Override
    public String toString() {
        return "CrawlConfig{maxDepth=" + maxDepth + ", maxPages=" + maxPages
                + ", threadCount=" + threadCount + ", domains=" + allowedDomains.size()
                + ", maxDurationMinutes=" + maxDurationMinutes + "}";
    }

    /** Builder co kiem tra tap trung trong {@link #build()}. */
    public static final class Builder {

        private int maxDepth = 3;
        private int maxPages = 100;
        private int threadCount = 4;
        private Set<String> allowedDomains = Set.of();
        private int maxDurationMinutes = 60;
        private int progressEveryN = 1;

        private Builder() {
        }

        public Builder maxDepth(int value) {
            this.maxDepth = value;
            return this;
        }

        public Builder maxPages(int value) {
            this.maxPages = value;
            return this;
        }

        public Builder threadCount(int value) {
            this.threadCount = value;
            return this;
        }

        public Builder allowedDomains(Set<String> value) {
            this.allowedDomains = value == null ? Set.of() : value;
            return this;
        }

        public Builder maxDurationMinutes(int value) {
            this.maxDurationMinutes = value;
            return this;
        }

        /** Chi in log tien do moi N trang — tranh spam console khi crawl hang nghin trang. */
        public Builder progressEveryN(int value) {
            this.progressEveryN = value;
            return this;
        }

        /**
         * Kiem tra MOI rang buoc tai mot cho duy nhat, roi tao doi tuong bat bien.
         *
         * @throws IllegalArgumentException neu bat ky tham so nao khong hop le
         */
        public CrawlConfig build() {
            if (maxPages <= 0) {
                throw new IllegalArgumentException("maxPages phai > 0, nhan duoc: " + maxPages);
            }
            if (maxDepth < 0) {
                throw new IllegalArgumentException("maxDepth phai >= 0, nhan duoc: " + maxDepth);
            }
            if (threadCount <= 0) {
                throw new IllegalArgumentException("threadCount phai > 0, nhan duoc: " + threadCount);
            }
            if (maxDurationMinutes <= 0) {
                throw new IllegalArgumentException(
                        "maxDurationMinutes phai > 0, nhan duoc: " + maxDurationMinutes);
            }
            if (progressEveryN <= 0) {
                // Neu bang 0 thi "count % progressEveryN" chia cho 0 -> ArithmeticException
                // o giua vong lap worker, mot cho hoan toan khong lien quan toi nguyen nhan.
                throw new IllegalArgumentException(
                        "progressEveryN phai > 0, nhan duoc: " + progressEveryN);
            }
            return new CrawlConfig(this);
        }
    }
}
