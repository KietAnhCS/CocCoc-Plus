Bản rút gọn dạng cây

Bốn đường đưa mã tới chỗ chạy, bốn mức tin cậy khác nhau:

```
run-backend.bat        → Docker Compose trên máy thật    ← ĐƯỜNG MẶC ĐỊNH, demo được ngay
deploy/kind/up.sh      → cụm Kubernetes trong Docker     ← thử manifest thật, dùng ảnh :dev
.github/workflows/ci   → không triển khai, chỉ CHẶN      ← 5 job, chạy mọi PR
.github/workflows/cd   → ghcr.io → cụm thật              ← ĐANG TẮT bằng cổng CD_TRIEN_KHAI_THAT
```

Đường 1 — máy thật, một lần bấm:

```
run-backend.bat  [--full | --kafka | --core | --no-football | --no-build | --logs]
├─ chcp 65001                                    ← log backend là tiếng Việt có dấu
│  ↳ CHÍNH tệp .bat thì viết KHÔNG dấu: cmd.exe đọc theo byte offset, ký tự đa byte
│    làm lệch con trỏ và cắt vụn các dòng lệnh phía sau (kể cả đã chcp, kể cả có BOM)
├─ đọc tham số → MODE / PROFILES / BUS
│  ├─ --full  → --profile kafka --profile monitoring   9 container  ~4 GB
│  ├─ --kafka → --profile kafka                        ~3 GB
│  └─ --core  → không profile nào                      ~1,5 GB
│  ↳ profile `football` gán SAU vòng lặp, không gán trong nhánh: `--core` viết đè cả
│    PROFILES, nên gán trong nhánh thì thứ tự người gõ sẽ đổi kết quả
├─ kiểm tra Docker THEO BA BƯỚC KHÁC NHAU
│  ├─ where docker           → có CLI không
│  ├─ docker compose version → có plugin compose v2 không   (hỏi CLI, engine tắt vẫn trả lời)
│  └─ docker info            → engine CÓ ĐANG CHẠY không    (phải nói chuyện được với daemon)
│     └─ chưa chạy → start Docker Desktop.exe, ping-poll 3 giây/lần, bỏ cuộc ở 240 s
├─ KHOÁ QUẢN TRỊ  ADMIN_API_KEY                                  ★ CỔNG BẮT BUỘC
│  ├─ biến môi trường phiên hiện tại  →  .env  →  sinh mới
│  │  └─ RandomNumberGenerator 32 byte, KHÔNG phải Get-Random (PRNG đoán được)
│  │     → ghi vào .env để lần sau không đổi khoá (đổi khoá = mọi lệnh curl trong tài liệu 401)
│  ├─ CHỈ lấy đúng khoá cần, KHÔNG nạp cả .env vào môi trường
│  │  ↳ các giá trị còn lại viết cho MẠNG NỘI BỘ compose (host `postgres`, `kafka`) — vô nghĩa ở terminal
│  └─ độ dài < 16 → dừng NGAY  (đúng ngưỡng SecurityConfig kiểm, bắt trước khi mất vài phút build)
│     ↳ docker-compose.yml khai ADMIN_API_KEY dạng `${...:?}` nên compose cũng tự dừng — hai lớp
│     ↳ /api/admin/** điều khiển crawler và tải được URL tuỳ ý: chạy không khoá là một lỗ SSRF hoàn chỉnh
├─ APP_CRAWLER_BUS đặt TƯỜNG MINH trong phiên                    ★ ĐÈ LÊN .env
│  ├─ full/kafka → `kafka`   ✗ để `memory`: hạ tầng lên đủ, topic RỖNG MÃI MÃI, KHÔNG một dòng log lỗi
│  └─ --core     → `memory`  ✗ để `kafka` : backend fatalIfBrokerNotAvailable → TỪ CHỐI khởi động
├─ CẢNH BÁO chỉ mục cũ hơn corpus
│  └─ index.json.LastWriteTime < crawled-documents.json.LastWriteTime → in lệnh reindex
│     ↳ SearchEngineFacade ưu tiên index.json; lệch thì "crawl 5.000 trang mà tìm gì cũng không ra"
├─ DỌN container mắc kẹt ở mạng Docker đã bị xoá
│  └─ ∀ container: docker inspect → NetworkID → docker network inspect  (phép thử THẬT, hỏi daemon)
│     └─ mạng chết → docker rm -f          ← KHÔNG đụng volume, CSDL còn nguyên
│     ↳ Docker Desktop khởi động lại giữa hai phiên sẽ tạo mạng cùng TÊN nhưng khác ID;
│       container cũ ghim ID cũ → "network <id> not found", nhìn như lỗi riêng của kafka
├─ docker compose %PROFILES% up -d [--build]
└─ CHỜ backend THẬT SỰ sẵn sàng
   └─ docker inspect -f {{.State.Health.Status}} vnsearch-backend, 5 giây/lần, trần 420 s
      ↳ `up -d` trả về khi container ĐÃ TẠO, không phải khi phục vụ được — backend còn
        nạp corpus 384 MB và lập chỉ mục
```

Đồ hình Compose — ai phụ thuộc ai, và ai thuộc profile nào:

```
postgres     :17-alpine   luôn bật   5432   pg_isready         volume postgres-data
│                                           schema.sql mount vào /docker-entrypoint-initdb.d
├─ backend            build ./search-engine  8080  wget /api/health  start_period 90s
│  │                  mount ./search-engine/data → /app/data   ← corpus + chỉ mục nằm TRÊN MÁY THẬT
│  └─ depends_on kafka: required=false   ← chế độ --core không có kafka mà backend vẫn lên
├─ crawler-worker     [kafka]   APP_CRAWLER_ROLE=worker, ROLE_IS_API=false
└─ football-service   [football] build ./football-service  8090  --health-check  ~30 MB
                      ↳ thiếu FOOTBALL_API_KEY vẫn LÊN, chỉ chạy dữ liệu mẫu (in ra ở dòng trạng thái)

kafka  apache/kafka:3.8.1  [kafka, monitoring]   KRaft, node 1 vừa broker vừa controller
├─ 3 listener: PLAINTEXT :9092 (nội bộ) | CONTROLLER :9093 | PLAINTEXT_HOST :29092 (ra máy thật)
├─ AUTO_CREATE_TOPICS_ENABLE=false     ← gõ sai tên topic phải BÁO LỖI, không tự tạo topic ma
├─ NUM_PARTITIONS 12                   ← trần song song của crawler-worker
└─ kafka-ui  :8081  [kafka, monitoring]

prometheus :9090  [monitoring]  scrape 15s, giữ 15 ngày
├─ grafana      :3000  [monitoring]  dashboard vnsearch.json nạp sẵn làm trang chủ
├─ alertmanager :9093  [monitoring]  receiver `chi-ghi-log` — webhook trỏ vào chỗ KHÔNG tồn tại
│                                    ↳ cố ý: đồ án không có Slack/PagerDuty thật để gửi
└─ kafka-exporter :9308 [monitoring]  → kafka_consumergroup_lag
```

Đường 2 — dựng ảnh (Dockerfile hai giai đoạn):

```
search-engine/Dockerfile
├─ GIAI ĐOẠN BUILD   maven:3.9-eclipse-temurin-17
│  ├─ COPY pom.xml TRƯỚC src            ← tầng tải thư viện chỉ hỏng cache khi pom đổi
│  ├─ mvn dependency:go-offline || true ← thuần tuý TỐI ƯU CACHE, hỏng không được làm hỏng build
│  └─ mvn clean package -DskipTests     ← test đã chạy ở CI (280 test), chạy lại chỉ tốn thời gian
└─ GIAI ĐOẠN CHẠY    eclipse-temurin:17-jre           (~600 MB nhẹ hơn: bỏ Maven, mã nguồn, kho .m2)
   ├─ useradd vnsearch, USER vnsearch   ← phục vụ HTTP không cần root
   ├─ COPY data/seed-documents.json     ← chạy được NGAY cả khi không mount gì
   └─ ENTRYPOINT java -XX:MaxRAMPercentage=75 -jar app.jar
      ↳ theo phần trăm chứ không -Xmx cố định: heap co giãn theo giới hạn Docker cấp
      ↳ chỉ mục đảo nằm HOÀN TOÀN trong bộ nhớ → đây là tham số đáng để ý khi đổi corpus

football-service/Dockerfile
├─ golang:1.24-alpine
│  ├─ go vet && go test   ← chạy TRONG ảnh, vì máy phát triển không cài Go
│  └─ CGO_ENABLED=0 -trimpath -ldflags="-s -w"
│     ↳ CGO=0 là BẮT BUỘC: distroless/static không có libc. Thiếu cờ này ảnh vẫn build,
│       vẫn chạy trên alpine, rồi chết ở distroless với "no such file or directory"
│       trỏ vào chính tệp nhị phân đang tồn tại
└─ gcr.io/distroless/static-debian12:nonroot   ~10 MB, không shell, không package manager
   ├─ USER nonroot (UID 65532)
   └─ HEALTHCHECK ["/football-service", "--health-check"]   ← tự gọi chính mình
```

Đường 3 — CI, năm job song song, không job nào triển khai gì:

```
ci.yml   on: push main | pull_request      concurrency: cancel-in-progress = TRUE
├─ backend            JDK 21
│  ├─ ./mvnw -B clean verify        (test + JaCoCo + SpotBugs)
│  ├─ awk trên jacoco.csv → bảng độ phủ vào GITHUB_STEP_SUMMARY
│  └─ upload-sarif spotbugs → tab Security         continue-on-error
├─ frontend           Node 22
│  └─ npm ci → typecheck → lint → vitest
│     env ELECTRON_SKIP_BINARY_DOWNLOAD=1          ← CI không cần nhị phân Electron 100 MB
├─ image
│  ├─ diff -u  src/main/resources/db/schema.sql  ⟷  deploy/k8s/base/schema.sql   ★ CHẶN LỆCH
│  │  ↳ hai tệp phải GIỐNG HỆT: compose mount tệp thứ nhất, k8s đóng tệp thứ hai vào ConfigMap
│  ├─ build ảnh (push: false, load: true)
│  └─ Trivy HIGH,CRITICAL → SARIF        continue-on-error  ← xem nguyên tắc 5 bên dưới
├─ kafka-integration  ./mvnw verify -Pkafka-it     (Testcontainers dựng broker thật)
└─ infrastructure     KHÔNG cần cụm nào
   ├─ kubectl kustomize base | overlays/dev | overlays/prod | monitoring   ← dựng được không
   ├─ kubeconform -strict -kubernetes-version 1.30.0                       ← đúng lược đồ không
   ├─ promtool check config      (chạy bằng chính ảnh prom/prometheus:v2.55.1)
   ├─ amtool check-config        (chạy bằng chính ảnh prom/alertmanager:v0.27.0)
   ├─ docker compose config --quiet  ở CẢ BA mức profile
   └─ ĐỐI CHIẾU HAI BẢN CẢNH BÁO                                           ★ CHẶN LỆCH
      grep tên alert trong deploy/monitoring/alerts.yml
        → ∀ tên: phải có trong deploy/k8s/monitoring/prometheusrule.yaml, thiếu là ĐỎ
      ↳ cùng bộ quy tắc viết hai lần cho hai môi trường; không có cổng này thì chúng
        lệch nhau trong im lặng và bản k8s trở thành thứ chưa ai từng chạy
```

Đường 4 — CD, và cái cổng đang khoá nó:

```
cd.yml   on: workflow_run [CI] completed, branches: main   |   workflow_dispatch
         concurrency: cd-<môi trường>, KHÔNG cancel-in-progress
         ↳ huỷ giữa chừng để lại cụm nửa vời: vài Pod bản mới, vài Pod bản cũ.
           Xếp hàng chờ mới đúng — ngược hẳn với CI nơi huỷ bản cũ là đúng.
│
├─ dung-anh          if: dispatch || workflow_run.conclusion == 'success'
│  │                 ↳ thiếu điều kiện này thì CI ĐỎ vẫn kích hoạt CD
│  ├─ kho = ghcr.io/<chủ kho ĐÃ HẠ CHỮ THƯỜNG>/search-engine        ← tính MỘT lần
│  │  ↳ chủ kho là `KietAnhCS`, Docker bắt buộc tên kho viết thường:
│  │    "repository name must be lowercase". Bốn chỗ tự hạ riêng là bốn cơ hội quên một chỗ.
│  ├─ thẻ = input.phien-ban  |  sha-<12 ký tự đầu của SHA>
│  ├─ login ghcr bằng GITHUB_TOKEN           ← KHÔNG phải PAT: token chỉ sống trong lần chạy này
│  ├─ build-push  sbom: true, provenance: mode=max
│  │  ↳ CVE tiếp theo được công bố, câu hỏi đầu tiên là "ta có dùng thư viện đó không"
│  ├─ cosign sign --yes  kho@DIGEST          ← keyless, danh tính từ OIDC, KHÔNG có khoá phải giữ
│  └─ Trivy CRITICAL  exit-code: 1           ★ CHẶN THẬT
│
├─ trien-khai        if: vars.CD_TRIEN_KHAI_THAT == 'true'          ★ CỔNG, hiện CHƯA BẬT
│  │  ↳ dùng `vars` chứ không `secrets`: ngữ cảnh secrets KHÔNG đọc được ở `if` cấp job,
│  │    mà muốn đọc secret theo môi trường thì job buộc phải khai `environment:` — và
│  │    chính việc khai đó SINH RA bản ghi Deployment trước khi bước nào chạy.
│  │    Mọi cách kiểm tra từ BÊN TRONG job đều đến quá muộn.
│  │  ↳ bỏ qua ≠ thất bại: một job đỏ thường trực dạy cả nhóm bỏ qua màu đỏ
│  ├─ environment: staging | production      ← chỗ đặt "Required reviewers" cho production
│  ├─ nạp kubeconfig, THIẾU thì báo lỗi có hướng dẫn   ← lưới an toàn lớp hai
│  │  ↳ bắt trường hợp hẹp hơn: biến đã bật, staging đã có KUBE_CONFIG, production thì chưa
│  ├─ GHIM ẢNH THEO DIGEST, không theo thẻ
│  │  ↳ thẻ là con trỏ DI ĐỘNG: hai node kéo ảnh ở hai thời điểm có thể chạy hai bản mã
│  │    khác nhau dưới cùng một tên. Digest là băm của chính nội dung ảnh.
│  ├─ kubectl apply -k … --dry-run=server    ← bắt CRD thiếu / quota / webhook TRÊN CỤM THẬT
│  ├─ kubectl apply -k …
│  ├─ rollout status  backend & crawler-worker  --timeout=5m
│  │  ↳ không có bước này thì workflow xanh ngay sau `apply` trong khi Pod đang CrashLoop.
│  │    "Đã triển khai" phải nghĩa là "đang chạy được", không phải "đã gửi lệnh đi".
│  ├─ if failure() → kubectl rollout undo    ★ TỰ QUAY LUI
│  └─ kiểm tra sau triển khai: Pod curlimages/curl gọi http://vnsearch-backend/api/health
│     ↳ rollout status chỉ nói probe xanh; bước này nói hệ thống TRẢ LỜI được
│
└─ bo-qua-trien-khai if: vars.CD_TRIEN_KHAI_THAT != 'true'
   └─ ghi vào GITHUB_STEP_SUMMARY vì sao bỏ qua và bật thế nào
      ↳ KHÔNG khai `environment:` nên không sinh bản ghi Deployment nào
      ↳ một job bị bỏ qua trong im lặng trông hệt như một job bị quên
```

Đường phát hành theo thẻ phiên bản:

```
release.yml   on: push tags v*.*.*
├─ kiem-tra    ./mvnw clean verify → package → upload jar
├─ anh-docker  needs kiem-tra
│  ├─ QEMU + buildx → platforms: linux/amd64, linux/arm64
│  ├─ metadata-action → {{version}} | {{major}}.{{minor}} | {{major}} | latest | sha-long
│  ├─ cosign sign ∀ thẻ (đều trỏ về CÙNG một digest)
│  └─ Trivy CRITICAL exit-code 1
└─ ban-phat-hanh  if: ref bắt đầu bằng refs/tags/
   └─ gh-release: generate_release_notes, đính jar, kèm sẵn lệnh `cosign verify`
      ↳ biến câu hỏi "ảnh đang chạy có phải thứ ta đã dựng không" từ NIỀM TIN thành
        một phép KIỂM CHỨNG ĐƯỢC
```

Đường 5 — cụm Kubernetes chạy trong Docker:

```
deploy/kind/up.sh            set -euo pipefail
├─ kind create cluster --config deploy/kind/cluster.yaml    (nếu chưa có)
│  └─ 1 control-plane + 2 worker
│     ├─ node-labels ingress-ready=true
│     └─ extraPortMappings 80/443 → cổng 80/443 của MÁY THẬT   ← để vnsearch.local vào được
├─ apply ingress-nginx (bản provider/kind) → wait ready 180s
├─ docker build -t vnsearch-backend:dev  → kind load docker-image
│  ↳ nạp THẲNG vào cụm: không registry, không push, không kéo mạng
├─ kubectl apply -k deploy/k8s/overlays/dev
└─ rollout status statefulset/postgres → deployment/backend
   ↳ in ra: thêm "127.0.0.1 vnsearch.local" vào hosts rồi curl /api/health
```

Những gì `base` dựng lên, và mỗi thứ chống lại điều gì:

```
Namespace vnsearch
└─ pod-security.kubernetes.io/enforce: restricted     ← Pod vi phạm bị TỪ CHỐI, không phải cảnh báo

Deployment vnsearch-backend                 replicas 2   RollingUpdate maxUnavailable 0, maxSurge 1
├─ securityContext  runAsNonRoot, uid 1000, seccomp RuntimeDefault
├─                  allowPrivilegeEscalation false, readOnlyRootFilesystem TRUE, drop ALL
│                   └─ hệ quả: /tmp và /app/data phải là emptyDir mount vào
├─ BA loại probe, ba câu hỏi khác nhau
│  ├─ startup   /api/health   5s × 36 = 180 s  ← "nạp xong corpus chưa" — nới cho lần khởi động chậm
│  ├─ readiness /api/health                    ← "nhận lưu lượng được chưa" (rút khỏi Service nếu đỏ)
│  └─ liveness  /actuator/health/liveness      ← "còn sống không" (đỏ thì GIẾT Pod)
│     ↳ liveness KHÔNG trỏ /api/health: chỉ mục rỗng làm health đỏ, mà giết Pod thì
│       không dựng lại được chỉ mục — chỉ đổi lỗi im lặng thành vòng lặp khởi động lại
├─ requests cpu 250m / mem 768Mi, limits mem 2Gi   ← KHÔNG đặt limit CPU (chỉ gây throttle)
├─ Service ClusterIP 80 → http
├─ HPA           2..6 theo CPU 70%, scaleDown stabilization 300 s   ← chống dao động
└─ PDB           minAvailable 1                ← drain node không được hạ hết Pod

Deployment vnsearch-crawler-worker          replicas 2   terminationGracePeriodSeconds 90
├─ APP_CRAWLER_BUS=kafka (cứng), IMAGES_DOWNLOAD=false
├─ CHỈ có startup + liveness, KHÔNG readiness   ← worker không phục vụ ai, không có Service để rút khỏi
├─ KEDA ScaledObject   1..12 theo LAG Kafka
│  └─ topic vnsearch.pages, group vnsearch-url-extractor
│     lagThreshold 1000, activationLagThreshold 100    ← lag < 100 thì co về minReplicaCount
│     ↳ scale theo CÔNG VIỆC TỒN ĐỌNG, không theo CPU: worker chờ mạng là chính, CPU thấp
│       trong khi hàng đợi dài ra — HPA theo CPU sẽ không bao giờ nở
└─ PDB           maxUnavailable 1

StatefulSet vnsearch-postgres   replicas 1   PVC 5Gi   uid 999 (user `postgres` của ảnh chính thức)
├─ Service HEADLESS (clusterIP: None)          ← StatefulSet cần tên DNS ổn định cho từng Pod
├─ init từ ConfigMap vnsearch-db-schema  ← kustomize configMapGenerator đóng schema.sql vào
│  └─ disableNameSuffixHash: true       ← không thì mỗi lần sửa schema là một ConfigMap tên khác
└─ NetworkPolicy: CHỈ backend và crawler-worker được vào cổng 5432
                  ↳ mặc định trong cụm là ai cũng gọi được ai

StatefulSet vnsearch-kafka      replicas 1   PVC 10Gi   KRaft
├─ quorum voter trỏ FQDN vnsearch-kafka-0.vnsearch-kafka.vnsearch.svc.cluster.local
├─ Service headless publishNotReadyAddresses: TRUE
│  ↳ broker phải tự phân giải được tên mình TRƯỚC khi ready; thiếu cờ này nó không bao giờ ready
├─ readinessProbe = kafka-broker-api-versions.sh  (giao thức thật)
│  livenessProbe   = tcpSocket, initialDelay 120s  ← phục hồi log dài, giết sớm là vòng lặp chết
└─ NetworkPolicy: backend + worker + CHÍNH kafka (controller tự nói với mình) + ns monitoring:9308

Ingress vnsearch.local → vnsearch-backend:http   proxy-body-size 1m, limit-rps 50
```

Ba lớp cấu hình, chỉ khác nhau đúng chỗ cần khác:

```
base                    dev (overlay)                     prod (overlay)
────────────────────────────────────────────────────────────────────────────────
backend replicas 2      → 1                               → 3 + topologySpread (maxSkew 1)
HPA / PDB               → XOÁ ($patch: delete)            giữ
worker replicas 2       → 1, KEDA + PDB XOÁ               giữ
scorer bm25             → tfidf                           giữ
postgres PVC 5Gi        → 1Gi                             giữ
kafka -Xmx1g, PVC 10Gi  → -Xmx512m, 768Mi, PVC 2Gi        giữ
Secret có sẵn (dev-key) giữ (chạy máy mình)               → XOÁ HẲN
                                                            ↳ prod phải nạp secret từ NGOÀI
image :latest           → vnsearch-backend:dev            → :v1.0.0, CD ghi đè bằng @digest
                          (kind load, không registry)
                                                          + APP_SECURITY_TRUST_PROXY=true
                                                            ↳ sau ingress, IP thật nằm ở
                                                              X-Forwarded-For; thiếu cờ này
                                                              rate limit đếm nhầm IP của proxy
                                                              và chặn TOÀN BỘ người dùng như một
```

Giám sát — cùng một bộ quy tắc, hai bản cho hai môi trường:

```
COMPOSE                                    KUBERNETES
prometheus.yml  static_configs             ServiceMonitor (Prometheus Operator)
  vnsearch-backend:8080                      selector theo nhãn component
  vnsearch-crawler-worker:8080               + Service headless riêng cho worker
  kafka-exporter:9308                          ↳ worker KHÔNG có Service ở base, mà
  localhost:9090                                 ServiceMonitor thì chọn theo Service
alerts.yml                                 prometheusrule.yaml
  └────────── CI đối chiếu tên alert giữa hai tệp, lệch là ĐỎ ──────────┘

7 quy tắc, `for:` là khoảng phải đúng LIÊN TỤC — không phải trang trí:
  VnSearchBackendDown           up == 0                                 2m   critical
  VnSearchEmptyIndex            vnsearch_index_documents == 0          10m   warning
  VnSearchSearchLatencyHigh     (phân vị độ trễ tìm kiếm)               5m   warning
  VnSearchHighErrorRate         (tỷ lệ 5xx)                             5m   critical
  VnSearchCrawlBusFailing       increase(publish_failures[5m]) > 0      5m   critical
  VnSearchKafkaConsumerLagHigh  sum(consumergroup_lag) > 10000         10m   warning
  VnSearchDeadLetterGrowing     increase(offset trên topic *.DLT) > 10 15m   warning

alertmanager: group_wait 30s, repeat 4h (critical 1h)
  inhibit: critical đang kêu thì NÉN warning cùng component/instance
  receiver `chi-ghi-log` → webhook trỏ vào chỗ không tồn tại  ← cố ý, không có Slack thật
```

Sáu nguyên tắc chạy suốt tầng triển khai:

```
1. Cổng chặn phải BỎ QUA, không được THẤT BẠI
   → CD_TRIEN_KHAI_THAT chưa bật thì job bị skip trọn vẹn, lần chạy vẫn XANH
   ✗ vi phạm: một job đỏ thường trực → sau vài tuần không ai nhìn màu đỏ nữa,
     kể cả lần đỏ THẬT. Mọi cổng chặn đều bị vô hiệu hoá theo đúng cách đó.

2. Cùng một cấu hình viết hai lần thì phải có cổng ĐỐI CHIẾU
   → schema.sql (compose ⟷ k8s) và alerts (alerts.yml ⟷ prometheusrule.yaml)
   ✗ vi phạm: bản ít dùng hơn lặng lẽ mục ra, tới lúc cần thì nó chưa từng chạy

3. Ghim theo DIGEST ở mọi nơi chạm vào cụm
   → thẻ là con trỏ di động; digest là băm của nội dung ảnh, và là thứ cosign đã ký
   ✗ vi phạm: hai node kéo ảnh hai thời điểm → hai bản mã dưới cùng một tên

4. "Đã triển khai" nghĩa là ĐANG CHẠY ĐƯỢC, không phải ĐÃ GỬI LỆNH
   → rollout status --timeout, rồi curl /api/health qua đúng đường người dùng đi
   ✗ vi phạm: workflow xanh trong khi Pod đang CrashLoopBackOff

5. Trivy CHẶN ở CD/Release, KHÔNG chặn ở CI  (mâu thuẫn có chủ ý)
   → CI: ảnh nền gần như luôn có CVE chưa vá — chặn ở đây là tự vô hiệu hoá theo điều 1
   → CD: ranh giới cuối trước khi ảnh chạm vào cụm thật — chặn ở đây là đúng chỗ
   ✗ vi phạm cả hai chiều: chặn ở CI thì cổng chết; không chặn ở CD thì cổng vô nghĩa

6. Ba loại probe trả lời BA câu hỏi khác nhau, không được dùng chung endpoint
   startup   "nạp xong chưa"            → nới rộng, chỉ áp cho lần khởi động
   readiness "nhận request được chưa"   → đỏ thì RÚT khỏi Service
   liveness  "còn sống không"           → đỏ thì GIẾT Pod
   ✗ vi phạm: trỏ liveness vào /api/health thì chỉ mục rỗng gây vòng lặp khởi động lại,
     mà khởi động lại KHÔNG dựng được chỉ mục — đổi một lỗi im lặng lấy một lỗi ồn ào hơn
     nhưng không hề gần lời giải hơn
```
