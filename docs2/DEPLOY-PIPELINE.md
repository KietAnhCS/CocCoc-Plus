Bản rút gọn dạng cây

Ba đường đưa mã tới chỗ chạy, ba mức tin cậy khác nhau. **Không có Kubernetes** —
đích triển khai là Docker Compose, và mọi thứ dưới đây được dựng quanh điều đó:

```
run-backend.bat          → jar + tự bật hạ tầng thiếu + tự mở giao diện  ← mặc định giờ ĐẦY ĐỦ
run-backend.bat --docker → Docker Compose, toàn hệ thống  ← ĐƯỜNG DEMO
.github/workflows/ci     → không triển khai, chỉ CHẶN     ← 7 job song song, mọi PR
.github/workflows/cd     → ghcr.io + docker-compose.release.yml
.github/workflows/release→ thẻ v*.*.* → ảnh đa kiến trúc + GitHub Release
```

```mermaid
flowchart LR
    A[commit] --> B[CI<br/>7 job song song]
    B -->|xanh trên main| C[CD<br/>9 ảnh: ký + quét CVE]
    C --> D[docker-compose.release.yml<br/>ghim theo digest]
    D --> E[máy đích<br/>docker compose up -d]
    A -.thẻ v*.*.*.-> F[Release<br/>amd64 + arm64, SBOM, cosign]
```

Đường 1 — máy thật:

```
run-backend.bat  [--core | --full | --build | --windows | --docker | --no-frontend]
├─ MẶC ĐỊNH MODE=full                            ← cả 9 service Java, --core mới rút gọn
│  ↳ đảo ngược so với bản cũ (mặc định từng là core); --core giờ mới là RÚT GỌN
├─ chcp 65001                                    ← log backend là tiếng Việt có dấu
│  ↳ CHÍNH tệp .bat phải là CRLF: với LF, cmd.exe cắt mất ký tự đầu mỗi dòng
│    ("REM" thành "M") — đã gặp thật, nay ghim bằng *.bat eol=crlf trong .gitattributes
├─ KHOÁ QUẢN TRỊ  ADMIN_API_KEY                                  ★ CỔNG BẮT BUỘC
│  ├─ biến môi trường phiên hiện tại  →  .env  →  sinh mới
│  │  └─ RandomNumberGenerator 32 byte, KHÔNG phải Get-Random (PRNG đoán được)
│  └─ độ dài < 16 → dừng NGAY (đúng ngưỡng ServiceSecurityConfig kiểm)
│     ↳ /api/admin/** điều khiển crawler và tải được URL tuỳ ý: chạy không khoá là lỗ SSRF
├─ BOOTSTRAP_ADMIN_PASSWORD tương tự → ghi vào .env, in ra một lần
├─ kiểm cổng 8080-8087 và 8090 còn trống không
├─ nạp .env bằng `if not defined` (biến sẵn có THẮNG tệp)  ← đúng thứ tự ưu tiên của compose
│  ↳ `java -jar` KHÔNG tự đọc .env như `docker compose`: thiếu bước này thì chạy bằng
│    Docker có tỉ số bóng đá, chạy bằng jar thì sampleOnly=true và ô tỉ số BIẾN MẤT
├─ ép mọi địa chỉ service về localhost + chuỗi kết nối CSDL
│  ↳ application.properties mặc định trỏ tên container `postgres`/`mongo`; thiếu phần ghi
│    đè này thì history/downloads/settings/football chết ngay vì UnknownHostException
├─ APP_CRAWLER_BUS: .env ghi `kafka` nhưng cổng 9092 trống → tự hạ về `memory`
│  ↳ trỏ vào hư không làm KafkaCrawlConfig ném lỗi lúc nạp bean, giết cả search lẫn crawler
├─ === HẠ TẦNG ===  tự dò cổng 5432/6379/27017, thiếu cái nào thì TỰ BẬT bằng Docker
│  ├─ chưa có lệnh `docker` → dừng, hướng dẫn cài Docker Desktop hoặc tự dựng 3 dịch vụ
│  ├─ có `docker` nhưng engine chưa chạy → dò đường cài Docker Desktop.exe, tự `start`
│  │  rồi vòng lặp `docker info` tới khi sẵn sàng, tối đa 180 giây
│  ├─ `docker compose up -d <danh sách còn thiếu>` rồi đợi healthcheck từng container
│  │  (`docker inspect .State.Health.Status`) tối đa 150 giây mỗi cái
│  │  ↳ KHÔNG dựng lại 9 service Java, chỉ 3 CSDL — 9 service vẫn chạy bằng jar
│  └─ khác bản cũ: trước đây bat KHÔNG tự bật hạ tầng, người dùng phải tự
│     `docker compose up -d postgres redis mongo` trước khi chạy
├─ chạy NGẦM bằng Start-Process, log ra backend\logs\<service>.log
│  ↳ `start /b` dùng chung console nên tiến trình con chết theo khi đóng cửa sổ;
│    --windows để mở lại 9 cửa sổ như cũ
└─ mặc định TỰ MỞ giao diện (`start ... run-frontend.bat` ở cửa sổ riêng) sau khi
   api-gateway trả lời khoẻ; `--no-frontend` tắt bước này

★ Đường jar giờ TỰ dựng hạ tầng (postgres/redis/mongo qua Docker, kể cả mở hộ Docker
  Desktop) — khác hẳn bản cũ vốn yêu cầu người dùng tự bật trước bằng tay.
```

Đồ hình Compose — 9 service Java, 3 CSDL, 3 thứ giám sát, 1 hồ sơ tuỳ chọn:

```
postgres  :17-alpine   5432   pg_isready   volume postgres-data   shared_buffers=128MB
│         init-db.sh → BA cơ sở dữ liệu riêng: vnsearch_auth, _downloads, _settings
redis     :7-alpine    6379   maxmemory 100mb, allkeys-lru, KHÔNG lưu bền
│         ↳ mất Redis = phải đăng nhập lại, không mất gì vĩnh viễn
mongo     :7          27017   wiredTigerCacheSizeGB=0.25
          ↳ mặc định WiredTiger lấy 50% RAM MÁY, không đọc giới hạn Docker → bị OOM kill

api-gateway      :8080  ← CỬA DUY NHẤT ra ngoài   384 MB
├─ auth-service      :8081   512 MB   BCrypt tốn CPU, không tốn heap
├─ search-service    :8082  3072 MB   ★ chỉ mục đảo + Trie + PageRank đều trong heap
├─ crawler-service   :8083  2048 MB   frontier + Bloom, dùng chung ./data với search
├─ analytics-service :8084   384 MB
├─ history-service   :8085   384 MB   → mongo
├─ downloads-service :8086   384 MB   → postgres
├─ settings-service  :8087   384 MB   → postgres
└─ football-service  :8090   384 MB   → postgres   (Java, KHÔNG còn là Go)
   ↳ 384 MB chứ không 64 MB như thời Go: một JVM cần chừng đó chỉ để khởi động.
     Đó là cái giá đã trả để cả hệ thống chỉ còn MỘT chuỗi công cụ.

prometheus :9090  giữ 7 ngày     grafana :3000 (admin/admin)     alertmanager :9093
kafka [profile kafka] :9092 KRaft  →  kafka-ui :8091   kafka-exporter :9308
   ↳ 8091 chứ KHÔNG phải 8090: 8090 là cổng của football-service BÊN TRONG mạng vnsearch.
     Dùng lại con số ấy cho thứ khác là mời người đọc mở localhost:8090 rồi kết luận sai
     về service nào đang trả lời.

Không còn hồ sơ `full` hay `observability`: MỘT lệnh `up` là lên toàn bộ, kể cả
Prometheus/Grafana/Alertmanager. Chỉ Kafka nằm sau hồ sơ riêng, vì bus mặc định là
`memory` và hệ thống chạy đủ chức năng mà không cần broker.
```

Đường 2 — MỘT Dockerfile cho CẢ CHÍN service:

```
backend/Dockerfile   tham số hoá bằng hai build arg
├─ ARG MODULE=services/<ten>      ARG ARTIFACT=<ten>
│  ↳ chín tệp riêng sẽ lệch nhau trong vài tháng: ai đó vá một lỗ hổng bảo mật ở một
│    tệp rồi quên tám tệp còn lại. Một quy trình, khác biệt gói gọn trong hai dòng ARG.
├─ GIAI ĐOẠN BUILD   maven:3.9-eclipse-temurin-17
│  ├─ COPY MỌI pom.xml TRƯỚC mã nguồn       ← tầng tải thư viện chỉ hỏng cache khi pom đổi
│  │  ↳ phải đủ CẢ 14 pom kể cả module không dựng tới: Maven đọc trọn <modules> của
│  │    POM cha trước khi biết nó cần dựng cái nào ("Child module does not exist")
│  ├─ --mount=type=cache,target=/root/.m2   ← giữ kho .m2 GIỮA các lần build
│  └─ mvn -pl ${MODULE} -am package -DskipTests -Djacoco.skip -Dspotbugs.skip
│     ↳ test/JaCoCo/SpotBugs là cổng chặn của CI; chạy lại trong container chỉ làm chậm
│       build mà không kiểm thêm gì — và với chín ảnh thì nó nhân lên chín lần
└─ GIAI ĐOẠN CHẠY    eclipse-temurin:17-jre   (~600 MB nhẹ hơn mỗi ảnh, nhân chín)
   ├─ useradd vnsearch, USER vnsearch  ← phục vụ HTTP không cần root
   ├─ COPY data/seed-documents.json    ← chạy được NGAY cả khi không mount gì
   └─ JAVA_TOOL_OPTIONS  MaxRAMPercentage=70  UseSerialGC  TieredStopAtLevel=1
      ↳ 70 chứ không 90: ngoài heap còn metaspace, ngăn xếp luồng, bộ đệm mã đã biên
        dịch, bộ nhớ ngoài heap của Netty. Đặt cao quá thì container bị OOM kill trong
        khi heap vẫn còn chỗ — kiểu hỏng không để lại dấu vết nào trong log Java.
      ↳ SerialGC vì dưới 512 MB thì G1 tốn ~30 MB cho cấu trúc quản lý vùng nhớ mà
        không đem lại lợi ích nào. search-service ghi đè sang G1 trong compose vì
        heap của nó lớn và thật sự cần.
```

Đường 3 — CI, bảy job song song, không job nào triển khai gì:

```
ci.yml   on: push main | pull_request      concurrency: cancel-in-progress = TRUE
├─ backend-test       JDK 21
│  ├─ ./mvnw -B clean verify -Dspotbugs.skip=true     (661 test + cổng JaCoCo)
│  └─ awk trên coverage/target/site/jacoco-aggregate/jacoco.csv → GITHUB_STEP_SUMMARY
│     ↳ phải là báo cáo GỘP của module `coverage`: trong reactor đa module,
│       backend/target/site/jacoco/ KHÔNG BAO GIỜ tồn tại. Trỏ sai chỗ thì bước này im
│       lặng in "không có báo cáo" ở MỌI lần chạy và không ai biết độ phủ chưa từng hiện.
├─ backend-static     ./mvnw -B -T 1C clean verify -DskipTests -Djacoco.skip=true
│  └─ upload-sarif spotbugs → tab Security        continue-on-error
│     ↳ tách khỏi test vì SpotBugs đọc BYTECODE, không cần bài test nào chạy. Thời gian
│       chờ của một PR bằng nhánh CHẬM NHẤT chứ không bằng tổng hai nhánh, và tên job
│       đỏ nói ngay hỏng thuộc loại nào.
│     ↳ -T 1C an toàn ở đây: không có test nên không module nào tranh nhau cổng/container
├─ frontend           Node 22   npm ci → typecheck → lint → vitest (155 test)
│  └─ ELECTRON_SKIP_BINARY_DOWNLOAD=1             ← CI không cần nhị phân Electron 100 MB
├─ image              build ảnh search-service (push: false, load: true)
│  └─ Trivy HIGH,CRITICAL → SARIF   continue-on-error   ← xem nguyên tắc 4
├─ data-integration   MA TRẬN 4 nhánh song song, fail-fast: false
│  └─ ∀ svc ∈ {downloads, football, history, settings}:
│       ./mvnw -B verify -Pdocker-it -pl services/<svc> -am
│     ↳ đúng bốn service này có @Tag("docker-it"). Testcontainers dựng Postgres/Mongo THẬT.
│     ↳ tên artifact phải kèm <svc>: upload-artifact@v4 từ chối hai artifact trùng tên
├─ kafka-integration  ./mvnw -B verify -Pkafka-it -pl libs/core-crawler -am
│  ↳ module DUY NHẤT có @Tag("kafka-it"). Hồ sơ đảo ngược bộ lọc nhóm test nên job này
│    mất khoảng một phút thay vì chạy lại toàn bộ bộ test đã chạy ở job kia.
└─ infrastructure     KHÔNG cần cụm nào
   ├─ promtool check config   (chạy bằng chính ảnh prom/prometheus:v2.55.1)
   │  ↳ mount vào ĐÚNG /etc/prometheus: `check config` đọc `rule_files:` bên trong
   │    prometheus.yml và đi theo đường dẫn TUYỆT ĐỐI của container thật. Mount vào
   │    /work thì đường dẫn ấy không tồn tại và bước này đỏ vì một lý do hoàn toàn giả.
   ├─ amtool check-config     (ảnh prom/alertmanager:v0.27.0)
   ├─ docker compose config --quiet   có và không có --profile kafka
   └─ ĐỐI CHIẾU MA TRẬN SERVICE                                       ★ CHẶN LỆCH
      docker compose config --format json → mọi service có khối `build:`
        ⟷ jobs.dung-anh.strategy.matrix.service    trong cd.yml
        ⟷ jobs.anh-docker.strategy.matrix.service  trong release.yml
      ↳ thêm một service vào compose mà quên hai ma trận thì cả hai workflow VẪN XANH,
        chỉ là service đó không bao giờ có ảnh — và điều đó chỉ lộ ra trên máy đích,
        lúc nó cố tự dựng từ mã nguồn không có ở đó.
```

Đường 4 — CD:

```
cd.yml   on: workflow_run [CI] completed, branches: main  |  workflow_dispatch
         concurrency: cd-<ref>, KHÔNG cancel-in-progress
         ↳ huỷ giữa chừng để lại nửa số ảnh đã ký và nửa chưa. Xếp hàng chờ mới đúng —
           ngược hẳn với CI nơi huỷ bản cũ là đúng.
│
├─ dung-anh   MA TRẬN 9 service, fail-fast: false
│  │          if: dispatch || workflow_run.conclusion == 'success'
│  │          ↳ thiếu điều kiện này thì CI ĐỎ vẫn kích hoạt CD
│  ├─ kho = ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/search-engine     ← hạ chữ thường MỘT lần
│  │  ↳ chủ kho là `KietAnhCS`, Docker bắt buộc tên kho viết thường. Bốn chỗ tự hạ
│  │    riêng là bốn cơ hội để một chỗ bị quên.
│  ├─ thẻ = inputs.version | sha-<12 ký tự đầu>
│  │  ↳ giá trị người gõ đi qua `env:` chứ không dán thẳng vào `run:`: dán thẳng thì
│  │    Actions thay chuỗi vào mã shell TRƯỚC khi shell chạy, và một thẻ tên
│  │    `a; rm -rf /` trở thành hai câu lệnh
│  ├─ build-push  sbom: true, provenance: mode=max   cache scope theo từng service
│  ├─ cosign sign --yes kho@DIGEST   ← keyless, danh tính từ OIDC, không khoá nào phải giữ
│  └─ Trivy CRITICAL  exit-code: 1                                  ★ CHẶN THẬT
│
└─ ban-phat-hanh   needs dung-anh
   ├─ hỏi THẲNG docker-compose.yml service nào cần ảnh (jq: có `build` thì có)
   │  ↳ viết tay lần thứ hai nghĩa là ngày nào đó thêm một service mà quên, và lớp phủ
   │    sẽ im lặng bỏ sót đúng service đó
   ├─ crane digest ∀ service → sinh docker-compose.release.yml
   │  └─ image: kho/<svc>@sha256:...   +   pull_policy: always
   │     ↳ lớp phủ KHÔNG xoá được khối `build:` của tệp gốc — phép ghép của Compose là
   │       hợp nhất, không phải thay thế. Service có CẢ image lẫn build thì Compose mặc
   │       định DỰNG khi ảnh chưa có sẵn trên máy: trên máy đích thường không có mã
   │       nguồn, và tệ hơn, nó vứt bỏ đúng cái ảnh vừa được quét CVE và ký.
   ├─ kiểm tại chỗ: docker compose -f gốc -f lớp phủ config --quiet
   └─ upload-artifact docker-compose-release, giữ 30 ngày

Máy đích chỉ cần:
   docker compose -f docker-compose.yml -f docker-compose.release.yml up -d
```

Đường phát hành theo thẻ phiên bản:

```
release.yml   on: push tags v*.*.*  |  workflow_dispatch
├─ kiem-tra        ./mvnw -B clean verify -Dspotbugs.skip=true → upload jar
├─ phan-tich-tinh  ./mvnw -B -T 1C clean verify -DskipTests -Djacoco.skip=true
│  ↳ hai job SONG SONG: đường găng trước matrix dựng ảnh là nhánh chậm hơn, không phải
│    tổng của hai nhánh
│  ↳ vì sao chạy lại dù CI đã xanh trên main: thẻ có thể đặt lên MỘT COMMIT BẤT KỲ,
│    kể cả commit cũ chưa từng qua CI, kể cả commit trên nhánh khác
├─ anh-docker  needs [kiem-tra, phan-tich-tinh]   MA TRẬN 9 service
│  ├─ QEMU + buildx → platforms: linux/amd64, linux/arm64
│  ├─ metadata-action → {{version}} | {{major}}.{{minor}} | {{major}} | latest | sha-long
│  ├─ cosign sign ∀ thẻ (đều trỏ về CÙNG một digest)
│  └─ Trivy CRITICAL exit-code 1
└─ ban-phat-hanh  if: ref bắt đầu bằng refs/tags/
   └─ gh-release: generate_release_notes, đính jar, kèm sẵn lệnh `cosign verify`
      ↳ ghi chú phát hành sinh THẲNG từ tiêu đề commit — bốn commit tên "minor" sẽ
        thành bốn dòng vô nghĩa trong tài liệu phát hành
```

Giám sát:

```
prometheus.yml   scrape 15s, giữ 7 ngày
├─ job vnsearch-backend          8 service, label component=backend
├─ job vnsearch-crawler-worker   crawler-service:8083 TÁCH RIÊNG
│  ↳ một cái phục vụ truy vấn, một cái chạy vòng lặp thu thập. Trộn chúng vào một job
│    làm mọi phép tổng hợp trở nên vô nghĩa.
├─ job kafka       → kafka-exporter:9308
└─ job prometheus  → chính nó

7 quy tắc, `for:` là khoảng thời gian điều kiện phải đúng LIÊN TỤC — không phải trang trí:
  VnSearchBackendDown           up == 0                                 2m
  VnSearchEmptyIndex            vnsearch_index_documents == 0          10m
  VnSearchSearchLatencyHigh     phân vị độ trễ tìm kiếm                 5m
  VnSearchHighErrorRate         tỷ lệ 5xx                               5m
  VnSearchCrawlBusFailing       increase(publish_failures[5m]) > 0      5m
  VnSearchKafkaConsumerLagHigh  sum(consumergroup_lag) > 10000         10m
  VnSearchDeadLetterGrowing     increase(offset trên topic *.DLT) > 10 15m

alertmanager: group_wait 30s, repeat 4h (critical 1h)
  inhibit: critical đang kêu thì NÉN warning cùng component/instance
  receiver `chi-ghi-log` → webhook trỏ vào chỗ không tồn tại  ← cố ý, không có Slack thật
```

Năm nguyên tắc chạy suốt tầng triển khai:

```
1. Cổng chặn phải BỎ QUA, không được THẤT BẠI
   ✗ vi phạm: một job đỏ thường trực → sau vài tuần không ai nhìn màu đỏ nữa, kể cả
     lần đỏ THẬT. Mọi cổng chặn đều bị vô hiệu hoá theo đúng cách đó.

2. Cùng một danh sách viết hai lần thì phải có cổng ĐỐI CHIẾU
   → ma trận service của cd.yml và release.yml ⟷ docker-compose.yml
   ✗ vi phạm: bản ít dùng hơn lặng lẽ mục ra, tới lúc cần thì nó chưa từng chạy

3. Ghim theo DIGEST ở mọi nơi chạm vào máy đích
   → thẻ là con trỏ DI ĐỘNG: hai máy kéo ảnh ở hai thời điểm có thể chạy hai bản mã
     khác nhau dưới cùng một tên. Digest là băm của chính nội dung ảnh, và là thứ
     cosign đã ký.

4. Trivy CHẶN ở CD/Release, KHÔNG chặn ở CI  (mâu thuẫn có chủ ý)
   → CI: ảnh nền gần như luôn có vài CVE chưa vá — chặn ở đây là tự vô hiệu hoá theo
     điều 1, và cổng sẽ bị tắt trong vòng một tuần
   → CD: ranh giới cuối trước khi ảnh rời khỏi kho — chặn ở đây là đúng chỗ
   ✗ vi phạm cả hai chiều: chặn ở CI thì cổng chết; không chặn ở CD thì cổng vô nghĩa

5. Tên job là MỘT PHẦN của branch protection
   → required status checks trỏ theo TÊN job. Đổi tên trong ci.yml mà quên cập nhật
     danh sách thì check cũ KHÔNG BAO GIỜ báo cáo nữa, và mọi PR đứng ở BLOCKED vĩnh
     viễn dù toàn bộ đều xanh — một kiểu hỏng không có dòng lỗi nào.
   ✗ đã xảy ra thật khi tách `backend` thành backend-test/backend-static và matrix hoá
     `data-integration`: 3 trong 8 check bắt buộc trỏ vào những tên đã biến mất.
```
