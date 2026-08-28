# syntax=docker/dockerfile:1

# ===========================================================================
# MỘT Dockerfile cho MỌI service Go của VnSearch.
# ===========================================================================
#
# Cùng nguyên tắc với backend/Dockerfile của các service Java: một quy trình
# đóng gói duy nhất, khác biệt giữa các service nằm gọn ở một dòng ARG.
#
#     docker build -f backend/go/Dockerfile \
#       --build-arg SERVICE=football \
#       -t vnsearch/football-service backend/go
#
# Ảnh cuối là alpine (không phải distroless) vì healthcheck của docker-compose
# gọi `wget` qua `sh` — cả hai có sẵn trong busybox của alpine.

FROM golang:1.27-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .

ARG SERVICE=football
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/app ./services/${SERVICE}

FROM alpine:3.20

RUN adduser -D -u 10001 app
USER app

COPY --from=build /out/app /app

ENTRYPOINT ["/app"]
