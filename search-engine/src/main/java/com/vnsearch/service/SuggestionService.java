package com.vnsearch.service;

import com.vnsearch.datastructure.Trie;
import com.vnsearch.index.SearchIndex;
import com.vnsearch.index.Tokenizer;
import com.vnsearch.index.VietnameseTokenizer;
import com.vnsearch.model.WebDocument;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Goi y tu khoa (autocomplete) dua tren {@link Trie} tu cai.
 *
 * <p>Tach khoi {@code SearchEngineFacade} vi day la mot trach nhiem doc lap:
 * no co vong doi rieng (dung lai sau moi lan reindex), nguon du lieu rieng
 * (tieu de tai lieu + truy van nguoi dung), va quy tac rieng.
 *
 * <p><b>Ba loi da sua, deu ghi lai o day de khong tai pham:</b>
 * <table border="1">
 *   <tr><th>Loi</th><th>Hau qua</th></tr>
 *   <tr><td>Chen NGUYEN tieu de lam mot goi y</td>
 *       <td>Goi y dai loang ngoang, khong ai go het</td></tr>
 *   <tr><td>Chen TUNG TIENG le</td>
 *       <td>{@code cong}, {@code the}, {@code kinh} — tieng le tieng Viet
 *           phan lon KHONG phai tu</td></tr>
 *   <tr><td>Chi {@code insert} ma khong {@code clear()}</td>
 *       <td>Tieu de cua corpus CU van con sau moi lan crawl lai</td></tr>
 * </table>
 *
 * <p><b>Cach lam hien tai:</b> tokenize tieu de roi lay (1) cac tu ghep ma
 * tokenizer nhan ra, (2) cac cap token lien tiep. Nguon (2) bu cho viec tu dien
 * tu ghep chi co 154 muc: du {@code bóng đá} khong co trong tu dien, cap token
 * lien tiep van duoc ghi nhan.
 *
 * <p>Moi cum duoc chen HAI lan — duoi khoa co dau va khoa khong dau — nhung
 * cung tro toi MOT chuoi hien thi co dau, de nguoi go {@code cong nghe} van
 * nhan duoc goi y {@code công nghệ} dung chinh ta.
 */
@Component
public class SuggestionService {

    /** So lan toi thieu mot cum phai xuat hien trong corpus de duoc lam goi y. */
    public static final int MIN_SUGGESTION_FREQUENCY = 3;

    private final Tokenizer tokenizer;
    private final Trie trie = new Trie();

    public SuggestionService(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Dung lai toan bo Trie tu chi muc hien tai.
     *
     * <p>Phai {@link Trie#clear()} truoc: neu chi insert them, tieu de cua
     * corpus CU van con nam trong trie sau moi lan crawl lai, va nguoi dung bam
     * vao goi y do se nhan ve 0 ket qua.
     */
    public void rebuild(SearchIndex index) {
        trie.clear();

        Map<String, Integer> phraseFrequency = new HashMap<>();
        for (WebDocument doc : index.getAllDocuments().values()) {
            String title = doc.getTitle();
            // Loai tieu de khong phai tieng Viet: corpus co lan bai tieng Anh
            // cua VnExpress International, truoc day lam goi y hien ra
            // "the city that helped vietnam...".
            if (title == null || title.isBlank() || !LanguageDetector.looksVietnamese(title)) {
                continue;
            }
            List<VietnameseTokenizer.Token> tokens = tokenizer.tokenize(title);
            for (int i = 0; i < tokens.size(); i++) {
                String term = tokens.get(i).term();
                // (1) Tu ghep — tokenizer da noi bang "_" nen day von la mot tu hoan chinh.
                if (term.indexOf('_') >= 0) {
                    phraseFrequency.merge(term.replace('_', ' '), 1, Integer::sum);
                }
                // (2) Cap token lien tiep — bat cac cum nguoi dung hay go ma tu
                // dien tu ghep chua kip co, vi du "bóng đá Việt Nam".
                if (i + 1 < tokens.size()) {
                    String bigram = (term + " " + tokens.get(i + 1).term()).replace('_', ' ');
                    phraseFrequency.merge(bigram, 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<String, Integer> entry : phraseFrequency.entrySet()) {
            if (entry.getValue() < MIN_SUGGESTION_FREQUENCY) {
                continue; // loc nhieu
            }
            insertBothForms(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Ghi lai mot truy van THAT cua nguoi dung lam nguon goi y.
     *
     * <p>Chi goi khi truy van CO ket qua — tranh hoc tu truy van go sai chinh
     * ta. Day la vong phan hoi tu cai thien: cang nhieu nguoi dung, goi y cang
     * khop thoi quen go that.
     */
    public void learnFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        insertBothForms(query.trim().toLowerCase(Locale.ROOT), 1);
    }

    /**
     * Chen mot cum duoi CA HAI khoa (co dau va khong dau) nhung cung tro toi
     * MOT chuoi hien thi co dau.
     *
     * <p>Can thiet vi Trie khop tien to theo TUNG KY TU chinh xac: duong di
     * {@code c-o-n-g} khong bao gio toi duoc nhanh {@code c-ô-n-g}.
     */
    private void insertBothForms(String phrase, int frequency) {
        trie.insert(phrase, phrase, frequency);
        String withoutDiacritics = VietnameseTokenizer.stripDiacritics(phrase);
        // Dieu kien if tranh chen trung khi cum von khong co dau ("web", "robot").
        if (!withoutDiacritics.equals(phrase)) {
            trie.insert(withoutDiacritics, phrase, frequency);
        }
    }

    /** O(L + m log k) - lay toi da {@code limit} goi y bat dau bang {@code prefix}. */
    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        return trie.getSuggestions(prefix.trim().toLowerCase(Locale.ROOT), limit);
    }
}
