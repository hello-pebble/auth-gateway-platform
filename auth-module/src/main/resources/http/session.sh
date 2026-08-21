#!/bin/bash

# JWT 및 인메모리 Refresh Token Rotation 흐름 확인
# 서버 내부 저장소는 직접 조회하지 않고 API 응답과 쿠키 교체로 검증합니다.

BASE_URL="${BASE_URL:-http://localhost:8080}"
COOKIE_JAR="jwt_cookies.txt"

do_curl() {
  local method=$1
  local url=$2
  shift 2
  curl -s -D - "$@" "$url" -X "$method"
}

rm -f "$COOKIE_JAR"

echo "=== 1. Login ==="
LOGIN_RESPONSE=$(do_curl POST "$BASE_URL/api/v1/login" \
  -c "$COOKIE_JAR" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}')
echo "$LOGIN_RESPONSE" | grep "HTTP/"

echo "=== 2. Refresh and rotate cookies ==="
REFRESH_RESPONSE=$(do_curl POST "$BASE_URL/api/v1/refresh" \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR")
echo "$REFRESH_RESPONSE" | grep "HTTP/"

echo "=== 3. Logout ==="
do_curl POST "$BASE_URL/api/v1/logout" \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR" | grep "HTTP/"

rm -f "$COOKIE_JAR"
