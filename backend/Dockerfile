# syntax=docker/dockerfile:1

# ===========================================================================
# MỘT Dockerfile cho CẢ TÁM service Java.
# ===========================================================================
#
# VÌ SAO KHÔNG PHẢI TÁM TỆP RIÊNG. Tám bản sao của cùng một quy trình đóng gói
# sẽ lệch nhau trong vòng vài tháng: ai đó vá một lỗ hổng bảo mật ở một tệp và
# quên bảy tệp còn lại, hoặc nâng phiên bản JRE ở sáu chỗ. Tham số hoá bằng
# build arg giữ đúng MỘT quy trình, và khác biệt duy nhất giữa các service —
# tên module và cổng — nằm gọn ở hai dòng ARG.
#
# CÁCH DÙNG:
#     docker build -f backend/Dockerfile \
#       --build-arg MODULE=services/search-service \
#       --build-arg ARTIFACT=search-service \
#       -t vnsearch/search-service backend/
#
# docker-compose truyền hai tham số này cho từng service — xem docker-compose.yml.

# ---------------------------------------------------------------------------
# Giai đoạn 1 — BIÊN DỊCH
#
# Dùng image Maven có sẵn thay vì Maven Wrapper: wrapper tồn tại để người dùng
# không phải cài Maven trên máy thật, còn trong container thì Maven đã có sẵn,
# và gọi wrapper sẽ phải tải thêm một bản Maven nữa vào mỗi lần build.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

ARG MODULE=services/search-service

WORKDIR /build

# Chép RIÊNG các tệp pom trước phần mã nguồn để tận dụng cache tầng của Docker:
# chừng nào pom không đổi thì tầng tải thư viện dưới đây được dùng lại, dù mã
# nguồn có đổi bao nhiêu lần. Chép cả cây thư mục cùng lúc sẽ làm tầng này mất
# hiệu lực mỗi lần sửa một dòng Java.
#
# Với reactor đa module thì phải chép ĐỦ MỌI pom, kể cả pom của module không
# dựng tới: Maven đọc toàn bộ danh sách <modules> của POM cha trước khi biết
# nó cần dựng cái nào. Thiếu một tệp là lỗi "Child module does not exist".
COPY pom.xml .
COPY libs/core-common/pom.xml libs/core-common/
COPY libs/core-crawler/pom.xml libs/core-crawler/
COPY libs/core-search/pom.xml libs/core-search/
COPY libs/platform/pom.xml libs/platform/
COPY services/api-gateway/pom.xml services/api-gateway/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/search-service/pom.xml services/search-service/
COPY services/crawler-service/pom.xml services/crawler-service/
COPY services/analytics-service/pom.xml services/analytics-service/
COPY coverage/pom.xml coverage/

# Làm nóng kho thư viện. `|| true` là có ý: đây thuần tuý là một bước TỐI ƯU
# CACHE, và có những plugin mà `go-offline` không giải được phụ thuộc đúng cách.
# Nếu bước này hỏng, `mvn package` bên dưới vẫn tải lại bình thường — hỏng ở
# đây không được phép làm hỏng cả bản build.
#
# --mount=type=cache giữ kho ~/.m2 GIỮA CÁC LẦN BUILD, kể cả khi tầng phía trên
# mất hiệu lực. Đây là khác biệt giữa một lần build lại mất 30 giây và một lần
# mất 4 phút.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am dependency:go-offline || true

COPY libs libs
COPY services services
COPY coverage coverage
COPY spotbugs-exclude.xml .

# `-pl ${MODULE} -am` = dựng module này VÀ những module nó phụ thuộc vào, bỏ
# qua phần còn lại. Với search-service nghĩa là core + platform + chính nó,
# không đụng tới bảy service kia.
#
# Bỏ test khi đóng gói ảnh: bộ test đã chạy ở `mvnw verify` trên máy thật và
# trong CI. Chạy lại trong container chỉ làm chậm build mà không kiểm thêm được
# điều gì mới — và với tám ảnh thì nó nhân lên tám lần.
#
# Bỏ luôn JaCoCo và SpotBugs: cả hai là cổng chặn chất lượng, thuộc về CI, và
# chúng cần bộ test chạy để có ý nghĩa.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${MODULE} -am clean package \
        -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true

# ---------------------------------------------------------------------------
# Giai đoạn 2 — CHẠY
#
# Chỉ mang theo JRE và tệp jar: ảnh cuối không chứa Maven, không chứa mã nguồn,
# không chứa kho thư viện (~600 MB tiết kiệm được mỗi ảnh, nhân tám).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre

ARG MODULE=services/search-service
ARG ARTIFACT=search-service

WORKDIR /app

# Không chạy bằng root: một tiến trình phục vụ HTTP không cần quyền root, và
# nếu có lỗ hổng thì thiệt hại bị giới hạn trong quyền của user này. Đây là
# biện pháp chống leo thang đặc quyền rẻ nhất và hiệu quả nhất trong container.
RUN useradd --create-home --shell /bin/false vnsearch

COPY --from=build /build/${MODULE}/target/${ARTIFACT}-*.jar app.jar

# Corpus mẫu đi kèm repo, để container chạy được NGAY cả khi không mount gì.
# Trong docker-compose, thư mục data của máy thật được mount đè lên chỗ này nên
# corpus lớn và tệp chỉ mục dựng sẵn sẽ được dùng thay.
COPY data/seed-documents.json data/seed-documents.json
RUN chown -R vnsearch:vnsearch /app

USER vnsearch

# ---------------------------------------------------------------------------
# THAM SỐ JVM — phần quan trọng nhất của tệp này với một máy 8 GB.
# ---------------------------------------------------------------------------
#
# MaxRAMPercentage=70 thay vì -Xmx cố định: heap tự co giãn theo giới hạn bộ
# nhớ mà Docker cấp cho container, nên một Dockerfile chạy đúng cho cả
# search-service (1536 MB) lẫn settings-service (384 MB).
#
# Vì sao 70 chứ không phải 75 hay 90: heap KHÔNG phải toàn bộ bộ nhớ một tiến
# trình JVM dùng. Ngoài nó còn metaspace, ngăn xếp luồng, bộ đệm mã đã biên
# dịch, và bộ nhớ ngoài heap của Netty. Đặt quá cao thì container bị hệ điều
# hành giết vì OOM trong khi heap vẫn còn chỗ — một kiểu hỏng đặc biệt khó
# chẩn đoán, vì log Java không để lại dấu vết nào.
#
# UseSerialGC chứ không G1: dưới 512 MB, G1 tốn khoảng 30 MB cho cấu trúc quản
# lý vùng nhớ và không đem lại lợi ích nào — nó được thiết kế cho heap lớn,
# nhiều nhân. Với các service nhỏ, SerialGC nhanh hơn khi khởi động và tốn ít
# hơn hẳn. search-service ghi đè tham số này trong docker-compose vì nó có heap
# lớn và thật sự cần G1.
#
# TieredStopAtLevel=1 dừng ở tầng biên dịch C1: khởi động nhanh hơn khoảng 30%,
# đổi lấy thông lượng đỉnh thấp hơn. Đúng cho các service CRUD nhỏ; SAI cho
# search-service, và nó cũng ghi đè tham số này.
#
# ExitOnOutOfMemoryError: hết bộ nhớ thì CHẾT HẲN để Docker khởi động lại. Mặc
# định của JVM là cố sống tiếp sau OOM, và một tiến trình thoi thóp còn tệ hơn
# một tiến trình đã chết — nó vẫn qua được healthcheck TCP, vẫn nhận request,
# và trả lỗi cho từng người một.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k \
-XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/./urandom"

# Cổng thật do biến SERVER_PORT quyết định (xem application.properties của từng
# service). EXPOSE ở đây chỉ là tài liệu — nó không mở cổng nào cả.
EXPOSE 8080-8090

ENTRYPOINT ["java", "-jar", "app.jar"]
