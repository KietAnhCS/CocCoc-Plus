#!/bin/bash
# Tạo CSDL và tài khoản riêng cho auth/downloads/settings. Bản song song của
# deploy/postgres/init-db.sh, chạy một lần lúc initdb.
set -euo pipefail

AUTH_PW="${AUTH_DB_PASSWORD:-$POSTGRES_PASSWORD}"
DOWNLOADS_PW="${DOWNLOADS_DB_PASSWORD:-$POSTGRES_PASSWORD}"
SETTINGS_PW="${SETTINGS_DB_PASSWORD:-$POSTGRES_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE USER vnsearch_auth WITH PASSWORD '${AUTH_PW}';
    CREATE DATABASE vnsearch_auth OWNER vnsearch_auth;
    CREATE USER vnsearch_downloads WITH PASSWORD '${DOWNLOADS_PW}';
    CREATE DATABASE vnsearch_downloads OWNER vnsearch_downloads;
    CREATE USER vnsearch_settings WITH PASSWORD '${SETTINGS_PW}';
    CREATE DATABASE vnsearch_settings OWNER vnsearch_settings;
SQL

for db in vnsearch_auth vnsearch_downloads vnsearch_settings; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$db" <<-SQL
      REVOKE ALL ON SCHEMA public FROM PUBLIC;
      GRANT ALL ON SCHEMA public TO ${db};
SQL
done
