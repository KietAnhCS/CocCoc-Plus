package com.vnsearch.eval;

import java.util.Random;

/**
 * Kiểm định ý nghĩa thống kê cho <b>hai cấu hình xếp hạng chạy trên cùng một
 * tập truy vấn</b>.
 *
 * <p><b>Vấn đề nó giải.</b> Bảng ablation nói cấu hình nào có MRR cao hơn. Nó
 * không trả lời được câu hỏi quan trọng hơn: <i>nếu hai cấu hình thực sự ngang
 * nhau, xác suất quan sát được chênh lệch lớn bằng hoặc hơn thế này — chỉ do
 * ngẫu nhiên của việc chọn đúng 200 truy vấn đó — là bao nhiêu?</i> Không có
 * con số đó, mọi câu "A tốt hơn B" đều là khẳng định chưa được chứng minh.
 *
 * <p><b>Vì sao kiểm định THEO CẶP.</b> Cả hai cấu hình chạy trên cùng tập truy
 * vấn, nên ta xét hiệu từng cặp {@code d_i = RR_A(q_i) − RR_B(q_i)} thay vì so
 * sánh hai trung bình độc lập. Cách này khử được nguồn biến thiên lớn nhất và
 * hoàn toàn không liên quan tới thứ cần đo: <i>truy vấn này vốn dễ, truy vấn
 * kia vốn khó</i>. Với dữ liệu truy hồi thông tin, phương sai giữa các truy vấn
 * lớn hơn hẳn phương sai giữa các hệ thống, nên bỏ qua cấu trúc cặp là vứt đi
 * phần lớn năng lực phát hiện.
 *
 * <p><b>Hai kiểm định, hai giả định khác nhau.</b>
 * <ol>
 *   <li><b>Paired t-test</b> giả định hiệu phân phối xấp xỉ chuẩn. Với
 *       reciprocal rank — rất lệch, dồn ở 1,0 và ở 0 — đó là giả định đáng hoài
 *       nghi.</li>
 *   <li><b>Randomization test</b> (hoán vị dấu) <b>không giả định gì</b> về
 *       phân phối. Nó là kiểm định được khuyến dùng trong ngành truy hồi thông
 *       tin (Smucker, Allan &amp; Carterette, CIKM 2007).</li>
 * </ol>
 * Báo cáo cả hai: khi chúng đồng ý, kết luận vững; khi khác nhau, đó là phát
 * hiện đáng nói chứ không phải thứ để giấu — {@link Result#testsDisagree()} tồn
 * tại chính vì lý do đó.
 *
 * <p><b>Tự cài, không gọi thư viện thống kê.</b> p-value của t-test cần hàm
 * phân phối Student, tức <b>hàm beta không hoàn chỉnh chính quy hoá</b>
 * {@code I_x(a,b)}, được cài ở đây bằng phân số liên tục Lentz. Khoảng tin cậy
 * cần phân vị t, được lấy bằng cách <b>nghịch đảo số học</b> chính hàm phân
 * phối đó (chia đôi) thay vì tra bảng — nhờ vậy đúng với mọi bậc tự do chứ
 * không chỉ vài giá trị có trong bảng.
 *
 * <p>Độ phức tạp: t-test {@code O(n)}; randomization test
 * {@code O(B·n)} với {@code B = }{@value #PERMUTATIONS} lần hoán vị.
 */
public final class SignificanceTest {

    /** Mức ý nghĩa quy ước. */
    public static final double ALPHA = 0.05;

    /**
     * Số lần hoán vị của randomization test.
     *
     * <p>Quyết định luôn <b>sàn phân giải</b> của p-value: giá trị nhỏ nhất báo
     * được là {@code 1/(B+1)}. Vì vậy 100.000 lần cho sàn {@code ~1e-5}, đủ để
     * khẳng định "&lt; 0,0001" một cách trung thực.
     */
    public static final int PERMUTATIONS = 100_000;

    /** Seed cố định — mọi con số trong báo cáo phải tái lập được y hệt. */
    private static final long SEED = 42L;

    private SignificanceTest() {
    }

    /**
     * Kết quả kiểm định.
     *
     * @param meanDifference       trung bình hiệu theo từng truy vấn (ΔMRR)
     * @param confidenceLow        cận dưới khoảng tin cậy 95 % của ΔMRR
     * @param confidenceHigh       cận trên khoảng tin cậy 95 % của ΔMRR
     * @param pValueTTest          p-value hai phía của paired t-test
     * @param pValueRandomization  p-value hai phía của randomization test
     * @param sampleSize           số cặp quan sát
     */
    public record Result(double meanDifference, double confidenceLow, double confidenceHigh,
                          double pValueTTest, double pValueRandomization, int sampleSize) {

        /**
         * Có ý nghĩa thống kê khi <b>cả hai</b> kiểm định cùng kết luận vậy.
         *
         * <p>Yêu cầu cả hai cùng đồng ý là cố ý khắt khe: một kết luận chỉ dựa
         * vào t-test sẽ phụ thuộc vào giả định chuẩn mà dữ liệu không thoả.
         */
        public boolean isSignificant() {
            return pValueTTest < ALPHA && pValueRandomization < ALPHA;
        }

        /**
         * Hai kiểm định cho kết luận trái nhau ở mức {@value #ALPHA}.
         *
         * <p>Khi điều này xảy ra, con số đáng tin hơn là randomization test (nó
         * không giả định phân phối), nhưng việc <b>báo ra sự bất đồng</b> quan
         * trọng hơn việc lặng lẽ chọn một trong hai.
         */
        public boolean testsDisagree() {
            return (pValueTTest < ALPHA) != (pValueRandomization < ALPHA);
        }
    }

    /**
     * Kiểm định theo cặp giữa hai dãy điểm đo trên <b>cùng</b> tập truy vấn.
     *
     * @param a điểm của cấu hình A, phần tử {@code i} ứng với truy vấn {@code i}
     * @param b điểm của cấu hình B, cùng thứ tự truy vấn
     * @throws IllegalArgumentException nếu hai dãy khác độ dài — đó là dấu hiệu
     *         hai cấu hình đã chạy trên hai tập truy vấn khác nhau, và khi đó
     *         kiểm định theo cặp là vô nghĩa chứ không chỉ kém chính xác
     */
    public static Result pairedTest(double[] a, double[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Hai day diem khong duoc null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Kiem dinh theo cap doi hoi hai day CUNG do dai (cung tap truy van), nhan duoc "
                            + a.length + " va " + b.length);
        }

        int n = a.length;
        double[] differences = new double[n];
        for (int i = 0; i < n; i++) {
            differences[i] = a[i] - b[i];
        }

        double mean = mean(differences);

        // Dưới 2 quan sát thì không có khái niệm phương sai mẫu — trả về kết quả
        // trung tính thay vì NaN, để tầng gọi không phải xử lý riêng.
        if (n < 2) {
            return new Result(mean, mean, mean, 1.0, 1.0, n);
        }

        double standardError = standardError(differences, mean);

        // Mọi hiệu bằng hệt nhau -> sai số chuẩn bằng 0. Không phải lỗi: hoặc
        // hai cấu hình cho kết quả y hệt (hiệu = 0, không có bằng chứng khác
        // biệt), hoặc chúng lệch nhau một hằng số ở MỌI truy vấn (bằng chứng
        // tuyệt đối). Chia cho 0 sẽ cho NaN và làm hỏng cả bảng báo cáo.
        if (standardError == 0.0) {
            double p = mean == 0.0 ? 1.0 : 0.0;
            return new Result(mean, mean, mean, p, p, n);
        }

        int degreesOfFreedom = n - 1;
        double t = mean / standardError;
        double pTTest = studentTTwoTailed(t, degreesOfFreedom);

        double margin = studentTQuantile975(degreesOfFreedom) * standardError;
        double pRandomization = randomizationTwoTailed(differences, mean);

        return new Result(mean, mean - margin, mean + margin, pTTest, pRandomization, n);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return values.length == 0 ? 0.0 : sum / values.length;
    }

    /** Sai số chuẩn của trung bình: {@code s / sqrt(n)} với {@code s} là độ lệch chuẩn MẪU (chia n−1). */
    private static double standardError(double[] values, double mean) {
        double sumSquares = 0.0;
        for (double value : values) {
            double deviation = value - mean;
            sumSquares += deviation * deviation;
        }
        double variance = sumSquares / (values.length - 1); // n−1: hiệu chỉnh Bessel
        return Math.sqrt(variance / values.length);
    }

    /**
     * <b>Randomization test</b> theo cặp, hai phía.
     *
     * <p><b>Giả thuyết không</b> là "hai cấu hình không khác nhau". Nếu đúng vậy
     * thì với mỗi truy vấn, việc hiệu {@code d_i} mang dấu dương hay âm là hoàn
     * toàn ngẫu nhiên — nhãn "A" và "B" có thể tráo cho nhau mà không đổi gì.
     * Nên ta <b>lật dấu ngẫu nhiên</b> từng hiệu {@value #PERMUTATIONS} lần và
     * đếm xem bao nhiêu lần trung bình mô phỏng có độ lớn không thua trung bình
     * thật.
     *
     * <p>Công thức {@code (đếm + 1) / (B + 1)} chứ không phải {@code đếm / B}:
     * cộng 1 vào cả tử và mẫu tính luôn <b>chính mẫu quan sát được</b> vào phân
     * phối null. Bỏ nó đi sẽ cho p-value bằng 0 — một khẳng định mạnh hơn hẳn
     * điều {@value #PERMUTATIONS} lần lấy mẫu có thể chứng minh.
     */
    private static double randomizationTwoTailed(double[] differences, double observedMean) {
        double observed = Math.abs(observedMean);
        Random random = new Random(SEED); // seed cố định -> tái lập được
        int n = differences.length;
        int atLeastAsExtreme = 0;

        for (int permutation = 0; permutation < PERMUTATIONS; permutation++) {
            double sum = 0.0;
            // Một số ngẫu nhiên 64 bit cấp 64 lần lật dấu, thay vì gọi bộ sinh
            // cho từng phần tử: vòng lặp này chạy B·n = 20 triệu lần.
            long bits = 0;
            int remaining = 0;
            for (int i = 0; i < n; i++) {
                if (remaining == 0) {
                    bits = random.nextLong();
                    remaining = 64;
                }
                sum += ((bits & 1L) == 0L) ? differences[i] : -differences[i];
                bits >>>= 1;
                remaining--;
            }
            if (Math.abs(sum / n) >= observed) {
                atLeastAsExtreme++;
            }
        }
        return (atLeastAsExtreme + 1.0) / (PERMUTATIONS + 1.0);
    }

    /**
     * p-value <b>hai phía</b> của phân phối Student với {@code df} bậc tự do.
     *
     * <p>Dùng đồng nhất thức nối phân phối t với hàm beta không hoàn chỉnh:
     * <pre>
     *   P(|T| &gt;= |t|) = I_x(df/2, 1/2),   x = df / (df + t²)
     * </pre>
     * Kiểm chứng được bằng dạng đóng ở hai bậc tự do nhỏ: {@code df = 1} cho
     * phân phối Cauchy {@code 1 − (2/π)·arctan|t|}, {@code df = 2} cho
     * {@code 1 − |t|/sqrt(2 + t²)}.
     */
    public static double studentTTwoTailed(double t, int degreesOfFreedom) {
        if (degreesOfFreedom <= 0) {
            return 1.0;
        }
        if (Double.isInfinite(t)) {
            return 0.0;
        }
        double x = degreesOfFreedom / (degreesOfFreedom + t * t);
        return regularizedIncompleteBeta(degreesOfFreedom / 2.0, 0.5, x);
    }

    /**
     * Phân vị 97,5 % của phân phối Student — nửa độ rộng khoảng tin cậy 95 %.
     *
     * <p>Không tra bảng mà <b>nghịch đảo số học</b> chính hàm phân phối ở trên
     * bằng chia đôi: {@link #studentTTwoTailed} đơn điệu giảm theo {@code t}, nên
     * chia đôi hội tụ chắc chắn. Nhờ vậy đúng với MỌI bậc tự do, kể cả những giá
     * trị không có trong bảng in sẵn.
     */
    public static double studentTQuantile975(int degreesOfFreedom) {
        if (degreesOfFreedom <= 0) {
            return 0.0;
        }
        double low = 0.0;
        double high = 1000.0; // đủ rộng: t(0,975; df=1) = 12,71 là giá trị lớn nhất
        for (int iteration = 0; iteration < 200; iteration++) {
            double mid = 0.5 * (low + high);
            if (studentTTwoTailed(mid, degreesOfFreedom) > ALPHA) {
                low = mid; // p còn lớn -> t còn nhỏ
            } else {
                high = mid;
            }
        }
        return 0.5 * (low + high);
    }

    /**
     * Hàm beta không hoàn chỉnh chính quy hoá {@code I_x(a, b)}.
     *
     * <p>Cài theo Numerical Recipes: dùng phân số liên tục, và <b>đổi vế</b> khi
     * {@code x} nằm ở phía hội tụ chậm nhờ đồng nhất thức
     * {@code I_x(a,b) = 1 − I_{1−x}(b,a)}. Không đổi vế thì phân số liên tục cần
     * số vòng lặp tăng vọt ở đuôi phân phối — đúng chỗ ta quan tâm nhất.
     */
    static double regularizedIncompleteBeta(double a, double b, double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(a, b, x) / a;
        }
        return 1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b;
    }

    /** Phân số liên tục của hàm beta không hoàn chỉnh, khai triển theo thuật toán Lentz cải biên. */
    private static double betaContinuedFraction(double a, double b, double x) {
        final double tiny = 1e-30;
        final double epsilon = 3e-16;
        final int maxIterations = 300;

        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double result = d;

        for (int m = 1; m <= maxIterations; m++) {
            int m2 = 2 * m;

            // Bước chẵn
            double numerator = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + numerator * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + numerator / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            result *= d * c;

            // Bước lẻ
            numerator = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + numerator * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + numerator / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            result *= delta;

            if (Math.abs(delta - 1.0) < epsilon) {
                break;
            }
        }
        return result;
    }

    /**
     * {@code ln Γ(z)} theo xấp xỉ Lanczos (g = 7, 9 hệ số).
     *
     * <p>Trả về <b>logarit</b> chứ không phải chính {@code Γ(z)}: hàm gamma tràn
     * {@code double} ngay từ {@code z ≈ 171}, trong khi ở đây nó chỉ xuất hiện
     * dưới dạng hiệu của các logarit nên tính thẳng trong miền log vừa an toàn
     * vừa chính xác hơn.
     */
    static double logGamma(double z) {
        final double[] coefficients = {
                0.99999999999980993, 676.5203681218851, -1259.1392167224028,
                771.32342877765313, -176.61502916214059, 12.507343278686905,
                -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

        if (z < 0.5) {
            // Công thức phản xạ Euler: Γ(z)·Γ(1−z) = π / sin(πz)
            return Math.log(Math.PI / Math.sin(Math.PI * z)) - logGamma(1.0 - z);
        }
        double x = z - 1.0;
        double series = coefficients[0];
        for (int i = 1; i < coefficients.length; i++) {
            series += coefficients[i] / (x + i);
        }
        double t = x + 7.5;
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(series);
    }
}
