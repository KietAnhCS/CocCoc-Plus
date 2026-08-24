package com.vnsearch.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử kiểm định ý nghĩa thống kê.
 *
 * <p><b>Nguyên tắc của bộ test này:</b> mọi giá trị kỳ vọng đều lấy từ
 * <b>dạng đóng giải tích</b>, không lấy từ một thư viện thống kê khác. Đối
 * chiếu với thư viện khác chỉ chứng minh hai cài đặt giống nhau — nếu cả hai
 * cùng sai theo một kiểu thì test vẫn xanh. Phân phối Student có dạng đóng ở
 * bậc tự do nhỏ, nên ở đó kiểm chứng được tuyệt đối:
 * <pre>
 *   df = 1 (Cauchy) : P(|T| >= t) = 1 − (2/π)·arctan(t)
 *   df = 2          : P(|T| >= t) = 1 − t / sqrt(2 + t²)
 * </pre>
 */
class SignificanceTestTest {

    private static final double TOLERANCE = 1e-9;

    // ---------------------------------------------------------------
    // Phân phối Student — đối chiếu với dạng đóng
    // ---------------------------------------------------------------

    @Test
    @DisplayName("df = 1 khớp dạng đóng Cauchy tại nhiều giá trị t")
    void df1MatchesCauchy() {
        for (double t : new double[]{0.1, 0.5, 1.0, 2.0, 5.0, 12.7062, 50.0}) {
            double expected = 1 - (2 / Math.PI) * Math.atan(t);
            assertEquals(expected, SignificanceTest.studentTTwoTailed(t, 1), 1e-12,
                    "sai tại t = " + t);
        }
    }

    @Test
    @DisplayName("df = 2 khớp dạng đóng 1 − t/sqrt(2+t²)")
    void df2MatchesClosedForm() {
        for (double t : new double[]{0.1, 0.5, 1.0, 2.0, 4.30265, 20.0}) {
            double expected = 1 - t / Math.sqrt(2 + t * t);
            assertEquals(expected, SignificanceTest.studentTTwoTailed(t, 2), 1e-12,
                    "sai tại t = " + t);
        }
    }

    @Test
    @DisplayName("t = 0 cho p = 1 với mọi bậc tự do — không có bằng chứng nào")
    void zeroTGivesPOne() {
        for (int df : new int[]{1, 2, 5, 30, 199, 1000}) {
            assertEquals(1.0, SignificanceTest.studentTTwoTailed(0.0, df), TOLERANCE);
        }
    }

    @Test
    @DisplayName("p hai phía đối xứng theo dấu của t")
    void symmetricInT() {
        for (int df : new int[]{1, 3, 17, 199}) {
            assertEquals(SignificanceTest.studentTTwoTailed(2.5, df),
                    SignificanceTest.studentTTwoTailed(-2.5, df), TOLERANCE);
        }
    }

    @Test
    @DisplayName("p giảm đơn điệu khi |t| tăng")
    void monotoneDecreasingInT() {
        double previous = 1.0;
        for (double t = 0.0; t <= 6.0; t += 0.25) {
            double p = SignificanceTest.studentTTwoTailed(t, 10);
            assertTrue(p <= previous + TOLERANCE, "p phải không tăng, gãy tại t = " + t);
            previous = p;
        }
    }

    @Test
    @DisplayName("Bậc tự do càng lớn, đuôi càng mỏng — cùng một t cho p càng nhỏ")
    void heavierTailsAtLowDf() {
        assertTrue(SignificanceTest.studentTTwoTailed(2.0, 1)
                > SignificanceTest.studentTTwoTailed(2.0, 10));
        assertTrue(SignificanceTest.studentTTwoTailed(2.0, 10)
                > SignificanceTest.studentTTwoTailed(2.0, 1000));
    }

    // ---------------------------------------------------------------
    // Phân vị dùng cho khoảng tin cậy
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Phân vị 97,5 % khớp giá trị bảng in sẵn")
    void quantileMatchesPublishedTable() {
        assertEquals(12.70620, SignificanceTest.studentTQuantile975(1), 1e-5);
        assertEquals(4.30265, SignificanceTest.studentTQuantile975(2), 1e-5);
        assertEquals(2.57058, SignificanceTest.studentTQuantile975(5), 1e-5);
        assertEquals(2.22814, SignificanceTest.studentTQuantile975(10), 1e-5);
        assertEquals(2.04227, SignificanceTest.studentTQuantile975(30), 1e-5);
        assertEquals(1.97993, SignificanceTest.studentTQuantile975(120), 1e-5);
    }

    @Test
    @DisplayName("Phân vị là nghịch đảo thật sự của hàm phân phối")
    void quantileInvertsDistribution() {
        for (int df : new int[]{1, 2, 7, 42, 199}) {
            double t = SignificanceTest.studentTQuantile975(df);
            assertEquals(SignificanceTest.ALPHA, SignificanceTest.studentTTwoTailed(t, df), 1e-9,
                    "sai tại df = " + df);
        }
    }

    @Test
    @DisplayName("Bậc tự do càng lớn, phân vị càng tiến về 1,96 của phân phối chuẩn")
    void quantileApproachesNormal() {
        assertTrue(SignificanceTest.studentTQuantile975(1000) < SignificanceTest.studentTQuantile975(10));
        assertEquals(1.96, SignificanceTest.studentTQuantile975(100_000), 1e-3);
    }

    // ---------------------------------------------------------------
    // Hàm beta không hoàn chỉnh và logGamma
    // ---------------------------------------------------------------

    @Test
    @DisplayName("I_x(1,1) = x — trường hợp phân phối đều")
    void incompleteBetaUniformCase() {
        for (double x : new double[]{0.0, 0.1, 0.25, 0.5, 0.75, 0.99, 1.0}) {
            assertEquals(x, SignificanceTest.regularizedIncompleteBeta(1, 1, x), 1e-12);
        }
    }

    @Test
    @DisplayName("I_x(a,b) = 1 − I_{1−x}(b,a) — đồng nhất thức đối xứng")
    void incompleteBetaSymmetry() {
        assertEquals(1 - SignificanceTest.regularizedIncompleteBeta(2.5, 0.5, 0.7),
                SignificanceTest.regularizedIncompleteBeta(0.5, 2.5, 0.3), 1e-12);
    }

    @Test
    @DisplayName("logGamma khớp giai thừa: Γ(n) = (n−1)!")
    void logGammaMatchesFactorial() {
        assertEquals(0.0, SignificanceTest.logGamma(1), 1e-10);          // 0! = 1
        assertEquals(0.0, SignificanceTest.logGamma(2), 1e-10);          // 1! = 1
        assertEquals(Math.log(24), SignificanceTest.logGamma(5), 1e-10); // 4! = 24
        assertEquals(Math.log(3_628_800), SignificanceTest.logGamma(11), 1e-9); // 10!
        assertEquals(0.5 * Math.log(Math.PI), SignificanceTest.logGamma(0.5), 1e-10); // Γ(½)=√π
    }

    // ---------------------------------------------------------------
    // Kiểm định theo cặp
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Hai dãy y hệt nhau: ΔMRR = 0, cả hai kiểm định đều không có ý nghĩa")
    void identicalSeriesShowNoEffect() {
        double[] scores = {1.0, 0.5, 0.25, 1.0, 0.0};

        SignificanceTest.Result result = SignificanceTest.pairedTest(scores, scores);

        assertEquals(0.0, result.meanDifference(), TOLERANCE);
        assertEquals(1.0, result.pValueTTest(), TOLERANCE);
        assertEquals(1.0, result.pValueRandomization(), TOLERANCE);
        assertTrue(!result.isSignificant() && !result.testsDisagree());
    }

    @Test
    @DisplayName("A tốt hơn B ở MỌI truy vấn: cả hai kiểm định cùng kết luận có ý nghĩa")
    void consistentImprovementIsSignificant() {
        int n = 40;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = 0.5 + 0.01 * i + 0.10;
            b[i] = 0.5 + 0.01 * i;
        }

        SignificanceTest.Result result = SignificanceTest.pairedTest(a, b);

        assertEquals(0.10, result.meanDifference(), 1e-9);
        assertTrue(result.isSignificant());
        assertTrue(!result.testsDisagree());
        assertTrue(result.confidenceLow() > 0, "KTC không được chứa 0 khi hiệu là hằng số dương");
    }

    @Test
    @DisplayName("Chênh lệch nhỏ lẫn trong nhiễu lớn: chưa kết luận được")
    void noisyTinyDifferenceIsNotSignificant() {
        double[] a = {1.0, 0.0, 1.0, 0.0, 0.5, 1.0, 0.0, 0.2};
        double[] b = {0.0, 1.0, 1.0, 0.0, 0.5, 0.0, 1.0, 0.3};

        SignificanceTest.Result result = SignificanceTest.pairedTest(a, b);

        assertTrue(!result.isSignificant());
        assertTrue(result.confidenceLow() < 0 && result.confidenceHigh() > 0,
                "KTC phải chứa 0 khi chưa phân biệt được");
    }

    @Test
    @DisplayName("ΔMRR đúng bằng hiệu hai trung bình — nhưng chỉ cách theo cặp cho được sai số chuẩn")
    void meanDifferenceEqualsDifferenceOfMeans() {
        double[] a = {1.0, 0.5, 0.2};
        double[] b = {0.5, 0.5, 0.1};

        SignificanceTest.Result result = SignificanceTest.pairedTest(a, b);

        double meanA = (1.0 + 0.5 + 0.2) / 3;
        double meanB = (0.5 + 0.5 + 0.1) / 3;
        assertEquals(meanA - meanB, result.meanDifference(), 1e-12);
    }

    @Test
    @DisplayName("Khoảng tin cậy đối xứng quanh ΔMRR")
    void confidenceIntervalIsCentred() {
        double[] a = {1.0, 0.5, 0.33, 0.25, 1.0, 0.2};
        double[] b = {0.5, 0.33, 0.25, 0.2, 0.5, 0.1};

        SignificanceTest.Result result = SignificanceTest.pairedTest(a, b);

        assertEquals(result.meanDifference(),
                (result.confidenceLow() + result.confidenceHigh()) / 2, 1e-12);
    }

    @Test
    @DisplayName("p-value của randomization test không bao giờ bằng 0 — sàn là 1/(B+1)")
    void randomizationPValueHasFloor() {
        // Hiệu phải BIẾN THIÊN (hiệu hằng số đi vào nhánh sai-số-chuẩn-bằng-0),
        // nhưng luôn dương: khi đó mọi phép lật dấu đều làm |trung bình| giảm,
        // nên không hoán vị nào đạt mức quan sát được -> p rơi đúng xuống sàn.
        int n = 60;
        double[] a = new double[n];
        double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            a[i] = 1.0;
            b[i] = (i % 4) * 0.01;
        }

        SignificanceTest.Result result = SignificanceTest.pairedTest(a, b);

        double floor = 1.0 / (SignificanceTest.PERMUTATIONS + 1.0);
        assertEquals(floor, result.pValueRandomization(), 1e-12);
        assertTrue(result.pValueRandomization() > 0,
                "báo p = 0 là khẳng định mạnh hơn điều số lần hoán vị có thể chứng minh");
    }

    @Test
    @DisplayName("Chạy lại cho kết quả y hệt — seed cố định nên báo cáo tái lập được")
    void resultsAreReproducible() {
        double[] a = {1.0, 0.5, 0.33, 0.25, 0.0, 1.0, 0.2, 0.5};
        double[] b = {0.5, 0.5, 0.20, 0.25, 1.0, 0.0, 0.1, 0.4};

        assertEquals(SignificanceTest.pairedTest(a, b), SignificanceTest.pairedTest(a, b));
    }

    @Test
    @DisplayName("Đảo vai trò A và B thì ΔMRR đổi dấu, p-value giữ nguyên")
    void swappingArgumentsFlipsSignOnly() {
        double[] a = {1.0, 0.5, 0.33, 0.25, 0.6, 0.9};
        double[] b = {0.5, 0.5, 0.20, 0.25, 0.4, 0.3};

        SignificanceTest.Result forward = SignificanceTest.pairedTest(a, b);
        SignificanceTest.Result reversed = SignificanceTest.pairedTest(b, a);

        assertEquals(-forward.meanDifference(), reversed.meanDifference(), 1e-12);
        assertEquals(forward.pValueTTest(), reversed.pValueTTest(), 1e-12);
        assertEquals(forward.pValueRandomization(), reversed.pValueRandomization(), 1e-12);
    }

    @Test
    @DisplayName("Hai dãy khác độ dài bị từ chối — đó là hai tập truy vấn khác nhau")
    void mismatchedLengthsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SignificanceTest.pairedTest(new double[]{1.0, 0.5}, new double[]{1.0}));
    }

    @Test
    @DisplayName("Một quan sát duy nhất: không có phương sai mẫu, trả kết quả trung tính chứ không NaN")
    void singleObservationDegradesGracefully() {
        SignificanceTest.Result result =
                SignificanceTest.pairedTest(new double[]{1.0}, new double[]{0.0});

        assertEquals(1.0, result.meanDifference(), TOLERANCE);
        assertEquals(1.0, result.pValueTTest(), TOLERANCE);
        assertTrue(!result.isSignificant());
    }

    @Test
    @DisplayName("Hiệu là hằng số khác 0: sai số chuẩn bằng 0, không được cho ra NaN")
    void constantNonZeroDifferenceDoesNotProduceNaN() {
        SignificanceTest.Result result = SignificanceTest.pairedTest(
                new double[]{1.0, 1.0, 1.0}, new double[]{0.5, 0.5, 0.5});

        assertEquals(0.5, result.meanDifference(), TOLERANCE);
        assertNotEquals(Double.NaN, result.pValueTTest());
        assertEquals(0.0, result.pValueTTest(), TOLERANCE);
        assertTrue(result.isSignificant());
    }
}
