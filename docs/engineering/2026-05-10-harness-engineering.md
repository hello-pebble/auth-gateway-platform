# base-auth 하네스 엔지니어링 정리

**작성일:** 2026-05-10  
**프로젝트:** base-auth  
**상태:** 진행 중

---

## 1. 프로젝트 개요

릴레이 소개팅 서비스의 인증/매칭/관리 백엔드. Kotlin 2.2.0 + Spring Boot 3.5.3 기반 MSA로 구성되며, 모든 저장소는 인메모리(ConcurrentHashMap) 유지.

---

## 2. 모듈 구조

| 모듈 | 포트 | 역할 | 상태 |
|---|---|---|---|
| auth-module | 8080 | OAuth2 AS, JWT 발급, 사용자 관리, 비밀번호 변경 | 완료 |
| gateway-service | 8082 | Spring Cloud Gateway, 쿠키→헤더 변환 | 완료 |
| matching-module | 8081 | 인메모리 매칭 엔진 (노출·순위·매칭·차단) | 완료 |
| task-module | 8083 | 태스크 CRUD (Java/Kotlin 혼용) | 완료 |
| preview-module | 8084 | 플레이스홀더 | 스켈레톤 |
| admin-module | 8085 | 운영 관리, 통계, ROLE_ADMIN 보호 | 완료 |

### 요청 흐름

```
[클라이언트]
    │
    ▼
[gateway-service :8082]  ← 쿠키→Bearer 헤더 변환, JWT 통과(검증 없음)
    ├── /api/v1/matching/**  →  matching-module :8081  (JWT 검증)
    ├── /api/v1/admin/**     →  admin-module :8085     (ROLE_ADMIN JWT 검증)
    └── /api/v1/**, /login   →  auth-module :8080      (공개 or 인증)

[admin-module :8085]
    └── /internal/admin/**  →  matching-module :8081   (게이트웨이 우회, JWT 포워딩)

[auth-module :8080]
    └── /oauth2/jwks  →  리소스 서버 JWKS 제공
```

---

## 3. 인증 & 보안 설계

### 3.1 JWT 발급 (auth-module)

- **Authorization Server:** Spring Authorization Server
- **Access Token:** 15분, HttpOnly Secure 쿠키 전달
- **Refresh Token:** 7일, Refresh Token Rotation(RTR) 적용 — 재발급 시 기존 즉시 무효화
- **Redis:** Refresh Token 저장소
- **커스텀 클레임:** `roles: ["ROLE_ADMIN"]` or `["ROLE_USER"]`

### 3.2 JWT 검증 (리소스 서버)

matching-module과 admin-module 모두 동일한 패턴 적용:

```kotlin
val grantedAuthoritiesConverter = JwtGrantedAuthoritiesConverter()
grantedAuthoritiesConverter.setAuthoritiesClaimName("roles")   // JWT의 roles 클레임 사용
grantedAuthoritiesConverter.setAuthorityPrefix("")              // 접두어 없이 그대로 사용
```

JWT `roles: ["ROLE_ADMIN"]` → Spring Security authority `ROLE_ADMIN`  
(Spring 기본값인 `scope`/`scp` 클레임 + `SCOPE_` 접두어를 커스텀 오버라이드)

### 3.3 권한 모델

| 경로 | 요구 권한 |
|---|---|
| `/api/v1/matching/**` | authenticated (모든 인증 사용자) |
| `/internal/admin/**` | `ROLE_ADMIN` (matching-module 내부) |
| `/api/v1/admin/**` | `ROLE_ADMIN` (admin-module) |
| `/actuator/health`, `/login`, `/signup` | permitAll |

### 3.4 게이트웨이 보안 철학

게이트웨이는 JWT를 **검증하지 않고** 쿠키를 Bearer 헤더로 변환만 수행. 각 리소스 서버(matching, admin)가 자체 검증 책임을 가짐 → **분산 검증 모델**.

---

## 4. 매칭 시스템 (matching-module)

### 4.1 도메인 모델

```kotlin
data class MatchingProfile(
    val userId: Long,
    val isExposed: Boolean = false,   // 추천 목록 노출 여부
    val isBlocked: Boolean = false,   // 관리자 차단 여부
    val updatedAt: LocalDateTime
)

data class MatchRanking(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,          // 1~3 (init 블록으로 검증)
    val createdAt: LocalDateTime
)

data class ChatMatch(
    val id: String,         // UUID
    val userA: Long,
    val userB: Long,
    val createdAt: LocalDateTime
)
```

### 4.2 매칭 로직

1. A가 B에게 1~3위 순위 부여
2. B도 A에게 1~3위 순위 부여한 기록 존재 → **상호 매칭 성립**
3. `ChatMatch` 생성 후 저장

### 4.3 차단(Block) 동작

- 차단된 사용자는 `getAllExposedUsers()` 필터에서 제외 → 추천 목록 미노출
- `rankUser()` 호출 시 차단 여부 확인 → `IllegalArgumentException` 발생

---

## 5. 관리자 시스템 (admin-module) — 신규

### 5.1 아키텍처

admin-module은 자체 상태를 저장하지 않음. matching-module의 내부 API를 RestClient로 직접 호출하고, 응답을 집계하여 반환.

```
AdminController → AdminService → MatchingInternalClient → matching-module /internal/admin/**
```

### 5.2 운영 관리 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/v1/admin/users?page=0&size=20` | 전체 사용자 목록 (페이징) |
| PATCH | `/api/v1/admin/users/{userId}/exposure` | 노출 상태 변경 |
| PATCH | `/api/v1/admin/users/{userId}/block` | 차단 상태 변경 |
| GET | `/api/v1/admin/matches?page=0&size=20` | 전체 매칭 목록 (페이징) |
| DELETE | `/api/v1/admin/matches/{matchId}` | 매칭 강제 삭제 |
| GET | `/api/v1/admin/rankings?page=0&size=20` | 전체 순위 목록 (페이징) |

### 5.3 통계 API

| 메서드 | 경로 | 응답 |
|---|---|---|
| GET | `/api/v1/admin/stats/summary` | totalUsers, activeUsers, totalMatches, blockCount |
| GET | `/api/v1/admin/stats/match-rate` | totalRankings, totalMatches, matchRate |
| GET | `/api/v1/admin/stats/top-ranked?limit=10` | 가장 많이 순위 받은 사용자 Top N |

### 5.4 페이징 구현

별도 DB 없이 인메모리 페이징:
```kotlin
val paged = all.drop(page * size).take(size)
return PageResponse(paged, page, size, all.size)
```

---

## 6. 테스트 전략

### 6.1 단위 테스트 패턴

```kotlin
// Mockito-Kotlin given/verify 패턴
given(matchingClient.getAllUsers(token)).willReturn(listOf(...))
val result = adminService.getSummaryStats(token)
assertEquals(3, result.totalUsers)
```

### 6.2 TDD 적용 범위

| 테스트 대상 | 방식 | 테스트 수 |
|---|---|---|
| `MatchingService` | TDD (차단 관련 먼저 작성) | 4개 |
| `AdminService` | TDD (서비스 구현 전 테스트 작성) | 5개 |

### 6.3 테스트 커버리지 항목

**MatchingServiceTest:**
- 상호 매칭 성공 (양쪽 모두 Top 3 순위)
- 노출 사용자만 추천 목록에 포함
- 차단된 사용자 추천 목록 제외
- 차단된 사용자에게 순위 부여 시 예외

**AdminServiceTest:**
- 요약 통계 집계 정확성
- 매칭 성사율 계산 (totalMatches * 2 / totalRankings)
- 인기 사용자 정렬 (receivedCount 내림차순)
- 페이징 정확성 (drop/take)
- 순위 없을 때 성사율 0.0

---

## 7. 파일 변경 이력 (Admin 시스템 구현)

### matching-module 수정

| 파일 | 변경 내용 |
|---|---|
| `domain/MatchingModels.kt` | `MatchingProfile`에 `isBlocked: Boolean` 추가 |
| `infrastructure/InMemoryMatchingStore.kt` | `getAllProfiles`, `getAllRankings`, `getAllMatches`, `deleteMatch`, `blockUser`, `isBlocked` 추가; `getAllExposedUsers` 차단 필터 추가 |
| `domain/MatchingService.kt` | `rankUser`에 차단 사용자 검증 추가 |
| `config/MatchingSecurityConfig.kt` | `JwtGrantedAuthoritiesConverter` 등록, `/internal/admin/**` ROLE_ADMIN 보호 추가 |
| `controller/AdminInternalController.kt` | **신규** — `/internal/admin/**` 내부 API |
| `test/MatchingServiceTest.kt` | 차단 관련 테스트 2개 추가 |

### admin-module 신규 생성

| 파일 | 역할 |
|---|---|
| `build.gradle.kts` | Spring Boot Web, Security, OAuth2 Resource Server |
| `AdminApplication.kt` | 진입점 |
| `config/AdminSecurityConfig.kt` | ROLE_ADMIN JWT 검증, 401 JSON 응답 |
| `dto/AdminDtos.kt` | 요청/응답 DTO 전체 |
| `client/MatchingInternalClient.kt` | RestClient로 matching-module 내부 API 호출 |
| `service/AdminService.kt` | 페이징·통계 비즈니스 로직 |
| `controller/AdminController.kt` | REST API, `@AuthenticationPrincipal Jwt`로 토큰 추출 |
| `resources/application.yaml` | 포트 8085, JWKS URI, matching URL |
| `test/AdminServiceTest.kt` | AdminService 단위 테스트 5개 |

### 루트 / gateway 수정

| 파일 | 변경 내용 |
|---|---|
| `settings.gradle.kts` | `include("admin-module")` 추가 |
| `gateway-service/application.yaml` | `/api/v1/admin/**` → admin-module 라우팅 추가 |

---

## 8. 현재 한계점 및 개선 여지

### 8.1 인메모리 저장소의 한계

- **수평 확장 불가:** matching-module 인스턴스가 여러 개면 각자 다른 상태를 가짐. admin 작업이 한 인스턴스에만 적용됨.
- **재시작 시 초기화:** 모든 매칭/순위/프로필 데이터 소실.
- **개선 방향:** Redis 또는 PostgreSQL로 저장소 교체 (설정 프로파일 활용).

### 8.2 JWT 포워딩 방식

- 사용자 JWT를 admin-module → matching-module로 그대로 전달. 두 모듈이 동일 JWKS를 공유해야 함.
- matching-module의 내부 포트가 직접 노출되면 ROLE_ADMIN 토큰을 가진 누구나 `/internal/admin/**` 직접 호출 가능.
- **개선 방향:** 서비스 간 통신에는 서비스 계정 토큰 또는 mTLS 사용.

### 8.3 updateExposure 상태 손실 버그

`MatchingService.updateExposure`는 새 `MatchingProfile` 객체를 생성하므로 기존 `isBlocked` 상태가 초기화됨:
```kotlin
// 현재: isBlocked 상태 손실
val profile = MatchingProfile(userId, isExposed, updatedAt = LocalDateTime.now())

// 권장: 기존 상태 보존
val existing = store.getProfile(userId) ?: MatchingProfile(userId)
store.saveProfile(existing.copy(isExposed = isExposed, updatedAt = LocalDateTime.now()))
```

### 8.4 감사 로그(Audit Log) 부재

관리자가 어떤 사용자를 차단하고 어떤 매칭을 삭제했는지 추적 불가. 운영 환경에서는 필수.

### 8.5 매칭 성사율 계산 방식

현재 `matchRate = totalMatches * 2 / totalRankings`. 한 매칭은 두 명의 상호 순위 부여를 소비하므로 `*2`를 곱하는 방식. 직관적이지 않아 향후 `매칭 수 / 순위 쌍 수`로 재정의가 필요할 수 있음.
