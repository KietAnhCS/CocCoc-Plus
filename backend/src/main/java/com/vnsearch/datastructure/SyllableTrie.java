package com.vnsearch.datastructure;

import java.util.HashMap;
import java.util.Map;

/**
 * Trie tren don vi AM TIET (khong phai ky tu), luu bang MANG PHANG.
 *
 * <p><b>Bai toan can giai.</b> Bo tach tu tieng Viet phai tra loi lien tuc mot
 * cau hoi: "day am tiet {@code s[i..j]} co phai mot tu trong tu dien khong, va
 * neu co thi trong so bao nhieu?". Cach cu trong {@code VietnameseTokenizer} la
 * ghep chuoi roi tra {@code HashSet}:
 * <pre>
 *   for (int len = 4; len &gt;= 2; len--) {
 *       String candidate = String.join(" ", Arrays.copyOfRange(syllables, i, i + len));
 *       if (bigramDictionary.contains(candidate)) { ... }
 *   }
 * </pre>
 * Moi vi tri {@code i} tao ra <b>ba mang tam va ba chuoi moi</b> chi de vut di
 * ngay sau khi tra xong. Tren corpus 5.011 trang (khoang 5 trieu am tiet) day la
 * chung 15 trieu lan cap phat vo ich, va tat ca deu chet non nen doi het len vai
 * gay ap luc GC.
 *
 * <p><b>Cach trie xoa bo viec do.</b> Duyet trie di theo tung am tiet mot va
 * <b>khong cap phat gi ca</b>: tu nut hien tai, hoi "co con mang nhan la am tiet
 * nay khong". Bon do dai 1..4 duoc kiem tra trong <b>mot</b> luot di, thay vi bon
 * luot dung lai chuoi doc lap. Ngoai ra trie con tra loi duoc cau hoi ma
 * {@code HashSet} khong tra loi duoc: "co con tu nao dai hon bat dau tu day
 * khong?" — neu {@link #child} tra ve -1 thi cat nhanh ngay, khoi thu cac do dai
 * con lai.
 *
 * <p><b>Vi sao mang phang chu khong phai nut doi tuong.</b> Cach viet tu nhien la
 * moi nut mot doi tuong {@code Node} chua {@code Map<String, Node> children}. Voi
 * 185.000 tu / khoang 460.000 nut, do la 460.000 doi tuong {@code Node} cong
 * 460.000 doi tuong {@code HashMap} — moi {@code HashMap} rong da ton ~48 byte
 * header truoc khi chua gi. Thay vao do o day:
 * <ul>
 *   <li>Nut chi la mot <b>chi so int</b>. Thuoc tinh cua nut nam trong mang song
 *       song ({@link #weight}).</li>
 *   <li>Toan bo canh cua CA cay nam trong <b>mot</b> bang bam dia chi mo, khoa la
 *       {@code (nutCha << 32) | idAmTiet}. Mot bang cho ca trie, thay vi mot
 *       bang cho moi nut.</li>
 * </ul>
 *
 * <p><b>Vi sao tu cai bang bam thay vi {@code HashMap<Long, Integer>}.</b> Khoa
 * la {@code long} va gia tri la {@code int}. {@code HashMap} bat buoc phai
 * <b>boxing</b> ca hai thanh {@code Long}/{@code Integer}, tuc them hai doi tuong
 * moi canh (~16 va ~16 byte) cong mot doi tuong {@code Node} cua bang bam
 * (~32 byte) — khoang 64 byte phu troi cho mot canh dang le chi can 12 byte du
 * lieu. Bang dia chi mo o day luu thang vao {@code long[]} + {@code int[]}: khong
 * doi tuong, khong con tro, du lieu nam lien nhau nen than thien voi cache CPU.
 *
 * <p><b>Do phuc tap.</b> {@code intern} / {@code child} / {@code weightAt} deu la
 * <b>O(1)</b> ky vong. {@code insert} tren tu co {@code k} am tiet la
 * <b>O(k)</b>. Bo nho: O(so nut + kich thuoc bang canh), khong phu thuoc do dai
 * chuoi vi am tiet da duoc quy ve id so nguyen.
 *
 * <p><b>Bat bien.</b> Id am tiet luon <b>&ge; 1</b>. Nho vay khoa canh
 * {@code (nutCha << 32) | idAmTiet} khong bao gio bang 0, va gia tri 0 trong
 * {@link #edgeKey} duoc dung lam dau hieu "o trong" — khoi phai cap phat them
 * mot mang {@code boolean[]} danh dau o da dung.
 */
public class SyllableTrie {

    /** Chi so cua nut goc. Luon la 0 vi nut goc duoc cap phat dau tien. */
    public static final int ROOT = 0;

    /** Tra ve khi khong co canh / khong biet am tiet. */
    public static final int NONE = -1;

    /** Nut khong ket thuc mot tu nao co trong so 0 — tu that luon co trong so duong. */
    private static final double NOT_A_WORD = 0.0;

    /**
     * Nguong tai cua bang canh. Vuot qua thi nhan doi bang.
     *
     * <p>0,55 thay vi 0,75 quen thuoc cua {@code HashMap}: do dai chuoi tham do
     * trong dia chi mo tuyen tinh tang <b>phi tuyen</b> theo he so tai (xap xi
     * {@code 1/(1-a)}), nen 0,75 cho chuoi dai gap doi 0,55. Doi 20% bo nho lay
     * do tre tra cuu on dinh la dang gia o day, vi {@link #child} nam tren duong
     * nong nhat cua ca he thong.
     */
    private static final double MAX_LOAD_FACTOR = 0.55;

    // --- Nut: mang song song, "nut" chi la chi so vao cac mang nay ---
    private double[] weight;
    private int nodeCount;

    // --- Canh: mot bang bam dia chi mo cho TOAN BO trie ---
    private long[] edgeKey;
    private int[] edgeValue;
    private int edgeCount;
    private int edgeMask;

    // --- Noi suy am tiet -> id (>= 1) ---
    private final Map<String, Integer> syllableIds = new HashMap<>();

    public SyllableTrie() {
        this(1 << 12);
    }

    /**
     * @param expectedEdges so canh du kien; dung de cap phat truoc bang, tranh
     *                      phai bam lai nhieu lan khi nap tu dien lon
     */
    public SyllableTrie(int expectedEdges) {
        int capacity = tableSizeFor((int) (expectedEdges / MAX_LOAD_FACTOR) + 1);
        this.edgeKey = new long[capacity];
        this.edgeValue = new int[capacity];
        this.edgeMask = capacity - 1;
        this.weight = new double[Math.max(16, expectedEdges / 2)];
        this.nodeCount = 1; // cap phat nut goc
    }

    /** Luy thua cua 2 nho nhat khong nho hon {@code n} (toi thieu 16). */
    private static int tableSizeFor(int n) {
        int size = 16;
        while (size < n) {
            size <<= 1;
        }
        return size;
    }

    /**
     * Tron bit cua khoa truoc khi lay du.
     *
     * <p>Khoa cua ta la {@code (nutCha << 32) | idAmTiet}. Neu chi lay
     * {@code khoa & mask} thi <b>32 bit cao — chinh la nut cha — bi vut di hoan
     * toan</b>: moi canh xuat phat tu cung mot am tiet se do vao cung mot o, bat
     * ke cha la ai. Am tiet pho bien nhu "cua" xuat hien duoi hang nghin nut cha
     * khac nhau, va tat ca se xep thanh mot chuoi tham do dai — bien O(1) thanh
     * O(n). Ham tron cua splitmix64 rai anh huong cua ca 64 bit len cac bit thap.
     */
    private static int hash(long key) {
        long h = key;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return (int) h;
    }

    private static long edgeKey(int parent, int syllableId) {
        return ((long) parent << 32) | (syllableId & 0xFFFFFFFFL);
    }

    /**
     * Tra id cua mot am tiet, cap id moi neu chua tung gap.
     *
     * <p>Chi dung khi NAP tu dien. Luc tra cuu hay dung {@link #idOf} de tranh
     * lam phinh bang bang nhung am tiet chi xuat hien trong van ban dau vao.
     */
    public int intern(String syllable) {
        Integer existing = syllableIds.get(syllable);
        if (existing != null) {
            return existing;
        }
        int id = syllableIds.size() + 1; // bat dau tu 1: xem "Bat bien" o Javadoc lop
        syllableIds.put(syllable, id);
        return id;
    }

    /** Id cua am tiet, hoac {@link #NONE} neu tu dien chua bao gio thay am tiet nay. */
    public int idOf(String syllable) {
        Integer id = syllableIds.get(syllable);
        return id == null ? NONE : id;
    }

    /**
     * Them mot tu vao trie.
     *
     * @param syllables cac am tiet cua tu, theo thu tu
     * @param wordWeight trong so cua tu; phai &gt; 0 vi 0 la dau hieu "khong phai tu"
     */
    public void insert(String[] syllables, double wordWeight) {
        if (syllables.length == 0) {
            return;
        }
        int node = ROOT;
        for (String syllable : syllables) {
            node = childOrCreate(node, intern(syllable));
        }
        // Cung mot tu co the den tu ca hai nguon tu dien — giu ban trong so lon hon.
        if (wordWeight > weight[node]) {
            weight[node] = wordWeight;
        }
    }

    /** Tim canh; neu chua co thi tao nut moi. */
    private int childOrCreate(int parent, int syllableId) {
        long key = edgeKey(parent, syllableId);
        int slot = hash(key) & edgeMask;
        while (edgeKey[slot] != 0) {
            if (edgeKey[slot] == key) {
                return edgeValue[slot];
            }
            slot = (slot + 1) & edgeMask;
        }
        int child = allocateNode();
        edgeKey[slot] = key;
        edgeValue[slot] = child;
        edgeCount++;
        if (edgeCount > (int) (edgeKey.length * MAX_LOAD_FACTOR)) {
            growEdgeTable();
        }
        return child;
    }

    private int allocateNode() {
        if (nodeCount == weight.length) {
            double[] bigger = new double[weight.length << 1];
            System.arraycopy(weight, 0, bigger, 0, weight.length);
            weight = bigger;
        }
        weight[nodeCount] = NOT_A_WORD;
        return nodeCount++;
    }

    private void growEdgeTable() {
        long[] oldKeys = edgeKey;
        int[] oldValues = edgeValue;
        int capacity = oldKeys.length << 1;
        edgeKey = new long[capacity];
        edgeValue = new int[capacity];
        edgeMask = capacity - 1;
        for (int i = 0; i < oldKeys.length; i++) {
            long key = oldKeys[i];
            if (key == 0) {
                continue;
            }
            int slot = hash(key) & edgeMask;
            while (edgeKey[slot] != 0) {
                slot = (slot + 1) & edgeMask;
            }
            edgeKey[slot] = key;
            edgeValue[slot] = oldValues[i];
        }
    }

    /** Nut goc. */
    public int root() {
        return ROOT;
    }

    /**
     * Con cua {@code node} theo canh {@code syllableId}, hoac {@link #NONE}.
     *
     * <p>Tra ve {@link #NONE} co nghia manh hon "khong phai tu": <b>khong co tu
     * nao trong tu dien bat dau bang tien to nay</b>, nen ben goi cat nhanh duoc
     * ngay. Do la thong tin ma {@code HashSet} khong bao gio cung cap.
     */
    public int child(int node, int syllableId) {
        if (syllableId == NONE) {
            return NONE;
        }
        long key = edgeKey(node, syllableId);
        int slot = hash(key) & edgeMask;
        while (true) {
            long found = edgeKey[slot];
            if (found == 0) {
                return NONE;
            }
            if (found == key) {
                return edgeValue[slot];
            }
            slot = (slot + 1) & edgeMask;
        }
    }

    /** Trong so cua tu ket thuc tai {@code node}; 0 neu nut nay khong ket thuc tu nao. */
    public double weightAt(int node) {
        return weight[node];
    }

    /** {@code true} neu co mot tu ket thuc dung tai {@code node}. */
    public boolean isWord(int node) {
        return weight[node] > NOT_A_WORD;
    }

    /** So am tiet phan biet da noi suy. */
    public int syllableCount() {
        return syllableIds.size();
    }

    /** So nut cua trie (ke ca nut goc). */
    public int nodeCount() {
        return nodeCount;
    }

    /** So canh cua trie. */
    public int edgeCount() {
        return edgeCount;
    }

    /** Uoc luong bo nho cua hai bang mang phang, tinh bang byte — dung cho bao cao. */
    public long approximateBytes() {
        return (long) edgeKey.length * Long.BYTES
                + (long) edgeValue.length * Integer.BYTES
                + (long) weight.length * Double.BYTES;
    }
}
