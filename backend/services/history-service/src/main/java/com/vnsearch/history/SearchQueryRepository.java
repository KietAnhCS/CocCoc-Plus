package com.vnsearch.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Truy cap collection {@code search_queries}. Xem {@link VisitRepository} ve quy tac username. */
public interface SearchQueryRepository extends MongoRepository<SearchQueryDocument, String> {

    Page<SearchQueryDocument> findByUsernameOrderBySearchedAtDesc(String username,
                                                                  Pageable pageable);

    /**
     * Goi y theo TIEN TO.
     *
     * <p>Dau {@code ^} khong phai chi tiet nho: no la thu duy nhat khien Mongo
     * dung duoc chi muc {@code ix_queries_user_prefix} cho mot bieu thuc chinh
     * quy. Bo no di thi cau truy van van tra ve dung ket qua — nhung bang cach
     * quet toan bo lich su tim kiem cua nguoi do, va khac biet chi lo ra khi
     * ai do da go vai nghin truy van.
     *
     * <p>Tham so {@code ?1} phai la tien to DA THOAT ky tu dac biet. Nguoi
     * dung go {@code .*} thi khong duoc phep bien no thanh mot bieu thuc chinh
     * quy khop moi thu — do la <i>ReDoS</i> va cung la mot dang injection. Viec
     * thoat ky tu lam o {@code HistoryService}, khong lam o day.
     */
    @Query("{ 'username': ?0, 'normalized': { $regex: ?1, $options: 'i' } }")
    List<SearchQueryDocument> goiYTheoTienTo(String username, String tienToNeoDau,
                                              Pageable pageable);

    /** Da go truy van nay chua — de cap nhat thoi diem thay vi them dong moi. */
    Optional<SearchQueryDocument> findByUsernameAndNormalized(String username, String normalized);

    long deleteByIdAndUsername(String id, String username);

    long deleteByUsernameAndSearchedAtBetween(String username, Instant from, Instant to);
}
