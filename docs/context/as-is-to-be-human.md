# Auth 5원칙 적용 — 현재/목표/이유 요약

**작성일**: 2026-05-14  
**브랜치**: develop  
**진행률**: 1/10 태스크 완료

---

## 한 줄 요약

> JWT 발급(auth-module) → 인증(Gateway) → 인가(각 서비스) 역할 분리를 완성하고,
> 서비스 간 직접 통신과 Redis 세션 관리를 추가한다.

---

## 지금은 어떤 상태인가 (As-Is)

**완료된 것**
- `JwtProvider`가 발급하는 Access Token에 `aud` 클레임 추가됨 (`task-service`, `matching-service`)
- 이를 검증하는 단위 테스트(`JwtProviderAudTest`) 작성됨

**아직 없는 것**
- task-module, matching-module이 JWT의 `aud`를 실제로 검증하지 않음 (누구나 어느 서비스 토큰이든 쓸 수 있음)
- `@PreAuthorize` 없어서 인증만 되면 role 무관하게 모든 API 접근 가능
- task-module이 matching-module을 호출하는 클라이언트 없음
- 로그인/로그아웃 시 Redis에 세션을 저장하지 않음

---

## 완성되면 어떤 상태가 되나 (To-Be)

### 인증·인가 흐름
```
브라우저 → Gateway(인증만) → task-module(@PreAuthorize로 인가)
                                   ↓ 인클러스터 직접 호출 (Gateway 미경유)
                              matching-module(@PreAuthorize로 인가)
```

### 세션 관리
```
로그인 성공 → Redis에 유저 세션 저장 (roles, email, TTL = 토큰 유효기간)
로그아웃   → Redis에서 즉시 삭제
```

### 변경 범위
| 모듈 | 주요 변경 |
|---|---|
| auth-module | RedisUserSessionService 신규, 로그인/로그아웃 핸들러에 Redis 연결 |
| task-module | SecurityConfig 강화, @PreAuthorize 추가, MatchingClient 신규 |
| matching-module | SecurityConfig 강화, InternalMatchingController 신규 |
| 인프라 | docker-compose.yml에 Redis + 인클러스터 URL 환경변수 |

---

## 왜 이렇게 만드는가 (Why)

### 1. Gateway 경유 없이 서비스 간 직접 통신
Gateway는 외부 클라이언트를 위한 관문이다. 이미 인증된 내부 서비스끼리 통신할 때 Gateway를 거치면 불필요한 경로가 늘어나고, Gateway 장애 시 내부 통신도 끊긴다. task → matching은 Docker 내부 네트워크로 직접 통신한다.

### 2. Access Token / ID Token 혼용 방지
ID Token의 `aud`는 OAuth2 앱 식별자, Access Token의 `aud`는 API 서버 식별자다. 두 토큰이 혼용되면 보안 경계가 무너진다. 각 리소스 서버가 `aud` 클레임을 직접 검증해서 자신의 토큰인지 확인한다.

### 3. 인가 책임은 각 서비스
Gateway가 role 체크까지 담당하면, 서비스별 권한 규칙이 바뀔 때마다 Gateway 코드를 수정해야 한다. 각 서비스가 `@PreAuthorize`로 직접 인가를 처리하면 변경이 격리된다.

### 4. JWKS 캐시 TTL 명시
Spring Security 기본값은 JWKS 캐시 TTL이 명시적이지 않다. auth-module에 장애가 생겨도 캐시된 공개키로 일정 시간 토큰 검증이 가능해야 한다. 10분 TTL을 명시한다.

### 5. RBAC 지금, ABAC 나중에
지금은 `ROLE_USER` / `ROLE_ADMIN`으로 단순하게 구분한다. 나중에 구독 등급(PREMIUM/BASIC), 지역(KR/US) 기반으로 세분화할 수 있도록 Redis 세션에 확장 필드를 미리 심어둔다.

---

## 남은 작업 (9개 태스크)

- [ ] Task 2 — RedisUserSessionService 구현
- [ ] Task 3 — 로그인/로그아웃 핸들러 Redis 연결
- [ ] Task 4 — task-module SecurityConfig 강화
- [ ] Task 5 — TaskController @PreAuthorize 추가
- [ ] Task 6 — MatchingClient 구현
- [ ] Task 7 — MatchingStatusController 추가
- [ ] Task 8 — InternalMatchingController 추가
- [ ] Task 9 — matching-module SecurityConfig 강화
- [ ] Task 10 — docker-compose.yml 업데이트

> 상세 구현 플랜: `docs/superpowers/plans/2026-05-10-auth-principles.md`
