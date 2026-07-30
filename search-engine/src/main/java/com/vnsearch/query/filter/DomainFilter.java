package com.vnsearch.query.filter;

import com.vnsearch.model.WebDocument;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gioi han ket qua trong mot domain (toan tu {@code site:vnexpress.net}).
 *
 * <p><b>Vi sao day la mot FILTER chu khong phai mot nut trong cay truy van.</b>
 * Cay bieu thuc ({@code QueryNode}) mo hinh hoa quan he BOOLEAN giua cac term —
 * no lam viec tren posting list. Nhung {@code site:} khong phai mot term: no la
 * mot rang buoc tren <i>sieu du lieu</i> cua tai lieu (URL), khong co posting
 * list nao tuong ung. Dua no vao cay se buoc phai dung mot chi muc phu
 * {@code host -> docIds}; con o day, voi vai chuc ung vien, kiem tra truc tiep
 * la du va don gian hon nhieu.
 *
 * <p>Day chinh la ranh gioi phan cong giua hai mau: <b>Composite</b> lo phan
 * truy hoi boolean, <b>Chain of Responsibility</b> lo cac rang buoc sau truy hoi.
 *
 * <p>Khop theo <b>hau to</b> nen {@code site:vnexpress.net} bat duoc ca
 * {@code vnexpress.net} lan {@code sport.vnexpress.net}.
 */
public final class DomainFilter implements CandidateFilter {

    @Override
    public boolean isApplicable(FilterContext context) {
        return context.parsed().siteFilter() != null;
    }

    @Override
    public List<Integer> apply(List<Integer> candidates, FilterContext context) {
        String wanted = context.parsed().siteFilter();
        List<Integer> filtered = new ArrayList<>(candidates.size());
        for (int docId : candidates) {
            WebDocument doc = context.index().getDocument(docId);
            if (doc == null) {
                continue;
            }
            String host = hostOf(doc.getUrl());
            if (host != null && (host.equals(wanted) || host.endsWith("." + wanted))) {
                filtered.add(docId);
            }
        }
        return filtered;
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String name() {
        return "site";
    }
}
