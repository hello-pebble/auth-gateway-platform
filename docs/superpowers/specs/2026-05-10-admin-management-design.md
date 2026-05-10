# Admin Management System Design

**Date:** 2026-05-10  
**Project:** base-auth / matching-module  
**Status:** Approved

---

## 1. 개요

릴레이 소개팅 서비스의 운영 관리자 및 데이터 분석을 위한 관리 시스템.  
사용자가 많다는 가정 하에 운영 효율성과 데이터 가시성을 확보하는 것이 목표.

---

## 2. 아키텍처

```
[클라이언트] → [gateway-service :8082]
                    ├── /api/v1/matching/** → matching-module :8081
                    └── /api/v1/admin/**   → admin-module :8085

[admin-module :8085]
  ├── ROLE_ADMIN JWT 검증 (auth-module JWKS)
  ├── matching-module 내부 API 호출 (RestClient)
  └── 집계/통계 처리

[matching-module :8081]
  └── /internal/admin/** (ROLE_ADMIN 전용, gateway 외부 노출 없음)
```

**핵심 원칙:**
- matching-module의 `/internal/admin/**`는 gateway에서 라우팅하지 않음 (admin-module만 직접 호출)
- admin-module은 JWT에서 `ROLE_ADMIN` 확인 후 matching-module 내부 API 호출
- 인메모리 유지 → admin-module 자체 상태 저장 없음 (모두 matching-module에서 조회)

---

## 3. 인증 & 권한

- auth-module의 기존 JWT 사용, `ROLE_ADMIN` 클레임으로 관리자 식별
- admin-module: `OAuth2ResourceServer` + JWKS (auth-module) 검증
- matching-module 내부 API: 동일 JWT 포워딩 (Authorization 헤더 전달), `ROLE_ADMIN` 확인

---

## 4. 기능 목록

### 4.1 운영 관리 (Operational)

| 기능 | 엔드포인트 | 설명 |
|---|---|---|
| 전체 사용자 조회 | `GET /api/v1/admin/users` | 페이징, 노출 상태 필터 |
| 사용자 노출 강제 변경 | `PATCH /api/v1/admin/users/{userId}/exposure` | 관리자가 직접 on/off |
| 사용자 차단 | `PATCH /api/v1/admin/users/{userId}/block` | 매칭 참여 완전 차단 |
| 전체 매칭 조회 | `GET /api/v1/admin/matches` | 전체 매칭 현황 |
| 매칭 강제 삭제 | `DELETE /api/v1/admin/matches/{matchId}` | 문제 매칭 해소 |
| 전체 순위 현황 | `GET /api/v1/admin/rankings` | 전체 순위 부여 현황 |

### 4.2 데이터 분석 (Analytics)

| 기능 | 엔드포인트 | 설명 |
|---|---|---|
| 요약 통계 | `GET /api/v1/admin/stats/summary` | 총 유저 수, 활성 유저, 매칭 수, 차단 수 |
| 매칭 성사율 | `GET /api/v1/admin/stats/match-rate` | 순위 부여 대비 매칭 성사 비율 |
| 인기 사용자 | `GET /api/v1/admin/stats/top-ranked` | 가장 많이 순위 받은 사용자 Top N |

---

## 5. 모듈 구조

### 5.1 admin-module (신규, 포트 8085)

```
admin-module/
├── src/main/kotlin/com/pebble/admin/
│   ├── AdminApplication.kt
│   ├── config/
│   │   └── AdminSecurityConfig.kt       # ROLE_ADMIN JWT 검증
│   ├── controller/
│   │   └── AdminController.kt           # REST API 엔드포인트
│   ├── service/
│   │   └── AdminService.kt              # 비즈니스 로직, 집계
│   ├── client/
│   │   └── MatchingInternalClient.kt    # RestClient → matching-module 호출
│   └── dto/
│       └── AdminDtos.kt                 # 요청/응답 DTO
└── src/main/resources/
    ├── application.yaml
    └── application-dev.yaml
```

### 5.2 matching-module 추가

```
matching-module/
└── controller/
    └── AdminInternalController.kt       # /internal/admin/** 엔드포인트
```

---

## 6. 데이터 모델

### 6.1 matching-module 변경

`MatchingProfile`에 `isBlocked: Boolean = false` 필드 추가:
- 차단된 사용자는 추천 목록에서 제외
- 차단된 사용자는 순위 부여 불가
- 차단된 사용자의 기존 매칭은 유지 (소급 삭제 없음)

### 6.2 admin-module DTO

```kotlin
// 사용자 관리
data class AdminUserDto(
    val userId: Long,
    val isExposed: Boolean,
    val isBlocked: Boolean,
    val updatedAt: Instant
)
data class ExposureUpdateRequest(val isExposed: Boolean)
data class BlockUpdateRequest(val isBlocked: Boolean)

// 매칭 관리
data class AdminMatchDto(
    val matchId: String,
    val userA: Long,
    val userB: Long,
    val createdAt: Instant
)

// 순위 현황
data class AdminRankingDto(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,
    val createdAt: Instant
)

// 통계
data class SummaryStatsDto(
    val totalUsers: Int,
    val activeUsers: Int,   // isExposed=true, isBlocked=false
    val totalMatches: Int,
    val blockCount: Int
)
data class MatchRateDto(
    val totalRankings: Int,
    val totalMatches: Int,
    val matchRate: Double   // totalMatches * 2 / totalRankings
)
data class TopRankedUserDto(
    val userId: Long,
    val receivedCount: Int
)
```

---

## 7. matching-module 내부 API (/internal/admin/**)

| 메서드 | 경로 | 기능 |
|---|---|---|
| GET | `/internal/admin/users` | 전체 프로필 목록 |
| PATCH | `/internal/admin/users/{userId}/exposure` | 노출 변경 |
| PATCH | `/internal/admin/users/{userId}/block` | 차단 변경 |
| GET | `/internal/admin/matches` | 전체 매칭 목록 |
| DELETE | `/internal/admin/matches/{matchId}` | 매칭 삭제 |
| GET | `/internal/admin/rankings` | 전체 순위 목록 |

---

## 8. 저장소 계층 변경 (matching-module)

`InMemoryMatchingStore`에 추가:
- `blockUser(userId, isBlocked)` — 차단 상태 변경
- `isBlocked(userId): Boolean` — 차단 여부 확인
- `getAllProfiles(): List<MatchingProfile>` — 전체 프로필 조회
- `getAllRankings(): List<MatchRanking>` — 전체 순위 조회
- `deleteMatch(matchId)` — 매칭 삭제

---

## 9. gateway-service 라우팅 추가

`/api/v1/admin/**` → `http://admin-module:8085`  
`/internal/**` 경로는 gateway에서 차단 (외부 노출 없음)

---

## 10. 제약 사항

- 인메모리 저장소 유지 (재시작 시 데이터 초기화)
- UI 없음, REST API만 제공
- 페이징은 page/size 쿼리 파라미터 (기본 page=0, size=20)
