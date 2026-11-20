package com.vnsearch.index;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * Nen danh sach so nguyen tang dan bang <b>delta encoding + variable-byte
 * (VByte)</b> — ky thuat nen chi muc kinh dien cua nganh truy hoi thong tin.
 *
 * <p><b>Hai buoc nen:</b>
 *
 * <p>1. <i>Delta encoding (ma hoa hieu).</i> Posting list va danh sach vi tri
 * deu SAP XEP TANG DAN, nen thay vi luu gia tri tuyet doi, ta luu HIEU giua
 * hai phan tu lien tiep:
 * <pre>
 *   goc   : [3, 17, 19, 40, 1041]
 *   delta : [3, 14,  2, 21, 1001]
 * </pre>
 * Hieu luon nho hon nhieu so voi gia tri goc, va cang nho khi danh sach cang
 * day (posting list dai thi docId lien tiep cang sat nhau).
 *
 * <p>2. <i>Variable-byte encoding.</i> Moi so duoc ghi bang so BYTE toi thieu
 * can thiet. 7 bit thap cua moi byte mang du lieu; bit cao nhat (bit 8) la
 * co "con byte nua khong":
 * <pre>
 *   bit cao = 1  -> con byte tiep theo
 *   bit cao = 0  -> byte cuoi cung cua so nay
 * </pre>
 * Nho vay:
 * <pre>
 *   0     .. 127        -> 1 byte
 *   128   .. 16 383     -> 2 byte
 *   16384 .. 2 097 151  -> 3 byte
 *   ...                    toi da 5 byte cho int 32-bit
 * </pre>
 *
 * <p><b>Hieu qua tren du lieu that.</b> Posting list cua term
 * {@code cong_nghe} co 1.639 muc trai deu tren 5.011 tai lieu, nen hieu
 * trung binh la {@code 5011/1639 ~= 3}. Voi delta ~3, moi docId chi ton
 * <b>1 byte</b> thay vi 4 byte cua {@code int} — tiet kiem <b>75%</b>.
 * Danh sach vi tri con day hon nen ty le nen con tot hon nua.
 *
 * <p><b>Vi sao dung duoc:</b> ca hai buoc deu dua vao bat bien "danh sach da
 * sap xep tang dan" ma {@link InvertedIndex} dam bao (posting list luon
 * append theo docId tang dan; positions duoc sinh theo thu tu token tang dan).
 * Day la loi ich thu BA cua bat bien do, ben canh two-pointer merge va
 * binary search.
 *
 * <p>Do phuc tap: {@link #encodeSorted} va {@link #decodeSorted} deu O(n)
 * mot luot, khong cap phat trung gian ngoai bo dem ket qua.
 */
public final class VByteCodec {

    private VByteCodec() {
    }

    /**
     * O(n) - nen mot danh sach so nguyen KHONG AM, SAP XEP TANG DAN bang
     * delta + VByte.
     *
     * @param sorted danh sach da sap xep tang dan (khong bi sua doi)
     * @return mang byte da nen
     * @throws IllegalArgumentException neu danh sach khong tang dan hoac co so am
     */
    public static byte[] encodeSorted(int[] sorted) {
        if (sorted == null || sorted.length == 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(sorted.length);
        int previous = 0;
        for (int i = 0; i < sorted.length; i++) {
            int value = sorted[i];
            if (value < 0) {
                throw new IllegalArgumentException("VByte chi ma hoa so khong am, gap: " + value);
            }
            if (i > 0 && value < previous) {
                throw new IllegalArgumentException(
                        "Danh sach phai tang dan; vi tri " + i + " co " + value + " < " + previous);
            }
            writeVInt(out, value - previous); // ← delta
            previous = value;
        }
        return out.toByteArray();
    }

    /**
     * O(n) - giai nen danh sach da nen boi {@link #encodeSorted}.
     *
     * @param data  mang byte da nen
     * @param count so phan tu (phai luu rieng, vi VByte khong tu mo ta do dai)
     */
    public static int[] decodeSorted(byte[] data, int count) {
        if (count <= 0) {
            return new int[0];
        }
        int[] result = new int[count];
        int position = 0;
        int previous = 0;
        for (int i = 0; i < count; i++) {
            long packed = readVInt(data, position);
            int delta = (int) (packed & 0xFFFFFFFFL);
            position = (int) (packed >>> 32);
            previous += delta;
            result[i] = previous;
        }
        return result;
    }

    /**
     * O(tong n) - nen NHIEU danh sach tang dan vao MOT mang byte lien tuc.
     *
     * <p><b>Vi sao can ham nay.</b> Danh sach vi tri (positions) cua mot term
     * duoc luu RIENG cho tung posting. Neu noi tat ca lai roi delta hoa mot
     * lan, delta se AM tai moi ranh gioi posting (vi tri cua doc sau bat dau
     * lai tu 0), ma VByte chi ma hoa duoc so khong am. Vi vay moi doan phai
     * duoc delta hoa DOC LAP — {@code previous} reset ve 0 dau moi doan.
     *
     * <p>Do dai tung doan KHONG duoc luu o day; nguoi goi phai giu rieng
     * (xem {@link CompressedPostings} — no suy ra tu mang offset tich luy,
     * dung ky thuat {@code rowPtr} cua CSR).
     *
     * @param segments cac doan, moi doan phai tang dan va khong am
     * @return mang byte chua tat ca cac doan noi tiep nhau
     */
    public static byte[] encodeSegments(List<int[]> segments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int[] segment : segments) {
            int previous = 0; // ← reset moi doan: day la diem khac encodeSorted
            for (int i = 0; i < segment.length; i++) {
                int value = segment[i];
                if (value < 0) {
                    throw new IllegalArgumentException("VByte chi ma hoa so khong am, gap: " + value);
                }
                if (i > 0 && value < previous) {
                    throw new IllegalArgumentException(
                            "Moi doan phai tang dan; vi tri " + i + " co " + value + " < " + previous);
                }
                writeVInt(out, value - previous);
                previous = value;
            }
        }
        return out.toByteArray();
    }

    /**
     * O(tong n) - giai nen mang byte do {@link #encodeSegments} tao ra.
     *
     * <p>Doc TUAN TU: sau khi doc xong doan {@code i}, con tro byte dung o dau
     * doan {@code i+1}. Nho vay khong can luu vi tri byte bat dau cua tung
     * doan — chi can biet SO PHAN TU cua tung doan.
     *
     * @param data   mang byte da nen
     * @param counts so phan tu cua tung doan, theo dung thu tu luc ma hoa
     */
    public static int[][] decodeSegments(byte[] data, int[] counts) {
        int[][] result = new int[counts.length][];
        int position = 0;
        for (int s = 0; s < counts.length; s++) {
            int[] segment = new int[counts[s]];
            int previous = 0; // ← reset moi doan, doi xung voi encodeSegments
            for (int i = 0; i < counts[s]; i++) {
                long packed = readVInt(data, position);
                previous += (int) (packed & 0xFFFFFFFFL);
                position = (int) (packed >>> 32);
                segment[i] = previous;
            }
            result[s] = segment;
        }
        return result;
    }

    /** Ghi mot so khong am theo VByte: 7 bit du lieu moi byte, bit cao la co "con nua". */
    private static void writeVInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {          // con bit ngoai 7 bit thap
            out.write((value & 0x7F) | 0x80);   // ghi 7 bit + bat co "con nua"
            value >>>= 7;
        }
        out.write(value & 0x7F);                // byte cuoi: bit cao = 0
    }

    /**
     * Doc mot so VByte tu {@code data} bat dau tai {@code position}.
     *
     * <p>Tra ve gia tri dong goi trong mot {@code long}: 32 bit thap la gia
     * tri doc duoc, 32 bit cao la vi tri doc tiep theo. Dong goi thay vi tra
     * ve hai gia tri de tranh cap phat object trong vong lap nong.
     */
    private static long readVInt(byte[] data, int position) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (position >= data.length) {
                throw new IllegalArgumentException("Du lieu VByte bi cat cut tai vi tri " + position);
            }
            int b = data[position++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break; // bit cao = 0 -> byte cuoi
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("So VByte vuot qua pham vi int 32-bit");
            }
        }
        return ((long) position << 32) | (value & 0xFFFFFFFFL);
    }

    /** So byte ma {@code value} chiem khi ma hoa VByte — dung de bao cao ty le nen. */
    public static int encodedSize(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            bytes++;
            value >>>= 7;
        }
        return bytes;
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        // Mo phong posting list that: 1.639 docId trai deu tren 5.011 tai lieu.
        int[] docIds = new int[1639];
        for (int i = 0; i < docIds.length; i++) {
            docIds[i] = i * 3 + (i % 2); // hieu trung binh ~3
        }

        byte[] compressed = encodeSorted(docIds);
        int rawBytes = docIds.length * Integer.BYTES;

        System.out.println("=== NEN POSTING LIST (delta + VByte) ===");
        System.out.printf("So phan tu       : %d%n", docIds.length);
        System.out.printf("Khong nen (int)  : %d byte%n", rawBytes);
        System.out.printf("Da nen (VByte)   : %d byte%n", compressed.length);
        System.out.printf("Ty le nen        : %.1f%% (tiet kiem %.1f%%)%n",
                100.0 * compressed.length / rawBytes,
                100.0 * (rawBytes - compressed.length) / rawBytes);

        int[] restored = decodeSorted(compressed, docIds.length);
        System.out.println("Giai nen dung nguyen ven: " + Arrays.equals(docIds, restored));
    }
}
