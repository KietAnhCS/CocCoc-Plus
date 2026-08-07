package com.vnsearch.eval;

import com.vnsearch.datastructure.SyllableTrie;
import com.vnsearch.index.MaxWeightSegmenter;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.index.VietnameseWordDictionary;
import com.vnsearch.model.WebDocument;
import com.vnsearch.crawler.ContentStorage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Do <b>truc tiep</b> hai thay doi cua bo tach tu, tren corpus that.
 *
 * <p>Bao cao nay tra loi hai cau hoi rieng biet, va chung can duoc tra loi rieng vi
 * chung co the doc lap dung/sai:
 * <ol>
 *   <li><b>Nhanh hon bao nhieu?</b> — Longest Matching cu tao ba mang tam va ba chuoi
 *       moi tai MOI am tiet chi de tra {@code HashSet}. Quy hoach dong tren
 *       {@link SyllableTrie} khong cap phat gi trong vong lap.</li>
 *   <li><b>Tach khac di bao nhieu, va khac o dau?</b> — con so nay moi quan trong.
 *       Nhanh hon ma ket qua y het thi khong dang doi thuat toan.</li>
 * </ol>
 *
 * <p><b>Ban doi chieu la gi.</b> Muon so sanh thi phai con giu duoc thuat toan cu.
 * {@link #segmentGreedy} o duoi la ban cai lai <b>nguyen van</b> Longest Matching
 * tham lam nhu {@code VietnameseTokenizer} truoc day, nhung chay tren <b>cung tu dien
 * moi</b>. Do la co y: neu de ban cu dung tu dien 154 muc thi phep do se tron lan hai
 * thay doi, va ta se khong biet phan cai thien den tu tu dien lon hay tu thuat toan.
 * Tach ra thi bang duoi day do <b>rieng phan thuat toan</b>.
 *
 * <pre>
 *   ./mvnw.cmd exec:java -Dexec.mainClass=com.vnsearch.eval.TokenizerBenchmark \
 *        -Dexec.args="data/crawled-documents.json 2000"
 * </pre>
 */
public class TokenizerBenchmark {

    public static void main(String[] args) throws IOException {
        String corpusPath = args.length > 0 ? args[0] : "data/crawled-documents.json";
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : 2000;

        System.out.println("Nap corpus: " + corpusPath);
        List<WebDocument> all = ContentStorage.loadFromJson(corpusPath);
        List<WebDocument> docs = all.subList(0, Math.min(limit, all.size()));
        System.out.printf("Dung %d / %d tai lieu%n%n", docs.size(), all.size());

        long dictStart = System.nanoTime();
        VietnameseWordDictionary dictionary = VietnameseTokenizer.sharedDictionary();
        long dictMs = (System.nanoTime() - dictStart) / 1_000_000;
        SyllableTrie trie = dictionary.trie();

        System.out.println("--- Tu dien ---");
        System.out.printf("  so tu           : %,d (%,d tu ghep)%n",
                dictionary.wordCount(), dictionary.compoundCount());
        System.out.printf("  am tiet phan biet: %,d%n", trie.syllableCount());
        System.out.printf("  nut trie / canh : %,d / %,d%n", trie.nodeCount(), trie.edgeCount());
        System.out.printf("  bo nho mang phang: %,d KB%n", trie.approximateBytes() / 1024);
        System.out.printf("  thoi gian nap   : %d ms (mot lan cho ca tien trinh)%n%n", dictMs);

        // Chuan hoa truoc mot lan cho ca hai ben, de phep do chi con lai phan GHEP TU
        // chu khong lan thoi gian chuan hoa Unicode va bo dau cau.
        List<String[]> corpus = new ArrayList<>(docs.size());
        long syllableTotal = 0;
        for (WebDocument doc : docs) {
            String[] syllables = splitIntoSyllables(text(doc));
            if (syllables.length > 0) {
                corpus.add(syllables);
                syllableTotal += syllables.length;
            }
        }
        System.out.printf("Tong so am tiet: %,d%n%n", syllableTotal);

        MaxWeightSegmenter dp = new MaxWeightSegmenter(dictionary);
        Set<String> greedyDictionary = flattenToStringSet(dictionary);

        // Ham nong can duoc JIT dich truoc, khong thi phep do chi do thoi gian
        // thong dich cua vai nghin luot goi dau tien.
        for (int warmup = 0; warmup < 3; warmup++) {
            for (String[] syllables : corpus) {
                dp.segment(syllables);
                segmentGreedy(syllables, greedyDictionary);
            }
        }

        long greedyNs = timeGreedy(corpus, greedyDictionary);
        long dpNs = timeDp(corpus, dp);

        System.out.println("--- Toc do ghep tu (da tru phan chuan hoa) ---");
        report("Longest Matching (HashSet)", greedyNs, syllableTotal);
        report("Quy hoach dong (SyllableTrie)", dpNs, syllableTotal);
        System.out.printf("  => nhanh gap %.2f lan%n%n", (double) greedyNs / dpNs);

        compareSegmentations(corpus, dp, greedyDictionary);
    }

    private static void report(String label, long ns, long syllables) {
        double ms = ns / 1_000_000.0;
        double perSecond = syllables / (ns / 1_000_000_000.0);
        System.out.printf("  %-30s %8.1f ms   %,15.0f am tiet/giay%n", label, ms, perSecond);
    }

    private static long timeDp(List<String[]> corpus, MaxWeightSegmenter dp) {
        long start = System.nanoTime();
        long sink = 0;
        for (String[] syllables : corpus) {
            sink += dp.segment(syllables).length;
        }
        long elapsed = System.nanoTime() - start;
        if (sink < 0) {
            throw new IllegalStateException(); // giu ket qua khoi bi JIT loai bo
        }
        return elapsed;
    }

    private static long timeGreedy(List<String[]> corpus, Set<String> dictionary) {
        long start = System.nanoTime();
        long sink = 0;
        for (String[] syllables : corpus) {
            sink += segmentGreedy(syllables, dictionary).length;
        }
        long elapsed = System.nanoTime() - start;
        if (sink < 0) {
            throw new IllegalStateException();
        }
        return elapsed;
    }

    /** Dem so cau ma hai thuat toan cho ket qua khac nhau, va in vai vi du. */
    private static void compareSegmentations(List<String[]> corpus,
                                             MaxWeightSegmenter dp,
                                             Set<String> greedyDictionary) {
        int documentsDiffering = 0;
        long tokensDp = 0;
        long tokensGreedy = 0;
        long differingPositions = 0;
        List<String> examples = new ArrayList<>();

        for (String[] syllables : corpus) {
            int[] a = dp.segment(syllables);
            int[] b = segmentGreedy(syllables, greedyDictionary);
            tokensDp += a.length - 1;
            tokensGreedy += b.length - 1;
            if (!Arrays.equals(a, b)) {
                documentsDiffering++;
                differingPositions += countDifferingBoundaries(a, b);
                if (examples.size() < 5) {
                    String example = firstDifference(syllables, a, b);
                    if (example != null) {
                        examples.add(example);
                    }
                }
            }
        }

        System.out.println("--- Khac biet ve ket qua tach ---");
        System.out.printf("  tai lieu tach khac nhau : %,d / %,d  (%.1f%%)%n",
                documentsDiffering, corpus.size(), 100.0 * documentsDiffering / corpus.size());
        System.out.printf("  moc gioi han khac nhau  : %,d%n", differingPositions);
        System.out.printf("  tong token — QHD / tham lam: %,d / %,d%n", tokensDp, tokensGreedy);
        if (!examples.isEmpty()) {
            System.out.println("\n  Vi du (tham lam  ->  quy hoach dong):");
            for (String example : examples) {
                System.out.println("    " + example);
            }
        }
    }

    private static int countDifferingBoundaries(int[] a, int[] b) {
        Set<Integer> boundariesA = new HashSet<>();
        for (int value : a) {
            boundariesA.add(value);
        }
        int differing = 0;
        for (int value : b) {
            if (!boundariesA.contains(value)) {
                differing++;
            }
        }
        return differing;
    }

    /** Doan ngan quanh cho tach khac nhau dau tien, de doc bang mat. */
    private static String firstDifference(String[] syllables, int[] dp, int[] greedy) {
        int i = 0;
        while (i < dp.length && i < greedy.length && dp[i] == greedy[i]) {
            i++;
        }
        if (i == 0 || i >= dp.length || i >= greedy.length) {
            return null;
        }
        int from = dp[i - 1];
        int to = Math.min(syllables.length, from + 6);
        return String.format("%-42s -> %s", render(syllables, greedy, from, to),
                render(syllables, dp, from, to));
    }

    private static String render(String[] syllables, int[] boundaries, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int k = 0; k + 1 < boundaries.length; k++) {
            int start = boundaries[k];
            int end = boundaries[k + 1];
            if (start < from || start >= to) {
                continue;
            }
            out.append('[');
            for (int i = start; i < end && i < syllables.length; i++) {
                if (i > start) {
                    out.append('_');
                }
                out.append(syllables[i]);
            }
            out.append(']');
        }
        return out.toString();
    }

    /**
     * Longest Matching tham lam — ban cai lai nguyen van thuat toan cu cua
     * {@code VietnameseTokenizer}, ke ca phan tao mang tam va chuoi moi tai moi vi tri.
     * Giu nguyen ca cho kem hieu qua vi day chinh la thu can do.
     */
    static int[] segmentGreedy(String[] syllables, Set<String> dictionary) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        int i = 0;
        while (i < syllables.length) {
            int matchedLen = 1;
            int maxLen = Math.min(VietnameseWordDictionary.MAX_SYLLABLES, syllables.length - i);
            for (int len = maxLen; len >= 2; len--) {
                String candidate = String.join(" ", Arrays.copyOfRange(syllables, i, i + len));
                if (dictionary.contains(candidate)) {
                    matchedLen = len;
                    break;
                }
            }
            i += matchedLen;
            boundaries.add(i);
        }
        int[] result = new int[boundaries.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = boundaries.get(k);
        }
        return result;
    }

    /**
     * Trai trie thanh {@code HashSet<String>} de ban tham lam co dung tu dien de tra.
     *
     * <p>Duyet toan bo trie theo chieu sau. Chi dung cho benchmark — he thong that
     * khong bao gio can cau truc nay.
     */
    private static Set<String> flattenToStringSet(VietnameseWordDictionary dictionary) {
        Set<String> words = new HashSet<>();
        // Trie khong cho duyet nguoc tu nut ve chuoi, nen doc lai chinh file tu dien.
        try (java.io.InputStream is =
                     TokenizerBenchmark.class.getResourceAsStream("/vietnamese-words.txt");
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    words.add(line.substring(0, tab));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return words;
    }

    private static String text(WebDocument doc) {
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        String body = doc.getBodyText() == null ? "" : doc.getBodyText();
        return title + " " + body;
    }

    /** Cung buoc chuan hoa nhu {@code VietnameseTokenizer}, tach ra de do rieng. */
    private static String[] splitIntoSyllables(String text) {
        String nfc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.forLanguageTag("vi"));
        String cleaned = nfc.replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split(" ");
    }
}
