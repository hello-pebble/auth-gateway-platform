# Auth Principles 적용 설계: task-module & matching-module

**날짜**: 2026-05-10  
**대상 모듈**: task-module (Java, :8083), matching-module (Kotlin, :8081)  
**배포 환경**: Docker Compose  
**접근법**: Approach B — JWT 전파 + Redis 유저 세션

---

## 1. 배경 및 목표

다음 5가지 원칙을 task-module과 matching-module에 적용한다.

1. **서비스 간 통신은 인클러스터** — GW 경유 없이 Docker 내부 네트워크 직접 통신
2. **ID Token은 Client 전용** — API 서버는 `aud` 클레임으로 Access Token만 수락
3. **GW는 인증만** — 인가(RBAC)는 각 서비스가 `@PreAuthorize`로 직접 처리
4. **JWKS 캐싱으로 로컬 검증** — IdP 호출 최소화, 캐시 TTL 명시
5. **규모에 따라 RBAC → ABAC** — 현재 RBAC 구현, Redis에 ABAC 확장 포인트 준비

---

## 2. 전체 아키텍처

```
[Browser/Client]
      │  쿠키 → Authorization: Bearer {access_token}
      ▼
[gateway-service :8082]
  └─ 인증만 (JWT 서명 검증) — 인가 없음
      │
      ├──→ [task-module :8083]
      │         │ @PreAuthorize("hasAuthority('ROLE_USER')")
      │         │ task → matching 조회:
      │         │   WebClient("http://matching-service:8081")
      │         │   Authorization: Bearer {전파된 user access_token}
      │         ▼
      │    [matching-module :8081]
      │         └─ JWKS 로컬 검증 (캐시) + RBAC
      │
      └──→ [matching-module :8081]
                └─ hasAuthority("ROLE_USER") / hasAuthority("ROLE_ADMIN")

[auth-module :8080]
  └─ 로그인 성공 → Redis에 UserSession 저장 (TTL = access_token 유효기간)
  └─ 로그아웃 → Redis에서 삭제 (즉각 무효화)
  └─ /oauth2/jwks 제공

[Redis :6379]
  key: "session:{userId}"
  value: { roles, email, subscriptionLevel, region, ... }
  TTL: access_token 만료와 동기화
  ← ABAC 확장 시 attribute 저장소로 사용
```

---

## 3. 원칙별 구현 상세

### 3.1 원칙 1: 인클러스터 통신 (task → matching)

task-module이 matching-module을 조회할 때 Gateway를 경유하지 않는다.

**구현:**
- Docker Compose 내부 네트워크: `matching-service` hostname으로 직접 호출
- `task-module`에 WebClient 빈 추가, base URL = `http://matching-service:8081`
- 호출 시 현재 요청의 `Authorization` 헤더(user access_token)를 그대로 포워딩

```java
// MatchingClient.java (task-module) — RestClient 사용 (동기, WebFlux 불필요)
@Component
public class MatchingClient {
    private final RestClient restClient;

    public MatchingClient(RestClient.Builder builder,
                          @Value("${internal.matching-service.url}") String url) {
        this.restClient = builder.baseUrl(url).build();
    }

    public MatchingStatusResponse getMatchingStatus(String userId, String bearerToken) {
        return restClient.get()
            .uri("/internal/matching/status/{userId}", userId)
            .header(HttpHeaders.AUTHORIZATION, bearerToken)
            .retrieve()
            .body(MatchingStatusResponse.class);
    }
}
```

**application-dev.yaml (task-module):**
```yaml
internal:
  matching-service:
    url: http://localhost:8081   # dev
```

**application.yaml (task-module, prod):**
```yaml
internal:
  matching-service:
    url: http://matching-service:8081  # Docker Compose hostname
```

### 3.2 원칙 2: ID Token 거부 (aud 클레임 검증)

Spring Security `oauth2ResourceServer`는 기본적으로 `aud` 검증을 하지 않는다.  
Access Token의 `aud`는 리소스 서버 식별자(`task-service`, `matching-service`)이고,  
ID Token의 `aud`는 OAuth2 client_id다.

**선결 조건**: `JwtProvider.createToken`에 `aud` 클레임이 현재 없으므로 먼저 추가해야 한다.

**auth-module JwtProvider.kt 변경:**
```kotlin
private fun createToken(username: String, claims: Map<String, Any>, expiration: Long): String {
    return Jwts.builder()
        .issuer(issuerUri)
        .subject(username)
        .audience().add(listOf("task-service", "matching-service")).and()  // 추가
        .claims(claims)
        .issuedAt(Date())
        .expiration(Date(Date().time + expiration))
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact()
}
```

**각 리소스 서버 application.yaml:**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          audiences: task-service   # matching-module은 matching-service
```

또는 커스텀 `JwtDecoder` 빈에서 `OAuth2TokenValidator<Jwt>` 체인으로 검증.

### 3.3 원칙 3: GW는 인증만, 인가는 각 서비스

**task-module SecurityConfig.java 변경:**
```java
@EnableMethodSecurity   // 추가
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    // authorizeHttpRequests: .anyRequest().authenticated() 유지
    // 세부 인가는 @PreAuthorize로 컨트롤러/서비스에 위임
}
```

**task-module TaskController.java:**
```java
@PreAuthorize("hasAuthority('ROLE_USER')")
@PostMapping
public ResponseEntity<Task> createTask(...) { ... }

@PreAuthorize("hasAuthority('ROLE_USER')")
@GetMapping
public ResponseEntity<List<Task>> getMyTasks(...) { ... }
```

**matching-module: 내부 전용 엔드포인트 추가**
```kotlin
// /internal/matching/status/{userId} — 서비스 간 통신용
// SecurityConfig에서 authenticated()로 보호 (JWKS 검증)
// ROLE_USER면 자신의 상태만, ROLE_ADMIN이면 모든 사용자 조회 가능
```

### 3.4 원칙 4: JWKS 캐싱 명시

Spring Security는 기본적으로 JWKS를 메모리에 캐시하지만 TTL이 명시되지 않는다.

**application.yaml (두 모듈 공통):**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_SERVICE_URL}/oauth2/jwks
          # Spring Boot 3.x: JWK Set cache는 NimbusJwtDecoder 기본 5분
          # 커스텀 빈으로 명시적 TTL 설정 가능
```

**커스텀 JwtDecoder (명시적 캐시 TTL):**
```kotlin
@Bean
fun jwtDecoder(@Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") uri: String): JwtDecoder {
    val decoder = NimbusJwtDecoder.withJwkSetUri(uri)
        .cache(Duration.ofMinutes(10), Duration.ofMinutes(1))  // TTL 10분, refresh 1분
        .build()
    // aud validator 체인 추가 가능
    return decoder
}
```

### 3.5 원칙 5: RBAC 현재 구현 + ABAC 확장 포인트

**현재 (RBAC):**
- JWT `roles` 클레임: `["ROLE_USER"]`, `["ROLE_USER", "ROLE_ADMIN"]`
- task-module: `@PreAuthorize("hasAuthority('ROLE_USER')")`
- matching-module: 기존 `hasAuthority("ROLE_ADMIN")` + 일반 사용자 `hasAuthority("ROLE_USER")`

**Redis 유저 세션 구조 (ABAC 확장 포인트):**
```json
{
  "userId": "123",
  "email": "user@example.com",
  "roles": ["ROLE_USER"],
  "subscriptionLevel": "PREMIUM",
  "region": "KR",
  "loginAt": "2026-05-10T10:00:00Z"
}
```

- auth-module 로그인 성공 시 저장: `SET session:123 {...} EX {access_token_ttl}`
- auth-module 로그아웃 시 삭제: `DEL session:123`
- 서비스에서 ABAC 필요 시 Redis에서 attribute 조회 후 `@PreAuthorize` SpEL 또는 커스텀 `PermissionEvaluator` 적용

---

## 4. Docker Compose 변경

```yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    networks:
      - internal

  auth-module:
    environment:
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    networks:
      - internal

  task-module:
    environment:
      - INTERNAL_MATCHING_SERVICE_URL=http://matching-service:8081
    networks:
      - internal
    depends_on:
      - redis
      - matching-module

  matching-module:
    networks:
      - internal

networks:
  internal:
    driver: bridge
```

---

## 5. 변경 파일 목록

### 신규 추가
| 파일 | 내용 |
|---|---|
| `docker-compose.yml` | Redis 추가, internal 네트워크, 서비스 hostname 설정 |
| `task-module/.../config/MatchingClient.java` | WebClient 빈, JWT 포워딩 |
| `task-module/.../controller/MatchingStatusController.java` | task→matching 조회 엔드포인트 |
| `matching-module/.../controller/InternalMatchingController.kt` | 서비스 간 통신 전용 내부 엔드포인트 |
| `auth-module/.../service/RedisUserSessionService.kt` | 로그인/로그아웃 시 Redis 세션 관리 |

### 수정
| 파일 | 변경 내용 |
|---|---|
| `task-module/SecurityConfig.java` | `@EnableMethodSecurity` 추가, `aud` 검증, 커스텀 JwtDecoder |
| `task-module/application.yaml` | `audiences`, JWKS 캐시 TTL, matching-service URL |
| `task-module/application-dev.yaml` | matching-service localhost URL |
| `task-module/TaskController.java` | `@PreAuthorize` 추가 |
| `matching-module/MatchingSecurityConfig.kt` | `aud` 검증, 커스텀 JwtDecoder, ROLE_USER 추가 |
| `matching-module/application.yaml` | `audiences`, JWKS 캐시 TTL |
| `auth-module/application.yaml` | Redis 연결 설정 |

---

## 6. 확인된 사항 (스펙 검토 결과)

- **aud 클레임**: `JwtProvider.createToken`에 `aud`가 없음 → auth-module에서 먼저 추가 필요 (3.2절)
- **task-module HTTP 클라이언트**: Java 기반, Spring Boot 3.2+ → `RestClient` (동기) 사용. WebFlux 의존성 불필요
- **Redis TTL**: `JwtProvider`의 `jwt.access-expiration` 값(밀리초)을 그대로 초 단위로 변환하여 Redis TTL에 적용
