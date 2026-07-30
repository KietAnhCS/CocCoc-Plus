package com.vnsearch.index;

import java.util.List;

/**
 * <b>Strategy pattern</b> — giao dien chung cho cac bo tach tu, de he thong
 * hoan doi duoc cai dat ma khong phai sua tang chi muc hay tang truy van.
 *
 * <p><b>Vi sao can tach ra sau mot giao dien.</b> Trong bao cao danh gia,
 * {@code RelevanceScorer} cho phep chay thi nghiem ablation "mo hinh tinh diem
 * nao tot hon" bang cach thay dung MOT bien so. Bo tach tu <b>cung la mot bien
 * so</b> anh huong lon toi chat luong — thuc te no la <i>tran chat luong</i>
 * cua ca he thong, vi tu dien tu ghep hien chi co 154 muc. Nhung truoc day
 * {@code VietnameseTokenizer} la lop CU THE nen khong the do duoc cau hoi
 * "tokenizer nao tot hon", trong khi cau hoi ve scorer thi do duoc. Giao dien
 * nay xoa bo su bat doi xung do.
 *
 * <p><b>Bat bien song hanh, bat buoc phai giu:</b> chi muc va truy van PHAI
 * dung CUNG mot cai dat tokenizer (cung thuat toan VA cung tu dien). Neu luc
 * index sinh ra {@code máy_tính} ma luc truy van sinh ra {@code máy} +
 * {@code tính} thi khong bao gio khop — va loi nay <b>im lang</b>, khong nem
 * ngoai le nao, chi la ket qua rong mot cach kho hieu. Vi vay
 * {@link InvertedIndex} va {@code QueryParser} deu nhan tokenizer qua
 * constructor thay vi tu tao.
 */
public interface Tokenizer {

    /**
     * Tach mot doan van ban thanh danh sach token da chuan hoa, da ghep tu
     * ghep va da loc tu dung.
     *
     * @param text van ban dau vao ({@code null} hoac rong tra ve danh sach rong)
     */
    List<VietnameseTokenizer.Token> tokenize(String text);

    /** Ten ngan gon cua cai dat, dung lam nhan trong bang ket qua danh gia. */
    String name();
}
