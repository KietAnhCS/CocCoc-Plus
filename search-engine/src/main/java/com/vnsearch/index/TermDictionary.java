package com.vnsearch.index;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>Flyweight pattern</b> — kho chuoi dung chung cho cac term cua chi muc.
 *
 * <p><b>Van de.</b> Chi muc co <b>136.768</b> term phan biet, nhung tokenizer
 * tao ra chuoi MOI cho moi lan gap term do:
 * <pre>
 *   term = String.join("_", Arrays.copyOfRange(syllables, i, i + matchedLen));
 * </pre>
 * Voi 5.011 tai lieu x ~1.400 tieng, do la khoang <b>7 trieu</b> object
 * {@code String} duoc cap phat, trong khi chi co 136.768 gia tri PHAN BIET.
 * Moi {@code String} ton {@code 16 (header) + 8 (ref) + 4 (hash) + 16 + L}
 * ~= {@code 44 + L} byte.
 *
 * <p><b>Giai phap.</b> Giu mot kho (pool) anh xa noi dung chuoi sang MOT
 * instance chuan tac duy nhat. Moi lan gap chuoi co noi dung da thay, tra ve
 * instance cu thay vi giu instance moi — instance moi tro thanh rac va duoc
 * thu hoi ngay.
 *
 * <p>Ket qua: chi muc, {@code positionsByTerm}, va moi {@code Token} deu tro
 * toi CUNG mot object cho cung mot term. Bo nho chuoi giam tu "so lan xuat
 * hien" xuong "so term phan biet".
 *
 * <p><b>Vi sao khong dung {@link String#intern()} co san cua JDK:</b> no dung
 * bang chuoi noi bo cua JVM, vung nho co kich thuoc cau hinh cung va
 * <b>khong giai phong duoc</b> cho toi khi lop bi go. Voi 136.768 term, no gay
 * ap luc len vung do va khong do duoc. Pool tu quan ly thi kiem soat duoc vong
 * doi ({@link #clear()}) va do duoc kich thuoc ({@link #size()}).
 *
 * <p>Do phuc tap: {@link #intern(String)} O(L) (bam chuoi), bo nho
 * O(tong ky tu cac term phan biet).
 *
 * <p><b>Khong thread-safe.</b> {@link InvertedIndex} chi dung no trong
 * {@code addDocument}, ma viec dung chi muc luon don luong (dung xong mot chi
 * muc moi hoan chinh roi gan bang tham chieu {@code volatile}).
 */
public final class TermDictionary {

    private final Map<String, String> pool;

    public TermDictionary() {
        this(1 << 18); // 262.144 — du cho 136.768 term ma khong phai rehash
    }

    public TermDictionary(int expectedTerms) {
        this.pool = new HashMap<>(expectedTerms);
    }

    /**
     * O(L) - tra ve instance CHUAN TAC cua chuoi. Moi lan goi voi noi dung
     * giong nhau deu tra ve CUNG MOT object.
     *
     * <p>{@code putIfAbsent} lam ca hai viec trong MOT lan bam: tra cuu va
     * chen neu chua co. Viet bang {@code containsKey} roi {@code put} se bam
     * hai lan.
     */
    public String intern(String term) {
        if (term == null) {
            return null;
        }
        String existing = pool.putIfAbsent(term, term);
        return existing != null ? existing : term;
    }

    /** So term phan biet dang giu — dung cho thong ke va bao cao. */
    public int size() {
        return pool.size();
    }

    /** Uoc luong bo nho tiet kiem duoc so voi viec giu rieng moi lan xuat hien. */
    public long estimatedBytes() {
        long total = 0;
        for (String term : pool.keySet()) {
            total += 44L + term.length() * 2L; // header + hash + byte[] + noi dung
        }
        return total;
    }

    public void clear() {
        pool.clear();
    }

    /** Demo minh hoa nho de chup man hinh lam bao cao. */
    public static void main(String[] args) {
        TermDictionary dictionary = new TermDictionary();

        // Mo phong: cung mot term gap lai nhieu lan trong corpus.
        String a = dictionary.intern(new String("máy_tính"));
        String b = dictionary.intern(new String("máy_tính"));
        String c = dictionary.intern(new String("công_nghệ"));

        System.out.println("a == b (cung instance)? " + (a == b));
        System.out.println("a == c ? " + (a == c));
        System.out.println("So term phan biet: " + dictionary.size());
    }
}
