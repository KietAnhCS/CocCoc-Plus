# ══════════════════════════════════════════════════════════════════════════
# Tầng build
# ══════════════════════════════════════════════════════════════════════════
FROM golang:1.25-alpine AS build

WORKDIR /src

# Sao chép manifest TRƯỚC mã nguồn. Tầng tải phụ thuộc chỉ phải chạy lại khi
# go.mod hoặc go.sum đổi; sửa một dòng Go không kéo theo một lượt tải lại toàn
# bộ mô-đun.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Chạy test NGAY TRONG ảnh build. Repo này không có Go cài sẵn trên máy phát
# triển, nên nếu không chặn ở đây thì đường duy nhất phát hiện test hỏng là
# CI — tức là sau khi đã đẩy mã lên.
RUN go vet ./... && go test ./...

# CGO_ENABLED=0 là bắt buộc, không phải tuỳ chọn: ảnh chạy ở dưới là
# distroless/static, trong đó không có libc để liên kết động. Bỏ cờ này thì
# ảnh build xong vẫn chạy được trên alpine nhưng chết ngay khi vào distroless
# với lỗi "no such file or directory" trỏ vào chính tệp nhị phân đang tồn tại.
#
# -trimpath bỏ đường dẫn tuyệt đối của máy build khỏi tệp nhị phân, để hai lần
# build cùng mã nguồn cho ra cùng kết quả.
RUN CGO_ENABLED=0 GOOS=linux go build \
        -trimpath \
        -ldflags="-s -w" \
        -o /out/football-service \
        ./cmd/server

# ══════════════════════════════════════════════════════════════════════════
# Tầng chạy
# ══════════════════════════════════════════════════════════════════════════
#
# distroless/static: không shell, không trình quản lý gói, không tiện ích nào.
# Ảnh cuối khoảng 10 MB và diện tấn công gần như chỉ còn đúng tệp nhị phân của
# ta. Đổi lại là không gõ `docker exec ... sh` vào được — với một service chỉ
# đọc và ghi log ra stdout thì đó là cái giá đáng trả.
FROM gcr.io/distroless/static-debian12:nonroot

# Chứng chỉ gốc để gọi HTTPS tới api-sports.io. distroless/static đã kèm sẵn
# /etc/ssl/certs/ca-certificates.crt — ghi lại ở đây vì đó là thứ đầu tiên
# hỏng nếu ai đó đổi sang một ảnh gốc rỗng hơn.

COPY --from=build /out/football-service /football-service

# nonroot là UID 65532, đã có sẵn trong ảnh. Tiến trình này không ghi vào tệp
# nào và không cần cổng dưới 1024, nên không có lý do gì để chạy bằng root.
USER nonroot:nonroot

EXPOSE 8090

# Tự gọi chính mình — xem hàm healthCheck trong cmd/server/main.go.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD ["/football-service", "--health-check"]

ENTRYPOINT ["/football-service"]
