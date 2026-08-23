package com.vnsearch.history;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Mot luot ghe tham mot trang web.
 *
 * <h2>Ba chi muc, ba muc dich khac nhau</h2>
 *
 * <pre>
 *   (username, visitedAt desc)  danh sach lich su theo ngay  -> man hinh chinh
 *   (username, url)             gop luot ghe lap lai         -> dem visitCount
 *   visitedAt TTL 90 ngay       tu xoa ban ghi qua han       -> quyen rieng tu
 * </pre>
 *
 * <p><b>Moi chi muc bat dau bang {@code username}</b>, va do la dieu bat buoc
 * chu khong phai toi uu. Mot truy van thieu dieu kien {@code username} se quet
 * lich su cua MOI NGUOI — vua cham, vua la mot lo hong ro ri du lieu giua cac
 * tai khoan. Dat username o vi tri dau chi muc khien mot truy van nhu the
 * khong dung duoc chi muc nao, va no lo ra ngay khi do do tre.
 *
 * <h2>Vi sao TTL 90 ngay</h2>
 *
 * <p>Khong phai con so tuy tien. Lich su duyet web la <b>du lieu ca nhan nhay
 * cam</b>: no noi ra noi lam viec, tinh trang suc khoe, quan diem chinh tri
 * cua mot nguoi. Giu vinh vien nghia la mot lan ro ri lam lo toan bo qua khu
 * cua ho. Nguyen tac toi thieu hoa du lieu noi rang chi giu chung nao con dung
 * toi — va khong ai mo lai lich su duyet web tu ba thang truoc.
 *
 * <p>Mongo tu xoa theo TTL, khong can job don dep. Voi SQL phai viet mot job
 * cron, va mot job cron bi quen chay se bien loi hua nay thanh loi noi suong.
 */
@Document(collection = "visits")
@CompoundIndex(name = "ix_visits_user_time", def = "{'username': 1, 'visitedAt': -1}")
@CompoundIndex(name = "ix_visits_user_url", def = "{'username': 1, 'url': 1}")
public record VisitDocument(
        @Id String id,

        /**
         * Chu so huu. Lay tu claim {@code sub} cua JWT, KHONG BAO GIO lay tu
         * tham so request — xem {@code CallerIdentity}.
         */
        String username,

        String url,
        String title,

        /** Host tach san de loc theo trang web ma khong phai phan tich URL moi lan. */
        String host,

        /**
         * TTL 90 ngay tinh tu truong nay.
         *
         * <p>{@code expireAfterSeconds} tren mot truong ngay thang la co che
         * TTL cua Mongo: no chay mot tien trinh nen moi 60 giay va xoa nhung
         * ban ghi co {@code visitedAt} cu hon nguong. Nghia la ban ghi co the
         * song them toi mot phut sau han — chap nhan duoc voi muc dich nay.
         */
        @Indexed(name = "ix_visits_ttl", expireAfter = "90d")
        Instant visitedAt,

        /** So lan ghe cung mot URL. Gop lai thay vi luu tung dong rieng. */
        int visitCount,

        /**
         * Luot ghe tu the AN DANH.
         *
         * <p>Truong nay LUON {@code false} trong CSDL, va do khong phai mau
         * thuan: che do an danh nghia la KHONG GUI gi len may chu ca. Truong
         * ton tai de hop dong API noi ro dieu do — mot ban ghi an danh loi vao
         * day la mot loi, va co truong nay thi loi do dem duoc.
         */
        boolean incognito) {
}
