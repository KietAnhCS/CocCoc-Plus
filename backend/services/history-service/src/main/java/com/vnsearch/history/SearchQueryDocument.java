package com.vnsearch.history;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Mot truy van tim kiem ma nguoi dung da go.
 *
 * <h2>Vi sao tach khoi {@link VisitDocument}</h2>
 *
 * <p>Nhin qua thi ca hai deu la "mot viec nguoi dung da lam luc T". Nhung
 * chung duoc DOC theo hai cach hoan toan khac nhau:
 *
 * <ul>
 *   <li>Lich su ghe tham doc theo <b>thoi gian</b>: "hom qua toi vao trang
 *       nao". Truy van luon co khoang thoi gian.</li>
 *   <li>Lich su tim kiem doc theo <b>tien to</b>: nguoi dung go "ma" va o dia
 *       chi phai goi y ngay "may tinh" ma ho tim tuan truoc. Truy van khong co
 *       khoang thoi gian, va no phai trả loi trong vai chuc mili giay.</li>
 * </ul>
 *
 * <p>Gop chung mot collection nghia la moi lan goi y phai loc bo phan lon ban
 * ghi khong lien quan, va khong chi muc nao phuc vu tot ca hai kieu truy van.
 *
 * <h2>TTL ngan hon: 30 ngay</h2>
 *
 * <p>Ngan hon lich su ghe tham (90 ngay) vi gia tri cua no giam nhanh hon
 * nhieu: mot truy van tu ba thang truoc gan nhu khong bao gio duoc go lai, con
 * mot trang da ghe thi van co the can tim lai. Giu it hon la giu dung nguyen
 * tac toi thieu hoa du lieu.
 */
@Document(collection = "search_queries")
@CompoundIndex(name = "ix_queries_user_time", def = "{'username': 1, 'searchedAt': -1}")
/*
 * Chi muc cho GOI Y THEO TIEN TO.
 *
 * `normalized` la ban chu thuong, bo dau cach thua cua truy van. Chi muc tren
 * no cho phep Mongo dung phep quet dai (range scan) voi mot bieu thuc chinh quy
 * neo dau dong (/^tien-to/) — thu duy nhat trong regex ma Mongo dung duoc chi
 * muc. Mot regex KHONG neo dau dong (/tien-to/) phai quet toan bo collection,
 * va do la khac biet giua vai mili giay va vai giay.
 */
@CompoundIndex(name = "ix_queries_user_prefix", def = "{'username': 1, 'normalized': 1}")
public record SearchQueryDocument(
        @Id String id,
        String username,

        /** Nguyen van nhu nguoi dung go, de hien lai dung nhu vay. */
        String query,

        /** Ban chuan hoa de doi chieu tien to. Xem chu thich chi muc o tren. */
        String normalized,

        /** So ket qua tra ve. Bang 0 nghia la lan tim do that bai — huu ich khi
         *  danh gia chat luong may tim kiem. */
        int resultCount,

        @Indexed(name = "ix_queries_ttl", expireAfter = "30d")
        Instant searchedAt) {
}
