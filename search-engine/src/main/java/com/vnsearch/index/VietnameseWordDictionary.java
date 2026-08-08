package com.vnsearch.index;

import com.vnsearch.datastructure.SyllableTrie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Tu dien tu ghep tieng Viet co TRONG SO, nap vao {@link SyllableTrie}.
 *
 * <p><b>Vi sao tu dien phai co trong so.</b> Tu dien cu
 * ({@code vietnamese-bigrams.txt}, 154 muc) chi tra loi duoc <i>co / khong</i>.
 * Voi cau tra loi nhi phan do, khi hai cach tach deu hop le thi khong co co so
 * nao de chon — thuat toan buoc phai doan, va Longest Matching doan bang cach
 * "lay cai dai nhat", mot heuristic sai o nhung cau nhap nhang (xem
 * {@link MaxWeightSegmenter}). Khi moi tu mang mot trong so, viec chon cach tach
 * tro thanh mot bai toan <b>toi uu co ham muc tieu</b>, giai duoc chinh xac bang
 * quy hoach dong thay vi doan.
 *
 * <h2>Cong thuc trong so</h2>
 *
 * <p>Lay dung cong thuc dang chay trong production cua Coc Coc
 * ({@code tokenizer/auxiliary/trie/multiterm_hash_trie_node.hpp},
 * ham {@code finalize()}):
 * <pre>
 *   spaceCount = soAmTiet - 1
 *   freqPower  = PARAM[spaceCount * 2]
 *   lenPower   = PARAM[spaceCount * 2 + 1]
 *   weight     = pow(log2(freq + 3), freqPower) * pow(spaceCount + 1, lenPower)
 * </pre>
 *
 * <p>Hai dieu dang chu y trong cong thuc nay, vi ca hai deu <b>khong hien nhien</b>:
 * <ul>
 *   <li><b>{@code log2(freq)} chu khong phai {@code freq}.</b> Tan suat trai dai
 *       tu 10 den 2.147.483.647 — hon tam muoi lan. Dung thang tuyen tinh thi mot
 *       tu pho bien se ap dao moi to hop khac va quy hoach dong bien thanh "luon
 *       chon tu pho bien nhat", bat ke ngu canh. Lay logarit nen tam gia tri ve
 *       khoang 3..31, dung bang tam anh huong voi thanh phan do dai.</li>
 *   <li><b>{@code +3} truoc khi lay log.</b> Chan duoi. Tu co {@code freq = 1} se
 *       cho {@code log2(1) = 0}, tuc trong so 0 — dung bang gia tri danh cho "khong
 *       phai tu". Cong 3 dam bao moi tu that deu co trong so duong that su.</li>
 * </ul>
 *
 * <p><b>Bang tham so nay khong don dieu theo do dai</b> — {@code lenPower} lan
 * luot la 1 / 2,59 / 4,42 / 0,23 cho tu 1 / 2 / 3 / 4 am tiet, nghia la tu 3 am
 * tiet duoc uu ai hon han, con tu 4 am tiet lai bi ha xuong. Do la ket qua do
 * tham so tren du lieu noi bo cua Coc Coc chu khong phai mot nguyen ly ngon ngu
 * hoc, nen <b>dung coi la chan ly</b>: {@link #PARAM} duoc tach ra thanh tham so
 * cua constructor de {@code EvaluationRunner} chay duoc thi nghiem ablation tren
 * chinh no.
 *
 * <p><b>Gioi han 4 am tiet</b> khong phai lua chon tuy y ma la <b>chan tren cua
 * bang tham so</b>: {@code PARAM} co 9 phan tu, tu 5 am tiet se doc toi chi so 9
 * va tran mang. Coc Coc cung dung gioi han nay.
 *
 * @see MaxWeightSegmenter
 */
public class VietnameseWordDictionary {

    /** So am tiet toi da cua mot tu ghep. Xem Javadoc lop: chan tren cua {@link #PARAM}. */
    public static final int MAX_SYLLABLES = 4;

    /**
     * Bang tham so cua Coc Coc, nguyen van tu
     * {@code multiterm_hash_trie_node.hpp}: cac cap (freqPower, lenPower) theo
     * {@code spaceCount} = soAmTiet - 1.
     */
    // Package-private chu khong public: mot mang `public static final` van SUA
    // duoc tu ben ngoai (`PARAM[0] = 99`) — `final` chi khoa tham chieu, khong
    // khoa noi dung. Bang nay chi duoc doc trong chinh goi nay.
    static final double[] PARAM = {
            0.38, 1.00,   // 1 am tiet
            0.14, 2.59,   // 2 am tiet
            1.42, 4.42,   // 3 am tiet
            1.45, 0.23,   // 4 am tiet
            0.10          // chan tren cua bang — xem Javadoc lop
    };

    /**
     * Trong so cua am tiet KHONG co trong tu dien.
     *
     * <p>Gia tri 0,5 lay tu Coc Coc ({@code MultitermHashTrieNode} khoi tao
     * {@code weight = 0.5}). No phai <b>duong</b> — neu bang 0 thi quy hoach dong
     * se coi moi cach tach chua tu la nhau, ma van ban that luon co ten rieng va
     * tu muon khong nam trong tu dien nao. No cung phai <b>nho hon</b> trong so cua
     * am tiet co trong tu dien (thap nhat khoang 1,5), de khi da biet mot am tiet
     * thi dung no van hon la coi nhu khong biet.
     */
    public static final double UNKNOWN_SYLLABLE_WEIGHT = 0.5;

    /**
     * Tan suat gan cho cac muc trong {@code vietnamese-bigrams.txt}.
     *
     * <p>File do la tu dien thu cong theo mien cong nghe/tim kiem cua du an, khong
     * co so lieu tan suat. Van phai giu vi no chua nhung cum tu dac thu de tai ma
     * tu dien tong quat cua Coc Coc khong co ("cong cu tim kiem", "an toan thong
     * tin"...). Gia tri nay tuong duong mot cum tu kha pho bien, du de chung canh
     * tranh duoc voi tu dien lon nhung khong ap dao no.
     */
    public static final int CURATED_FREQUENCY = 10_000_000;

    private static final String WORDS_RESOURCE = "/vietnamese-words.txt";
    private static final String CURATED_RESOURCE = "/vietnamese-bigrams.txt";

    private final SyllableTrie trie;
    private final double[] param;
    private int wordCount;
    private int compoundCount;

    public VietnameseWordDictionary() {
        this(PARAM);
    }

    /**
     * @param param bang tham so (freqPower, lenPower) theo so am tiet — tach ra
     *              lam tham so de chay ablation, xem Javadoc lop
     */
    public VietnameseWordDictionary(double[] param) {
        this.param = param.clone();
        // Tu dien hien tai sinh ra khoang 50.000 canh (do bang TokenizerBenchmark).
        // Cap phat 1<<16 cho bang canh khoang 131.000 o — du cho, va neu tu dien lon
        // len thi bang tu nhan doi, chi ton vai lan bam lai luc khoi dong.
        // Ban dau cho la 1<<19: dung duoc nhung ton 14 MB cho 50.000 canh, gap bay lan
        // muc can thiet. Phep do bat duoc, uoc luong bang mat thi khong.
        this.trie = new SyllableTrie(1 << 16);
        load(WORDS_RESOURCE, true);
        load(CURATED_RESOURCE, false);
    }

    /**
     * Trong so cua mot tu theo tan suat va so am tiet.
     *
     * @param frequency tan suat tho trong tu dien
     * @param syllables so am tiet, 1..{@link #MAX_SYLLABLES}
     */
    public double weightOf(int frequency, int syllables) {
        int spaceCount = syllables - 1;
        double freqPower = param[spaceCount << 1];
        double lenPower = param[(spaceCount << 1) | 1];
        return Math.pow(log2(frequency + 3.0), freqPower)
                * Math.pow(spaceCount + 1, lenPower);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    /**
     * @param hasFrequency {@code true} cho file "<tu>\t<tan suat>";
     *                     {@code false} cho file chi co tu, dung
     *                     {@link #CURATED_FREQUENCY}
     */
    private void load(String resource, boolean hasFrequency) {
        try (InputStream is = VietnameseWordDictionary.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("Khong tim thay resource: " + resource);
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 1 << 16)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') {
                        continue;
                    }
                    String word;
                    int frequency;
                    if (hasFrequency) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) {
                            continue;
                        }
                        word = line.substring(0, tab);
                        frequency = parsePositiveInt(line, tab + 1);
                        if (frequency < 0) {
                            continue;
                        }
                    } else {
                        word = line.trim();
                        frequency = CURATED_FREQUENCY;
                    }
                    addWord(word, frequency);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Loi doc tu dien: " + resource, e);
        }
    }

    /**
     * Doc so nguyen duong tu {@code from} den het dong.
     *
     * <p>Khong dung {@link Integer#parseInt} tren {@code substring}: ham nay chay
     * 185.000 lan luc khoi dong, va moi lan {@code substring} cap phat mot chuoi
     * chi de doc ra mot so roi vut di. Quet truc tiep tren chuoi goc thi khong cap
     * phat gi. Tra ve -1 neu dong khong hop le.
     */
    private static int parsePositiveInt(String line, int from) {
        if (from >= line.length()) {
            return -1;
        }
        long value = 0;
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
            if (value > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) value;
    }

    private void addWord(String word, int frequency) {
        String normalized = normalize(word);
        String[] syllables = normalized.split(" ");
        if (syllables.length == 0 || syllables.length > MAX_SYLLABLES) {
            return;
        }
        for (String syllable : syllables) {
            if (syllable.isEmpty()) {
                return;
            }
        }
        trie.insert(syllables, weightOf(frequency, syllables.length));
        wordCount++;
        if (syllables.length > 1) {
            compoundCount++;
        }
    }

    /**
     * Chuan hoa mot chuoi tu dien ve dung dang ma tokenizer sinh ra luc tra cuu.
     *
     * <p>Neu tu dien luu dang NFD ma tokenizer tra bang NFC (hoac nguoc lai) thi
     * hai chuoi trong nhu nhau tren man hinh nhung khac nhau tung byte, va tu dien
     * <b>khong bao gio khop</b> — mot loi im lang, khong ngoai le, chi la ket qua
     * kem di mot cach kho hieu. Ca hai phia deu phai di qua dung mot ham nay.
     */
    static String normalize(String s) {
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFC)
                .toLowerCase(Locale.forLanguageTag("vi"));
    }

    /** Trie da nap, dung cho {@link MaxWeightSegmenter}. */
    public SyllableTrie trie() {
        return trie;
    }

    /** Tong so tu da nap (ke ca am tiet don). */
    public int wordCount() {
        return wordCount;
    }

    /** So tu ghep (tu 2 am tiet tro len). */
    public int compoundCount() {
        return compoundCount;
    }
}
