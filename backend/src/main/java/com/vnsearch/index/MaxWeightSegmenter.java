package com.vnsearch.index;

import com.vnsearch.datastructure.SyllableTrie;

/**
 * Tach tu tieng Viet bang <b>quy hoach dong cuc dai trong so</b> tren toan cau.
 *
 * <h2>Vi sao phai bo Longest Matching</h2>
 *
 * <p>Cach cu chay tham lam: dung tai am tiet {@code i}, thu ghep 4 am tiet, roi
 * 3, roi 2, <b>lay ngay cai dai nhat co trong tu dien</b> va nhay qua. Quyet dinh
 * do la <b>khong the rut lai</b> — no da tieu mat nhung am tiet ma mot cach tach
 * tot hon o phia sau can den. Vi du kinh dien cua tieng Viet:
 *
 * <pre>
 *   "nha hang xom"                (nha hang xom)
 *
 *   Longest Matching:  tai i=0 thay "nha hang" CO trong tu dien -> lay ngay
 *                      -> [nha_hang] [xom]        = "quan an" + "xom"   SAI
 *
 *   Quy hoach dong:    so sanh CA HAI cach tren toan cau
 *                      [nha_hang][xom] = 9,59 + 3,46 = 13,05
 *                      [nha][hang_xom] = 3,69 + 9,44 = 13,13   &lt;- lon hon
 *                      -> [nha] [hang_xom]        = "nha cua nguoi hang xom"  DUNG
 * </pre>
 *
 * <p>Diem mau chot: ca hai cach tach deu <b>hop le ve tu dien</b>. Tham lam khong
 * co cach nao phan biet chung nen phai doan bang mot heuristic ("dai hon thi
 * dung hon") va o day heuristic do sai. Quy hoach dong khong doan — no cham diem
 * <b>ca cau</b> roi chon cau tot nhat, nen tan suat cua {@code hang xom} co co hoi
 * thang tan suat cua {@code nha hang}.
 *
 * <h2>Cong thuc</h2>
 *
 * <p>Goi {@code best[i]} la tong trong so lon nhat cua mot cach tach {@code i} am
 * tiet dau tien. Chuyen trang thai: tu vi tri {@code i}, moi tu trong tu dien bat
 * dau tai {@code i} va dai {@code L} am tiet cho mot canh toi {@code i + L}:
 *
 * <pre>
 *   best[0] = 0
 *   best[j] = max( best[i] + weight(amTiet[i..j)) )  voi moi i sao cho j - i &le; 4
 * </pre>
 *
 * <p>Dap an nam o {@code best[n]}; cach tach cu the truy nguoc bang mang
 * {@code trace}. Day la bai toan <b>duong di dai nhat tren do thi khong chu trinh
 * co huong</b> — cac dinh 0..n da san o thu tu topo nen chi can mot luot quet
 * tien, khong can sap xep topo.
 *
 * <h2>Do phuc tap</h2>
 *
 * <p><b>O(n &times; MAX_SYLLABLES) = O(n)</b> vi {@code MAX_SYLLABLES} la hang so 4,
 * dung bang do phuc tap cua Longest Matching cu. Cai thien nam o <b>hang so</b>:
 * moi buoc cu tao mot mang tam ({@code Arrays.copyOfRange}) va mot chuoi moi
 * ({@code String.join}) truoc khi tra {@code HashSet}; buoc moi chi la mot phep
 * tra bang bam tren {@code long} trong {@link SyllableTrie}, khong cap phat gi.
 * Bo nho: O(n) cho hai mang {@code best} va {@code trace}.
 *
 * <h2>An toan luong</h2>
 *
 * <p>Lop nay <b>khong co trang thai thay doi duoc</b>. Hai mang lam viec duoc cap
 * phat trong long {@link #segment} nen moi loi goi doc lap hoan toan. Dieu nay la
 * bat buoc chu khong phai tuy chon: tokenizer duoc dung chung boi tang chi muc va
 * tang truy van, ma tang truy van chay tren nhieu luong cua Spring Boot. Neu dung
 * mang dung chung lam bo dem tai su dung thi hai truy van dong thoi se ghi de ket
 * qua cua nhau — mot loi <b>im lang</b>, chi hien ra duoi tai cao.
 *
 * @see VietnameseWordDictionary
 */
public class MaxWeightSegmenter {

    private final SyllableTrie trie;
    private final double unknownSyllableWeight;

    public MaxWeightSegmenter(VietnameseWordDictionary dictionary) {
        this(dictionary.trie(), VietnameseWordDictionary.UNKNOWN_SYLLABLE_WEIGHT);
    }

    public MaxWeightSegmenter(SyllableTrie trie, double unknownSyllableWeight) {
        this.trie = trie;
        this.unknownSyllableWeight = unknownSyllableWeight;
    }

    /**
     * Tim cach tach toi uu cho mot day am tiet.
     *
     * @param syllables day am tiet da chuan hoa (NFC, chu thuong, khong dau cau)
     * @return mang moc gioi han: token thu {@code k} gom cac am tiet
     *         {@code [ket_qua[k], ket_qua[k + 1])}. Luon co
     *         {@code ket_qua[0] == 0} va phan tu cuoi bang {@code syllables.length}.
     *         Day rong tra ve {@code {0}}.
     */
    public int[] segment(String[] syllables) {
        int n = syllables.length;
        if (n == 0) {
            return new int[]{0};
        }

        double[] best = new double[n + 1];
        int[] trace = new int[n + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0.0;
        trace[0] = -1;

        for (int i = 0; i < n; i++) {
            if (best[i] == Double.NEGATIVE_INFINITY) {
                continue;
            }

            // Luon cho phep tach mot am tiet, ke ca khi no khong co trong tu dien.
            // Neu bo buoc nay thi mot ten rieng hay tu muon nam giua cau se lam
            // DUT do thi va best[n] khong bao gio den duoc.
            relax(best, trace, i + 1, best[i] + unknownSyllableWeight, i);

            // Di doc trie MOT luot: mot lan di phu ca bon do dai 1..4, thay vi
            // bon lan dung lai chuoi ung vien doc lap nhu cach cu.
            int node = trie.root();
            int maxEnd = Math.min(n, i + VietnameseWordDictionary.MAX_SYLLABLES);
            for (int j = i; j < maxEnd; j++) {
                node = trie.child(node, trie.idOf(syllables[j]));
                if (node == SyllableTrie.NONE) {
                    // Khong tu nao trong tu dien co tien to nay — cac do dai con
                    // lai deu vo vong, cat nhanh luon. HashSet khong noi duoc dieu
                    // nay: no phai thu tung do dai mot.
                    break;
                }
                if (trie.isWord(node)) {
                    relax(best, trace, j + 1, best[i] + trie.weightAt(node), i);
                }
            }
        }

        return traceBack(trace, n);
    }

    /** Cap nhat {@code best[to]} neu duong di moi tot hon. */
    private static void relax(double[] best, int[] trace, int to, double score, int from) {
        if (score > best[to]) {
            best[to] = score;
            trace[to] = from;
        }
    }

    /** Truy nguoc {@code trace} thanh mang moc gioi han theo thu tu tang dan. */
    private static int[] traceBack(int[] trace, int n) {
        int count = 0;
        for (int i = n; i > 0; i = trace[i]) {
            count++;
        }
        int[] boundaries = new int[count + 1];
        boundaries[count] = n;
        int k = count;
        for (int i = n; i > 0; i = trace[i]) {
            boundaries[--k] = trace[i];
        }
        return boundaries;
    }
}
