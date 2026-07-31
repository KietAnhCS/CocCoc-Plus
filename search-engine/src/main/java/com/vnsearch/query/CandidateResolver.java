package com.vnsearch.query;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.ast.QueryNode;
import com.vnsearch.query.filter.CandidateFilter;
import com.vnsearch.query.filter.DomainFilter;
import com.vnsearch.query.filter.MaxCandidatesFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bien mot {@link QueryParser.ParsedQuery} thanh danh sach docId ung vien.
 *
 * <p><b>Hai mau thiet ke chia nhau hai viec khac han nhau:</b>
 * <ol>
 *   <li><b>Composite</b> ({@link QueryNode}) — truy hoi BOOLEAN: AND, OR, NOT,
 *       term, cum tu. Lam viec tren posting list, ket qua luon sap xep tang
 *       dan theo docId.</li>
 *   <li><b>Chain of Responsibility</b> ({@link CandidateFilter}) — cac rang
 *       buoc SAU truy hoi khong bieu dien duoc bang posting list: loc theo
 *       domain ({@code site:}), chan tren so ung vien.</li>
 * </ol>
 * Ranh gioi nay khong tuy tien: mot rang buoc co posting list thi thuoc ve cay;
 * mot rang buoc tren sieu du lieu cua tai lieu thi thuoc ve duong ong loc.
 *
 * <p><b>Vi sao tach rieng thanh mot lop:</b> logic nay truoc day nam trong
 * {@code SearchEngineFacade} duoi dang phuong thuc private, nen bo danh gia
 * chat luong khong goi lai duoc va buoc phai viet lai mot ban sao. Hai ban sao
 * chac chan se troi lech nhau theo thoi gian, va khi do MOI con so trong bao
 * cao danh gia deu mat gia tri vi chung do mot duong di khac voi duong di ma
 * he thong thuc su phuc vu nguoi dung.
 *
 * <p><b>LUI DAN VE AND-CUA-TAP-CON.</b> AND ngam dinh giua cac term la ngu
 * nghia dung cho truy van ngan, nhung voi truy van dai no bien mot ket qua tot
 * thanh KHONG CO ket qua nao: chi can mot tieng vang mat khoi corpus la giao
 * cua moi posting list bang rong. Truy van "may tinh xach tay gia re cho sinh
 * vien" hau nhu chac chan tra ve rong du corpus co day du tai lieu ve may tinh
 * xach tay. Khi giao day du rong, lop nay <b>bo dan cac term it thong tin
 * nhat</b> roi thu lai — xem {@link #relaxAndRetry}. Diem tinh khong doi: viec
 * CHAM DIEM van dung nguyen tan suat term cua truy van GOC, nen tai lieu khop
 * nhieu term hon van duoc xep tren.
 */
public final class CandidateResolver {

    /**
     * Duong ong loc, xep theo nguyen tac "re va loai nhieu truoc".
     *
     * <p>Them mot bo loc moi chi can them mot dong o day — KHONG sua
     * {@link #resolve}. Doi thu tu loc chi can doi thu tu trong danh sach.
     * Va vi moi tang co {@link CandidateFilter#name()}, co the boc timer quanh
     * {@code apply} de in bang "tang nao loai bao nhieu, ton bao nhieu ms".
     */
    private static final List<CandidateFilter> FILTERS = List.of(
            new DomainFilter(),
            new MaxCandidatesFilter());

    private static final QueryParser AST_BUILDER = new QueryParser();

    private CandidateResolver() {
    }

    /**
     * Ket qua phan giai: danh sach ung vien va tan suat term cua truy van
     * (dung lai cho khau tinh diem nen tra ve luon — tranh tinh hai lan, va
     * quan trong hon, tranh HAI CACH TINH khac nhau).
     *
     * @param droppedTerms cac term da bi bo de co ket qua, rong neu truy van
     *                     day du da khop. Tra ra ngoai thay vi bo qua am tham:
     *                     nguoi dung co quyen biet ket qua ho dang xem ung voi
     *                     mot truy van HEP HON truy van ho go.
     */
    public record ResolvedQuery(List<Integer> candidateDocIds,
                                 Map<String, Integer> queryTermFrequency,
                                 List<String> droppedTerms) {

        /** Truy van khop day du, khong phai bo term nao. */
        public ResolvedQuery(List<Integer> candidateDocIds, Map<String, Integer> queryTermFrequency) {
            this(candidateDocIds, queryTermFrequency, List.of());
        }

        public boolean wasRelaxed() {
            return !droppedTerms.isEmpty();
        }
    }

    public static ResolvedQuery resolve(SearchIndex index, QueryParser.ParsedQuery parsed) {
        Map<String, Integer> queryTermFrequency = buildQueryTermFrequency(parsed);

        // --- Giai doan 1: truy hoi boolean bang cay bieu thuc (Composite) ---
        QueryNode ast = AST_BUILDER.buildAst(parsed);
        if (ast == null) {
            return new ResolvedQuery(List.of(), queryTermFrequency);
        }

        List<Integer> candidates = applyFilters(ast.evaluate(index), index, parsed);
        if (!candidates.isEmpty()) {
            return new ResolvedQuery(candidates, queryTermFrequency);
        }

        // --- Giai doan 2: khong co gi khop -> noi long truy van ---
        return relaxAndRetry(index, parsed, queryTermFrequency);
    }

    /**
     * Ap duong ong rang buoc sau truy hoi (Chain of Responsibility).
     *
     * <p>Rong la PHAN TU HAP THU cua moi phep loc, nen mot khi rong thi dung ngay.
     */
    private static List<Integer> applyFilters(List<Integer> candidates, SearchIndex index,
                                               QueryParser.ParsedQuery parsed) {
        CandidateFilter.FilterContext context = new CandidateFilter.FilterContext(index, parsed);
        for (CandidateFilter filter : FILTERS) {
            if (candidates.isEmpty()) {
                break;
            }
            if (!filter.isApplicable(context)) {
                continue;
            }
            candidates = filter.apply(candidates, context);
        }
        return candidates;
    }

    /**
     * Bo dan term it thong tin nhat cho toi khi co ket qua.
     *
     * <p><b>Bo theo thu tu nao.</b> Term nao bi bo truoc quyet dinh ket qua con
     * lai co con lien quan hay khong. Thu tu o day la <i>IDF tang dan</i>, tuc
     * bo term PHO BIEN nhat truoc, giu lai term hiem nhat — vi term hiem mang
     * nhieu thong tin phan biet hon. Dung dung mot dai luong ma
     * {@link com.vnsearch.ranking.BM25Scorer#idf} dung de cham diem, nen khau
     * noi long va khau xep hang khong noi hai thu khac nhau ve "term nao quan
     * trong".
     *
     * <p><b>Term co df = 0 duoc bo TAT CA trong mot buoc.</b> Chung khong the
     * khop bat cu tai lieu nao, nen giu lai du chi MOT cai la ket qua rong vinh
     * vien. Day cung la nguyen nhan pho bien nhat: nguoi dung go sai chinh ta,
     * hoac dung mot tu khong co trong corpus. Bo het trong mot buoc thay vi bo
     * tung cai tiet kiem toi {@code k} lan danh gia lai cay.
     *
     * <p><b>Cai gi KHONG bao gio bi bo.</b> Cum tu trong ngoac kep va nhom
     * {@code OR} la y dinh TUONG MINH cua nguoi dung, khong phai suy dien cua
     * he thong; term bi loai tru ({@code -tu}) lai cang khong — bo mot menh de
     * NOT se THEM vao ket qua dung nhung thu nguoi dung noi ro la khong muon.
     * Chi cac term don AND ngam dinh moi la thu he thong tu suy ra, nen cung
     * chi chung moi duoc phep rut lai.
     *
     * <p>Do phuc tap: toi da {@code k} lan danh gia lai cay voi {@code k} la so
     * term don. Chi chay khi ket qua day du RONG — tuc khi con duong hien tai
     * dang tra ve mot trang trong vo dung.
     */
    private static ResolvedQuery relaxAndRetry(SearchIndex index, QueryParser.ParsedQuery parsed,
                                                Map<String, Integer> queryTermFrequency) {
        List<String> mustTerms = parsed.mustTerms();
        if (mustTerms.isEmpty()) {
            return new ResolvedQuery(List.of(), queryTermFrequency);
        }

        // Neu mot cum tu (hoac ca mot nhom OR) chua term khong ton tai, khong
        // phep bo term don nao cuu duoc — thoat ngay thay vi thu k lan vo ich.
        if (isUnmatchable(index, parsed)) {
            return new ResolvedQuery(List.of(), queryTermFrequency);
        }

        List<String> remaining = new ArrayList<>(mustTerms);
        List<String> dropped = new ArrayList<>();

        // Buoc 1: bo TAT CA term khong ton tai trong corpus, mot lan.
        remaining.removeIf(term -> {
            if (index.getDocumentFrequency(term) == 0) {
                dropped.add(term);
                return true;
            }
            return false;
        });

        if (!dropped.isEmpty()) {
            ResolvedQuery attempt = attempt(index, parsed, queryTermFrequency, remaining, dropped);
            if (attempt != null) {
                return attempt;
            }
        }

        // Buoc 2: bo tiep tung term mot, pho bien nhat truoc (IDF tang dan).
        remaining.sort(Comparator.comparingInt(index::getDocumentFrequency).reversed());
        while (remaining.size() > 1) {
            dropped.add(remaining.remove(0));
            ResolvedQuery attempt = attempt(index, parsed, queryTermFrequency, remaining, dropped);
            if (attempt != null) {
                return attempt;
            }
        }

        return new ResolvedQuery(List.of(), queryTermFrequency);
    }

    /**
     * Thu lai truy van voi tap term don da rut gon.
     *
     * @return ket qua neu lan thu nay co ung vien, {@code null} neu van rong
     */
    private static ResolvedQuery attempt(SearchIndex index, QueryParser.ParsedQuery parsed,
                                          Map<String, Integer> queryTermFrequency,
                                          List<String> remainingTerms, List<String> dropped) {
        QueryParser.ParsedQuery relaxed = new QueryParser.ParsedQuery(
                List.copyOf(remainingTerms), parsed.phrases(), parsed.excludedTerms(),
                parsed.orGroups(), parsed.siteFilter());

        QueryNode ast = AST_BUILDER.buildAst(relaxed);
        if (ast == null) {
            return null; // khong con menh de khang dinh nao de truy hoi
        }
        List<Integer> candidates = applyFilters(ast.evaluate(index), index, relaxed);
        if (candidates.isEmpty()) {
            return null;
        }
        // Diem van tinh theo truy van GOC: tai lieu khop nhieu term hon van tren.
        return new ResolvedQuery(candidates, queryTermFrequency, List.copyOf(dropped));
    }

    /**
     * Truy van chua mot menh de khong the khop du co bo bao nhieu term don.
     *
     * <p>Mot cum tu chi khop khi MOI tieng cua no ton tai; mot nhom OR chi khop
     * khi CO IT NHAT MOT ve ton tai. Vi pham hai dieu do la ket qua rong vinh
     * vien, khong phai thu noi long chua tay.
     */
    private static boolean isUnmatchable(SearchIndex index, QueryParser.ParsedQuery parsed) {
        for (List<String> phrase : parsed.phrases()) {
            for (String term : phrase) {
                if (index.getDocumentFrequency(term) == 0) {
                    return true;
                }
            }
        }
        for (List<String> group : parsed.orGroups()) {
            boolean anyExists = false;
            for (String alternative : group) {
                if (index.getDocumentFrequency(alternative) > 0) {
                    anyExists = true;
                    break;
                }
            }
            if (!anyExists) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tan suat moi term TRONG TRUY VAN, dung cho vector truy van cua scorer.
     *
     * <p>Gom ca term cua cum tu va cua nhom OR: neu bo sot, trong so truy van
     * se sai va scorer cham diem lech.
     *
     * <p>Luon tinh tu truy van GOC, ke ca khi truy van bi noi long: term da bi
     * bo khoi phan TRUY HOI van phai duoc tinh diem, vi do chinh la thu phan
     * biet tai lieu khop 4/5 term voi tai lieu chi khop 3/5.
     */
    private static Map<String, Integer> buildQueryTermFrequency(QueryParser.ParsedQuery parsed) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String term : parsed.mustTerms()) {
            frequency.merge(term, 1, Integer::sum);
        }
        for (List<String> phrase : parsed.phrases()) {
            for (String term : phrase) {
                frequency.merge(term, 1, Integer::sum);
            }
        }
        for (List<String> group : parsed.orGroups()) {
            for (String term : group) {
                frequency.merge(term, 1, Integer::sum);
            }
        }
        return frequency;
    }
}
