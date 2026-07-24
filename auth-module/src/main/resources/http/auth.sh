#!/bin/bash

# 인증 관련 API 테스트 (Login, Refresh, Logout)
# Phase 2: JWT Based Authentication (in-memory Refresh Token repository)

BASE_URL="http://localhost:8080"
COOKIE_JAR="cookies.txt"

# Function to make curl request and separate headers from body
do_curl() {
  local method=$1
  local url=$2
  shift 2
  curl -s -D - "$@" "$url" -X "$method"
}

rm -f "$COOKIE_JAR"

echo "=== 1. Login (testuser) ==="
LOGIN_RESPONSE=$(do_curl POST "$BASE_URL/api/v1/login" \
  -c "$COOKIE_JAR" \
  -d "username=testuser&password=password123")

ACCESS_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -i "Authorization:" | awk '{print $2}' | tr -d '\r')
echo "Access Token extracted."
echo

echo "=== 2. Refresh Token (Rotate Access Token) ==="
# Send refreshToken cookie, receive new Access Token and new refreshToken cookie
REFRESH_RESPONSE=$(do_curl POST "$BASE_URL/api/v1/refresh" \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR")

NEW_ACCESS_TOKEN=$(echo "$REFRESH_RESPONSE" | grep -i "Authorization:" | awk '{print $2}' | tr -d '\r')
echo "New Access Token: $NEW_ACCESS_TOKEN"
echo

echo "=== 3. Access with New Token ==="
do_curl GET "$BASE_URL/api/v1/users/me" \
  -H "Authorization: Bearer $NEW_ACCESS_TOKEN"
echo

echo "=== 4. Logout ==="
# Logout removes the browser cookies. The current in-memory Refresh Token repository
# is process-local and is not a distributed revocation store.
do_curl POST "$BASE_URL/api/v1/logout" \
  -b "$COOKIE_JAR" \
  -H "Authorization: Bearer $NEW_ACCESS_TOKEN"
echo

echo "=== 5. Access after logout (Should Fail 401) ==="
do_curl GET "$BASE_URL/api/v1/users/me" \
  -H "Authorization: Bearer $NEW_ACCESS_TOKEN"
echo

rm -f "$COOKIE_JAR"
