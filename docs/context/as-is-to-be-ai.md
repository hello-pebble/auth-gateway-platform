---
type: context
purpose: ai-agent-handoff
date: 2026-05-14
branch: develop
status: in-progress
task_progress: 1/10
---

# As-Is / To-Be / Why — AI Handoff Document

## CURRENT STATE (As-Is)

### auth-module
| File | State |
|---|---|
| `config/JwtProvider.kt` | MODIFIED — `createToken`에 `.audience().add(["task-service","matching-service"]).and()` 추가됨. RS256 서명, `aud` 클레임 포함. |
| `config/RedisUserSessionService.kt` | MISSING — 존재하지 않음 |
| `config/FormLoginSuccessHandler.kt` | UNMODIFIED — Redis 세션 저장 로직 없음 |
| `config/oauth2/OAuth2SuccessHandler.kt` | UNMODIFIED — Redis 세션 저장 로직 없음 |
| `config/CustomAuthenticationHandler.kt` | UNMODIFIED — 로그아웃 시 Redis 삭제 없음 |
| `resources/application-dev.yaml` | UNMODIFIED — Redis 연결 설정 없음 |

### task-module
| File | State |
|---|---|
| `config/SecurityConfig.java` | UNMODIFIED — `@EnableMethodSecurity` 없음, 커스텀 `JwtDecoder` 없음, `aud` 검증 없음 |
| `controller/TaskController.java` | UNMODIFIED — `@PreAuthorize` 없음, RBAC 미적용 |
| `config/MatchingClient.java` | MISSING — 존재하지 않음 |
| `controller/MatchingStatusController.java` | MISSING — 존재하지 않음 |

### matching-module
| File | State |
|---|---|
| `config/MatchingSecurityConfig.kt` | UNMODIFIED — `aud` 검증 없음, JWKS 캐시 TTL 명시 없음, ROLE_USER 인가 없음 |
| `controller/InternalMatchingController.kt` | MISSING — 존재하지 않음 |

### Infrastructure
| File | State |
|---|---|
| `docker-compose.yml` | task-service에 `INTERNAL_MATCHING_SERVICE_URL` env 없음, `depends_on: matching-service` 없음 |

---

## TARGET STATE (To-Be)

### Task Completion Map
| Task | Module | Status | Description |
|---|---|---|---|
| Task 1 | auth-module | ✅ DONE | JWT `aud` 클레임 추가 (`task-service`, `matching-service`) + `JwtProviderAudTest.kt` |
| Task 2 | auth-module | ⬜ TODO | `RedisUserSessionService.kt` 구현 |
| Task 3 | auth-module | ⬜ TODO | 로그인/로그아웃 핸들러에 Redis 세션 연결 |
| Task 4 | task-module | ⬜ TODO | `SecurityConfig.java` — `@EnableMethodSecurity` + 커스텀 `JwtDecoder` (aud, JWKS 캐시 10분) |
| Task 5 | task-module | ⬜ TODO | `TaskController.java` — 모든 엔드포인트에 `@PreAuthorize("hasAuthority('ROLE_USER')")` |
| Task 6 | task-module | ⬜ TODO | `MatchingClient.java` 신규 — RestClient 기반 인클러스터 HTTP 클라이언트 |
| Task 7 | task-module | ⬜ TODO | `MatchingStatusController.java` 신규 — task→matching 조회 엔드포인트 |
| Task 8 | matching-module | ⬜ TODO | `InternalMatchingController.kt` 신규 — 서비스 간 전용 엔드포인트 |
| Task 9 | matching-module | ⬜ TODO | `MatchingSecurityConfig.kt` — aud 검증 + JWKS 캐시 + ROLE_USER 인가 |
| Task 10 | infra | ⬜ TODO | `docker-compose.yml` — task-service env + depends_on |

### Final Architecture (Target)
```
[Browser] --쿠키→ [Gateway :8082] --JWT Bearer→ [task-module :8083]
                                                      │ RestClient (인클러스터)
                                                      │ Authorization: Bearer {user_token}
                                                      ▼
                                               [matching-module :8081]
                                                 /internal/matching/status/{userId}

[auth-module :8080]
  ├── login success → Redis SET session:{userId} {roles,email,...} EX {access_ttl_seconds}
  ├── logout → Redis DEL session:{userId}
  └── GET /oauth2/jwks → JWKS endpoint

[Redis :6379]  key: "session:{userId}"
```

---

## WHY

### Principle 1 — 인클러스터 통신
- **What**: task → matching 호출 시 Gateway 경유 불가
- **Why**: Gateway는 외부 트래픽용. 내부 서비스 간 통신에 GW를 경유하면 불필요한 네트워크 홉, 쿠키→헤더 변환 로직 재진입, 단일 장애점 발생
- **How**: `MatchingClient`가 `http://matching-service:8081` (Docker Compose hostname)으로 직접 호출

### Principle 2 — ID Token 거부
- **What**: 리소스 서버는 Access Token만 수락, ID Token 거부
- **Why**: OAuth2 스펙상 ID Token의 `aud`는 client_id, Access Token의 `aud`는 리소스 서버 식별자. 혼용 시 보안 경계 붕괴
- **How**: 커스텀 `JwtDecoder`에 `OAuth2TokenValidator`로 `aud` 클레임 필수 검증 (`task-service` / `matching-service`)

### Principle 3 — GW는 인증만
- **What**: Gateway는 JWT 서명 검증만, 인가(role 체크)는 각 서비스 책임
- **Why**: 인가 로직이 Gateway에 집중되면 각 서비스의 비즈니스 규칙 변경 시 Gateway 수정 필요 → 강한 결합
- **How**: `@EnableMethodSecurity` + `@PreAuthorize("hasAuthority('ROLE_USER')")`

### Principle 4 — JWKS 캐싱
- **What**: Spring Security 기본 JWKS 캐시는 TTL 미명시 → 매 요청마다 auth-module 호출 가능
- **Why**: auth-module 장애 시 모든 서비스 인증 불능. TTL 명시로 IdP 의존성 완화
- **How**: `DefaultJWKSetCache(10, 2, TimeUnit.MINUTES)` — 10분 TTL, 2분 리프레시 예약

### Principle 5 — RBAC → ABAC
- **What**: 현재 RBAC(`ROLE_USER`, `ROLE_ADMIN`), 향후 ABAC 확장 준비
- **Why**: 서비스 성숙 후 `subscriptionLevel`, `region` 기반 세분화 인가 필요 예상
- **How**: Redis `UserSession`에 `subscriptionLevel`, `region` 필드 보유. 현재는 RBAC, 추후 커스텀 `PermissionEvaluator`로 전환 가능

---

## IMPLEMENTATION NOTES

### Task 1 완료 상태 확인
- `JwtProvider.kt:53` — `.audience().add(listOf("task-service", "matching-service")).and()` 존재 확인
- `JwtProviderAudTest.kt` — reflection으로 `privateKey` 주입 후 `claims.audience` 검증

### Task 2 시작 전 확인 필요
- `application-dev.yaml`에 Redis 설정 추가 시 `spring:` 블록 하위 들여쓰기 주의
- `StringRedisTemplate` 빈 자동 구성은 `spring-boot-starter-data-redis` 의존성 필요 (build.gradle 확인)

### 주의사항
- task-module은 Java/Kotlin 혼용 모듈 — Java 파일 추가 시 `src/main/java/` 경로 사용
- `FormLoginSuccessHandler`의 `email` 필드: Form 로그인은 email 정보 없을 수 있음 → 빈 문자열 처리
- matching-module `MatchingService.getMyMatches(userId: Long)` — userId 타입이 `Long`임에 유의 (InternalMatchingController PathVariable 파싱 시)
