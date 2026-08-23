#!/bin/bash
# ===========================================================================
# Tạo CSDL và tài khoản riêng cho từng service.
# ===========================================================================
#
# Chạy MỘT LẦN, lúc container postgres khởi tạo dữ liệu lần đầu (cơ chế
# docker-entrypoint-initdb.d của image chính thức). Container đã có dữ liệu thì
# thư mục này bị bỏ qua hoàn toàn — nên sửa tệp này KHÔNG có tác dụng lên một
# volume đã tồn tại. Muốn áp lại: `docker compose down -v` (xoá sạch dữ liệu).
#
# VÌ SAO MỖI SERVICE MỘT CSDL VÀ MỘT TÀI KHOẢN RIÊNG
#
# Cách dễ hơn là dùng chung một CSDL `vnsearch` và một tài khoản cho tất cả.
# Nó chạy được, và nó xoá sạch ranh giới mà việc tách service vừa dựng lên:
#
#   1. Một lỗ hổng SQL injection ở downloads-service sẽ đọc được bảng
#      `auth_users` — nơi chứa hash mật khẩu. Với tài khoản riêng, kết nối đó
#      thậm chí không NHÌN THẤY CSDL kia.
#   2. Hai service dùng chung lược đồ thì không service nào đổi được lược đồ
#      nữa mà không phối hợp với service kia — đúng thứ microservice sinh ra
#      để tránh.
#   3. Không đo được service nào gây tải. Mọi truy vấn chậm đều mang cùng một
#      tên tài khoản trong `pg_stat_activity`.
#
# Đây là hiện thân cụ thể của nguyên tắc "database per service", và cũng là
# biện pháp cho A01 (Broken Access Control) ở tầng dữ liệu.

set -euo pipefail

# Mật khẩu lấy từ biến môi trường, KHÔNG viết trong tệp này (tệp này nằm trong
# Git). Thiếu biến thì dùng lại mật khẩu của superuser — chỉ chấp nhận được khi
# chạy thử cục bộ, và dòng cảnh báo dưới đây nói rõ điều đó.
AUTH_PW="${AUTH_DB_PASSWORD:-$POSTGRES_PASSWORD}"
DOWNLOADS_PW="${DOWNLOADS_DB_PASSWORD:-$POSTGRES_PASSWORD}"
SETTINGS_PW="${SETTINGS_DB_PASSWORD:-$POSTGRES_PASSWORD}"

if [ "$AUTH_PW" = "$POSTGRES_PASSWORD" ]; then
  echo "CANH BAO: cac service dung chung mat khau voi superuser." >&2
  echo "          Dat AUTH_DB_PASSWORD / DOWNLOADS_DB_PASSWORD / SETTINGS_DB_PASSWORD" >&2
  echo "          truoc khi chay o moi truong that." >&2
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    -- ---------------------------------------------------------------
    -- auth-service — CSDL nhạy cảm nhất hệ thống.
    -- ---------------------------------------------------------------
    CREATE USER vnsearch_auth WITH PASSWORD '${AUTH_PW}';
    CREATE DATABASE vnsearch_auth OWNER vnsearch_auth;

    -- ---------------------------------------------------------------
    -- downloads-service
    -- ---------------------------------------------------------------
    CREATE USER vnsearch_downloads WITH PASSWORD '${DOWNLOADS_PW}';
    CREATE DATABASE vnsearch_downloads OWNER vnsearch_downloads;

    -- ---------------------------------------------------------------
    -- settings-service
    -- ---------------------------------------------------------------
    CREATE USER vnsearch_settings WITH PASSWORD '${SETTINGS_PW}';
    CREATE DATABASE vnsearch_settings OWNER vnsearch_settings;
SQL

# ---------------------------------------------------------------------------
# GỠ quyền mặc định trên schema `public` của từng CSDL.
#
# PostgreSQL 14 trở về trước cấp quyền CREATE trên `public` cho MỌI tài khoản
# (vai trò PUBLIC). Nghĩa là tài khoản của downloads-service tạo được bảng
# trong CSDL của auth-service nếu nó kết nối được tới đó. PostgreSQL 15+ đã
# sửa mặc định này, nhưng lệnh dưới đây vẫn cần: nó khiến cấu hình đúng bất kể
# ai chạy trên phiên bản nào, thay vì đúng nhờ may mắn.
# ---------------------------------------------------------------------------
for db in vnsearch_auth vnsearch_downloads vnsearch_settings; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-SQL
      REVOKE ALL ON SCHEMA public FROM PUBLIC;
      GRANT ALL ON SCHEMA public TO ${db};
SQL
done

echo "Da tao 3 CSDL rieng: vnsearch_auth, vnsearch_downloads, vnsearch_settings"
