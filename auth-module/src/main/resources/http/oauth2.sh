#!/bin/bash

# OAuth2.0 통합 API 테스트 (Cookie-based JWT)
# Phase 3: OAuth2.0 Social Login Integration

BASE_URL="http://localhost:8080"
COOKIE_JAR="oauth2_cookies.txt"

# Function to make curl request and separate headers from body
do_curl() {
  local method=$1
  local url=$2
  shift 2
  curl -s -D - "$@" "$url" -X "$method"
}

rm -f "$COOKIE_JAR"

echo "=== 1. OAuth2 Login Guide ==="
echo "OAuth2 로그인은 브라우저에서 진행해야 합니다."
echo "URL: $BASE_URL/oauth2/authorization/google (또는 github)"
echo "로그인 성공 후 생성된 쿠키를 이 스크립트에서 사용한다고 가정합니다."
echo

# 실제 테스트를 위해 수동으로 쿠키를 설정하거나 브라우저에서 가져온 값을 시뮬레이션 할 수 있습니다.
# 여기서는 소셜 로그인 성공 후 발급될 쿠키의 유효성을 검증하는 시나리오를 작성합니다.

echo "=== 2. Access Protected Resource with Cookies ==="
# 브라우저에서 소셜 로그인 성공 후 발급된 쿠키가 oauth2_cookies.txt에 있다고 가정할 때
# 만약 쿠키가 없다면 401 Unauthorized가 발생할 것입니다.

RESPONSE=$(do_curl GET "$BASE_URL/api/v1/users/me" \
  -b "$COOKIE_JAR")

echo "$RESPONSE" | grep "HTTP/"
echo

echo "=== 3. Logout (Cookie Removal) ==="
# 로그아웃 성공 시 브라우저 쿠키가 무효화되어야 합니다.
do_curl POST "$BASE_URL/api/v1/logout" \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR"
echo

echo "=== 4. Access after logout (Should Fail 401) ==="
do_curl GET "$BASE_URL/api/v1/users/me" \
  -b "$COOKIE_JAR"
echo

rm -f "$COOKIE_JAR"
