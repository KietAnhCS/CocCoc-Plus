package com.vnsearch.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gioi han tan suat theo dia chi goi, bang thuat toan <b>token bucket</b> tu
 * cai dat.
 *
 * <p><b>Vi sao can.</b> Mot truy van tim kiem khong duoc cache co the phai cham
 * diem hang nghin ung vien. Khong co gioi han nao thi mot vong lap
 * {@code curl} don gian cung du lam CPU dat tran — khong can loi nao ca, chi
 * can goi API dung cach nhung qua nhanh.
 *
 * <p><b>Vi sao token bucket chu khong phai dem theo cua so co dinh.</b> Dem
 * theo phut lich ("toi da 120 request moi phut") co loi <i>bien cua so</i>:
 * 120 request luc 10:00:59 va 120 request nua luc 10:01:00 deu hop le, tuc 240
 * request trong mot giay. Token bucket khong co bien: gao do lai deu theo thoi
 * gian THUC, nen toc do trung binh luon bi chan, trong khi van cho phep mot
 * cum ngan bang dung suc chua cua gao.
 *
 * <pre>
 *   Cua so co dinh:  |....120....||....120....|   &lt;- 240 req quanh ranh gioi
 *                              ^^ bien
 *   Token bucket:    gao chua toi da N token, do lai N/60 token moi giay
 *                    -&gt; toc do trung binh bi chan o moi thoi diem
 * </pre>
 *
 * <p><b>Vi sao dong bo bang {@code synchronized} chu khong phai CAS.</b> Ban
 * dau lop nay goi ca trang thai vao mot {@code AtomicLong} de tranh khoa. Cach
 * do <b>sai</b>: no dua moc thoi gian tu {@link System#nanoTime()} vao mot
 * truong bit, ma {@code nanoTime()} co goc TUY Y theo dac ta — gia tri co the
 * am, va phep dich bit khi do cho ket qua vo nghia. Doi lai duoc gi? O muc
 * 120 request/phut, tranh chap tren mot gao gan nhu bang khong, nen khoa khong
 * ton gi ca. Mot toi uu khong do duoc khong dang doi lay mot loi kho thay.
 *
 * <p><b>Gioi han da biet cua cach lam nay.</b>
 * <ul>
 *   <li><b>Theo tung tien trinh.</b> Chay hai ban sao thi han muc thuc te nhan
 *       doi. Dung chung han muc doi hoi mot kho dem ngoai (Redis) — dung sau,
 *       khi that su co nhieu ban sao.</li>
 *   <li><b>Dinh danh bang dia chi IP</b>, ma dia chi IP gia mao duoc va nhieu
 *       nguoi dung co the dung chung mot NAT. Day la phep chan tho, khong phai
 *       phep chong lam dung tinh vi.</li>
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Tran so dia chi theo doi cung luc.
     *
     * <p>Khong co tran thi chinh bo gioi han tan suat tro thanh mot lo ro ri bo
     * nho: moi dia chi gia mao them mot muc vao bang, va bang khong bao gio
     * duoc don. Cham tran thi bang bi xoa sach — tho, nhung chan tren bo nho la
     * dieu bat buoc, con do chinh xac cua han muc thi khong.
     */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final int capacity;
    private final boolean enabled;
    private final boolean trustProxy;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int requestsPerMinute, boolean enabled) {
        this(requestsPerMinute, enabled, false);
    }

    public RateLimitFilter(int requestsPerMinute, boolean enabled, boolean trustProxy) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "requestsPerMinute phai > 0, nhan duoc: " + requestsPerMinute);
        }
        this.capacity = requestsPerMinute;
        this.enabled = enabled;
        this.trustProxy = trustProxy;
    }

    /** Mot gao token cho mot dia chi. Moi thao tac O(1). */
    static final class Bucket {

        private final double capacity;
        private final double tokensPerMilli;
        private double tokens;
        private long lastRefillMillis;

        Bucket(int requestsPerMinute, long nowMillis) {
            this.capacity = requestsPerMinute;
            this.tokensPerMilli = requestsPerMinute / 60_000.0;
            this.tokens = requestsPerMinute;
            this.lastRefillMillis = nowMillis;
        }

        /**
         * Lay mot token, hoac tu choi neu gao can.
         *
         * <p>Nhan {@code nowMillis} tu ben ngoai thay vi tu goi
         * {@code System.currentTimeMillis()}: nho vay kiem thu dieu khien duoc
         * thoi gian va khong phai {@code Thread.sleep} — mot bai kiem thu ngu
         * that la mot bai kiem thu cham va chap chon.
         */
        synchronized boolean tryConsume(long nowMillis) {
            long elapsed = Math.max(0, nowMillis - lastRefillMillis);
            lastRefillMillis = nowMillis;
            tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);

            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear(); // xem Javadoc MAX_TRACKED_CLIENTS
        }

        long now = System.currentTimeMillis();
        Bucket bucket = buckets.computeIfAbsent(clientIp(request), k -> new Bucket(capacity, now));

        if (!bucket.tryConsume(now)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"Vuot qua gioi han " + capacity + " request/phut\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Dia chi cua ben goi.
     *
     * <p>{@code X-Forwarded-For} do CLIENT gui len duoc, nen tin no mot cach vo
     * dieu kien se khien bo gioi han bi vo hieu hoan toan: chi can doi header
     * moi request la moi request thanh mot "dia chi" moi voi gao day. Chi doc
     * header nay khi da khai bao ro rang la co proxy tin cay dat truoc ung dung
     * ({@code app.security.trust-proxy=true}).
     */
    private String clientIp(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
