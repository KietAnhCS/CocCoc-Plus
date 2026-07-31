# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Giai doan 1 — BIEN DICH
#
# Dung image co san Maven thay vi Maven Wrapper: wrapper ton tai de nguoi dung
# khong phai cai Maven tren may that, con trong container thi Maven da co san,
# va goi wrapper se phai tai them mot ban Maven nua vao moi lan build.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Chep RIENG pom.xml truoc phan ma nguon de tan dung cache tang cua Docker:
# chung nao pom khong doi thi tang tai thu vien duoi day duoc dung lai, du ma
# nguon co doi bao nhieu lan. Chep ca thu muc cung luc se lam tang nay mat hieu
# luc moi lan sua mot dong Java.
COPY pom.xml .

# Lam nong kho thu vien. `|| true` la co y: day thuan tuy la mot buoc TOI UU
# CACHE, va co nhung plugin ma `go-offline` khong giai duoc phu thuoc dung cach.
# Neu buoc nay hong, `mvn package` ben duoi van tai lai binh thuong — hong o day
# khong duoc phep lam hong ca ban build.
RUN mvn -B -q dependency:go-offline || true

COPY src ./src

# Bo test khi dong goi anh: bo test da duoc chay o ban `mvnw test` tren may
# that (280 test). Chay lai trong container chi lam cham build ma khong kiem
# them duoc dieu gi moi.
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Giai doan 2 — CHAY
#
# Chi mang theo JRE va file jar: anh cuoi khong chua Maven, khong chua ma nguon,
# khong chua kho thu vien (~600 MB tiet kiem duoc).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

WORKDIR /app

# Khong chay bang root: mot tien trinh phuc vu HTTP khong can quyen root, va
# neu co lo hong thi thiet hai bi gioi han trong quyen cua user nay.
RUN useradd --create-home --shell /bin/false vnsearch

COPY --from=build /build/target/*.jar app.jar

# Corpus mau di kem repo, de container chay duoc NGAY ca khi khong mount gi.
# Trong docker-compose, thu muc data cua may that duoc mount de len cho nay nen
# corpus lon (crawled-multi.json) va file chi muc dung san se duoc dung thay.
COPY data/seed-documents.json data/seed-documents.json
RUN chown -R vnsearch:vnsearch /app

USER vnsearch
EXPOSE 8080

# MaxRAMPercentage thay vi -Xmx co dinh: heap tu co gian theo gioi han bo nho
# ma Docker cap cho container, nen khong phai sua Dockerfile khi doi may.
# Chi muc dao nam HOAN TOAN trong bo nho (5.011 trang ~ 136.768 term), nen day
# la tham so dang de y neu doi sang corpus lon hon.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
