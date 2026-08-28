package com.vnsearch.service;

import com.vnsearch.index.VietnameseTokenizer;

/**
 * Doan xem mot doan van ban co phai tieng Viet khong.
 *
 * <p><b>Vi sao tach thanh lop rieng.</b> Truoc day day la mot phuong thuc
 * private {@code looksVietnamese} nam trong {@code SearchEngineFacade} — mot
 * lop dieu phoi. Mot ham doan ngon ngu nam trong lop dieu phoi la vi du ro
 * nhat cua "Feature Envy": no khong dung gi cua lop chua no, va no thuoc ve
 * mot mien tri thuc hoan toan khac.
 *
 * <p>Tach ra con cho phep dung no o cho THU HAI ma truoc day bo sot:
 * {@code KnownItemQueryGenerator} sinh truy van danh gia tu corpus co lan bai
 * tieng Trung va tieng Anh, tao ra nhung truy van vo nghia nhu
 * {@code "柬埔寨国会主席昆索达莉圆满结束对越南的正式访问 共产主义"}.
 *
 * <p><b>Heuristic:</b> dung dau thanh dieu lam dau hieu. Van ban tieng Viet
 * that gan nhu luon co it nhat mot nguyen am mang dau trong mot cau day du;
 * tieu de tieng Anh thi khong bao gio co.
 *
 * <p>Ky thuat kiem tra dung <b>diem bat dong</b> cua phep bo dau:
 * {@code stripDiacritics(s) == s} khi va chi khi {@code s} khong co dau nao.
 */
public final class LanguageDetector {

    /**
     * Nguong do dai duoi day thi khong ket luan.
     *
     * <p>Tieu de rat ngan ("Video", "Ảnh") co the khong co dau nao ma van la
     * tieng Viet, nen coi nhu hop le de khong loai nham.
     */
    public static final int MIN_LENGTH_TO_JUDGE = 15;

    private LanguageDetector() {
    }

    /** {@code true} neu van ban co ve la tieng Viet (hoac qua ngan de ket luan). */
    public static boolean looksVietnamese(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.length() < MIN_LENGTH_TO_JUDGE) {
            return true; // qua ngan de ket luan -> khong loai nham
        }
        // Co it nhat mot ky tu mang dau => bo dau lam chuoi THAY DOI.
        return !VietnameseTokenizer.stripDiacritics(trimmed).equals(trimmed);
    }
}
