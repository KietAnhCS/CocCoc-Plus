package com.vnsearch.history;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.time.Instant;
import java.util.Optional;

/**
 * Truy cap collection {@code visits}.
 *
 * <h2>MOI phuong thuc deu nhan {@code username} lam tham so dau tien</h2>
 *
 * <p>Khong co ngoai le, va do la mot rang buoc co y. Mot phuong thuc kieu
 * {@code findById(String id)} nghe vo hai, nhung no cho phep bat ky ai doan
 * duoc id la doc duoc ban ghi cua nguoi khac — dung dinh nghia IDOR
 * (<i>A01:2021 — Broken Access Control</i>). Bat buoc co username o chu ky ham
 * khien loi do khong viet ra duoc.
 *
 * <p>Voi Spring Data, cach dat ten phuong thuc CHINH LA cau truy van. Nen quy
 * tac o day khong phai loi khuyen ma la mot bat bien kiem duoc bang mat khi
 * doc giao dien nay.
 */
public interface VisitRepository extends MongoRepository<VisitDocument, String> {

    /** Trang lich su theo thoi gian giam dan. */
    Page<VisitDocument> findByUsernameOrderByVisitedAtDesc(String username, Pageable pageable);

    /** Loc theo khoang thoi gian — man hinh "hom nay / hom qua / 7 ngay qua". */
    Page<VisitDocument> findByUsernameAndVisitedAtBetweenOrderByVisitedAtDesc(
            String username, Instant from, Instant to, Pageable pageable);

    /**
     * Tim trong lich su theo tu khoa, doi chieu voi CA tieu de lan dia chi.
     *
     * <p>Dung {@code @Query} thay vi dat ten phuong thuc: dieu kien "username
     * khop VA (title HOAC url chua tu khoa)" khong dien dat duoc bang ten
     * phuong thuc ma khong tao ra mot cai ten dai ba dong.
     *
     * <p>{@code $options: 'i'} de khong phan biet hoa thuong. Bieu thuc nay
     * KHONG neo dau dong nen no khong dung duoc chi muc — chap nhan duoc vi no
     * da bi thu hep truoc do boi dieu kien {@code username}, tuc chi quet lich
     * su cua dung mot nguoi.
     */
    @Query("{ 'username': ?0, $or: [ { 'title': { $regex: ?1, $options: 'i' } }, "
            + "{ 'url': { $regex: ?1, $options: 'i' } } ] }")
    Page<VisitDocument> searchByKeyword(String username, String keyword, Pageable pageable);

    /** Tim ban ghi cua CUNG mot URL de gop luot ghe thay vi them dong moi. */
    Optional<VisitDocument> findByUsernameAndUrl(String username, String url);

    /** Xoa mot muc — van doi username de khong ai xoa duoc muc cua nguoi khac. */
    long deleteByIdAndUsername(String id, String username);

    /** Xoa theo khoang thoi gian ("xoa 1 gio qua", "xoa toan bo"). */
    long deleteByUsernameAndVisitedAtBetween(String username, Instant from, Instant to);

    long countByUsername(String username);
}
