package com.vnsearch.query;

import com.vnsearch.index.SearchIndex;
import com.vnsearch.query.ast.QueryNode;
import com.vnsearch.query.filter.CandidateFilter;
import com.vnsearch.query.filter.DomainFilter;
import com.vnsearch.query.filter.MaxCandidatesFilter;

import java.util.ArrayList;
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
     */
    public record ResolvedQuery(List<Integer> candidateDocIds, Map<String, Integer> queryTermFrequency) {
    }

    public static ResolvedQuery resolve(SearchIndex index, QueryParser.ParsedQuery parsed) {
        Map<String, Integer> queryTermFrequency = buildQueryTermFrequency(parsed);

        // --- Giai doan 1: truy hoi boolean bang cay bieu thuc (Composite) ---
        QueryNode ast = AST_BUILDER.buildAst(parsed);
        if (ast == null) {
            return new ResolvedQuery(new ArrayList<>(), queryTermFrequency);
        }
        List<Integer> candidates = ast.evaluate(index);

        // --- Giai doan 2: rang buoc sau truy hoi (Chain of Responsibility) ---
        // Rong la PHAN TU HAP THU cua moi phep loc, nen mot khi rong thi dung ngay.
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

        return new ResolvedQuery(candidates, queryTermFrequency);
    }

    /**
     * Tan suat moi term TRONG TRUY VAN, dung cho vector truy van cua scorer.
     *
     * <p>Gom ca term cua cum tu va cua nhom OR: neu bo sot, trong so truy van
     * se sai va scorer cham diem lech.
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
