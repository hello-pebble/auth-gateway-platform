# Auth 5원칙 적용 구현 플랜

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** task-module과 matching-module에 인클러스터 통신, aud 검증, RBAC, JWKS 캐싱, Redis 유저 세션을 적용한다.

**Architecture:** auth-module이 JWT 발급 시 aud 클레임을 추가하고, 로그인/로그아웃 시 Redis에 유저 세션을 관리한다. task-module은 `@EnableMethodSecurity` + `@PreAuthorize`로 RBAC를 처리하고, 내부 네트워크(`http://matching-service:8081`)를 통해 RestClient로 matching-module을 직접 호출한다. 두 리소스 서버 모두 커스텀 `JwtDecoder` 빈으로 aud 검증과 JWKS 캐시 TTL을 명시적으로 설정한다.

**Tech Stack:** Spring Boot 3.x, Spring Security 6, JJWT 0.12.6, Nimbus JOSE, Spring Data Redis, RestClient, JUnit 5 + Mockito

---

## 파일 구조 (변경 범위)

### auth-module (Kotlin)
| 파일 | 작업 |
|---|---|
| `config/JwtProvider.kt` | `createToken`에 `.audience()` 추가 |
| `config/RedisUserSessionService.kt` | **신규** — 세션 저장/조회/삭제 |
| `config/FormLoginSuccessHandler.kt` | 로그인 성공 시 Redis 세션 저장 |
| `config/oauth2/OAuth2SuccessHandler.kt` | OAuth2 로그인 성공 시 Redis 세션 저장 |
| `config/CustomAuthenticationHandler.kt` | 로그아웃 시 Redis 세션 삭제 |
| `src/main/resources/application-dev.yaml` | Redis 연결 설정 추가 |

### task-module (Java)
| 파일 | 작업 |
|---|---|
| `config/SecurityConfig.java` | `@EnableMethodSecurity` + 커스텀 `JwtDecoder` (aud, JWKS 캐시) |
| `controller/TaskController.java` | 각 엔드포인트에 `@PreAuthorize("hasAuthority('ROLE_USER')")` |
| `config/MatchingClient.java` | **신규** — RestClient 기반 인클러스터 HTTP 클라이언트 |
| `controller/MatchingStatusController.java` | **신규** — task→matching 조회 엔드포인트 |
| `src/main/resources/application.yaml` | `internal.matching-service.url` (Docker) |
| `src/main/resources/application-dev.yaml` | `internal.matching-service.url` (localhost) |

### matching-module (Kotlin)
| 파일 | 작업 |
|---|---|
| `config/MatchingSecurityConfig.kt` | `@EnableMethodSecurity` + 커스텀 `JwtDecoder` (aud, JWKS 캐시) + internal 경로 인가 |
| `controller/InternalMatchingController.kt` | **신규** — 서비스 간 전용 엔드포인트 |

### 인프라
| 파일 | 작업 |
|---|---|
| `docker-compose.yml` | task-service에 `INTERNAL_MATCHING_SERVICE_URL` env, `depends_on: matching-service` 추가 |

---

## Task 1: auth-module — JWT aud 클레임 추가

**Files:**
- Modify: `auth-module/src/main/kotlin/com/pebble/basicAuth/config/JwtProvider.kt`
- Test: `auth-module/src/test/kotlin/com/pebble/basicAuth/config/JwtProviderAudTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`auth-module/src/test/kotlin/com/pebble/basicAuth/config/JwtProviderAudTest.kt` 생성:

```kotlin
package com.pebble.basicAuth.config

import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

class JwtProviderAudTest {

    private lateinit var jwtProvider: JwtProvider

    @BeforeEach
    fun setUp() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubKey = keyPair.public as RSAPublicKey
        val privKey = keyPair.private as RSAPrivateKey

        // JWKSource mock은 생략하고 reflection으로 privateKey 직접 주입
        jwtProvider = JwtProvider(mock(), "http://localhost:8080")
        jwtProvider.accessExpiration = 900000L
        jwtProvider.refreshExpiration = 86400000L

        val field = JwtProvider::class.java.getDeclaredField("privateKey")
        field.isAccessible = true
        field.set(jwtProvider, privKey)
    }

    @Test
    fun `액세스 토큰에 aud 클레임이 포함된다`() {
        val token = jwtProvider.createAccessToken("user1", "ROLE_USER")
        // 서명 검증 없이 payload만 파싱
        val claims = Jwts.parser()
            .unsecured()
            .build()
            .parseUnsecuredClaims(token.split(".").let { "${it[0]}.${it[1]}." })
            .payload

        assertThat(claims.audience).containsExactlyInAnyOrder("task-service", "matching-service")
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.JwtProviderAudTest"
```

Expected: FAIL — aud 클레임이 없어 assertion 실패

- [ ] **Step 3: JwtProvider.kt 수정 — createToken에 audience 추가**

`auth-module/src/main/kotlin/com/pebble/basicAuth/config/JwtProvider.kt` 의 `createToken` 메서드 전체 교체:

```kotlin
private fun createToken(username: String, claims: Map<String, Any>, expiration: Long): String {
    val now = Date()
    val expiryDate = Date(now.time + expiration)

    return Jwts.builder()
        .issuer(issuerUri)
        .subject(username)
        .audience().add(listOf("task-service", "matching-service")).and()
        .claims(claims)
        .issuedAt(now)
        .expiration(expiryDate)
        .signWith(privateKey, Jwts.SIG.RS256)
        .compact()
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.JwtProviderAudTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```
git add auth-module/src/main/kotlin/com/pebble/basicAuth/config/JwtProvider.kt
git add auth-module/src/test/kotlin/com/pebble/basicAuth/config/JwtProviderAudTest.kt
git commit -m "feat(auth): JWT 액세스 토큰에 aud 클레임 추가 (task-service, matching-service)"
```

---

## Task 2: auth-module — RedisUserSessionService 구현

**Files:**
- Create: `auth-module/src/main/kotlin/com/pebble/basicAuth/config/RedisUserSessionService.kt`
- Test: `auth-module/src/test/kotlin/com/pebble/basicAuth/config/RedisUserSessionServiceTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`auth-module/src/test/kotlin/com/pebble/basicAuth/config/RedisUserSessionServiceTest.kt` 생성:

```kotlin
package com.pebble.basicAuth.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisUserSessionServiceTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var service: RedisUserSessionService
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        redisTemplate = mock()
        valueOps = mock()
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
        service = RedisUserSessionService(redisTemplate, objectMapper)
    }

    @Test
    fun `save는 session:{userId} 키로 TTL과 함께 Redis에 저장한다`() {
        val session = RedisUserSessionService.UserSession(
            userId = "user1", roles = listOf("ROLE_USER"), email = "user1@test.com"
        )
        service.save("user1", session, 900L)

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val ttlCaptor = ArgumentCaptor.forClass(Duration::class.java)
        verify(valueOps).set(keyCaptor.capture(), any(), ttlCaptor.capture())

        assertThat(keyCaptor.value).isEqualTo("session:user1")
        assertThat(ttlCaptor.value).isEqualTo(Duration.ofSeconds(900))
    }

    @Test
    fun `delete는 session:{userId} 키를 삭제한다`() {
        service.delete("user1")
        verify(redisTemplate).delete("session:user1")
    }

    @Test
    fun `find는 저장된 세션을 역직렬화해 반환한다`() {
        val session = RedisUserSessionService.UserSession(
            userId = "user1", roles = listOf("ROLE_USER"), email = "user1@test.com"
        )
        whenever(valueOps.get("session:user1")).thenReturn(objectMapper.writeValueAsString(session))

        val result = service.find("user1")

        assertThat(result).isNotNull
        assertThat(result!!.userId).isEqualTo("user1")
        assertThat(result.roles).containsExactly("ROLE_USER")
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.RedisUserSessionServiceTest"
```

Expected: FAIL — `RedisUserSessionService` 클래스 없음

- [ ] **Step 3: RedisUserSessionService.kt 구현**

`auth-module/src/main/kotlin/com/pebble/basicAuth/config/RedisUserSessionService.kt` 생성:

```kotlin
package com.pebble.basicAuth.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RedisUserSessionService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    data class UserSession(
        val userId: String,
        val roles: List<String>,
        val email: String,
        val subscriptionLevel: String = "BASIC",
        val region: String = "KR"
    )

    fun save(userId: String, session: UserSession, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(
            "session:$userId",
            objectMapper.writeValueAsString(session),
            Duration.ofSeconds(ttlSeconds)
        )
    }

    fun delete(userId: String) {
        redisTemplate.delete("session:$userId")
    }

    fun find(userId: String): UserSession? {
        val json = redisTemplate.opsForValue().get("session:$userId") ?: return null
        return objectMapper.readValue(json, UserSession::class.java)
    }
}
```

- [ ] **Step 4: auth-module/src/main/resources/application-dev.yaml에 Redis 설정 추가**

기존 파일 하단에 추가:

```yaml
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

(주의: `spring:` 블록 하위에 들여쓰기를 맞춰서 추가한다)

- [ ] **Step 5: 테스트 통과 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.RedisUserSessionServiceTest"
```

Expected: PASS

- [ ] **Step 6: 커밋**

```
git add auth-module/src/main/kotlin/com/pebble/basicAuth/config/RedisUserSessionService.kt
git add auth-module/src/main/resources/application-dev.yaml
git add auth-module/src/test/kotlin/com/pebble/basicAuth/config/RedisUserSessionServiceTest.kt
git commit -m "feat(auth): RedisUserSessionService 구현 (로그인/로그아웃 세션 관리)"
```

---

## Task 3: auth-module — 로그인/로그아웃 핸들러에 Redis 세션 연결

**Files:**
- Modify: `auth-module/src/main/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandler.kt`
- Modify: `auth-module/src/main/kotlin/com/pebble/basicAuth/config/oauth2/OAuth2SuccessHandler.kt`
- Modify: `auth-module/src/main/kotlin/com/pebble/basicAuth/config/CustomAuthenticationHandler.kt`
- Test: `auth-module/src/test/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandlerTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`auth-module/src/test/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandlerTest.kt` 생성:

```kotlin
package com.pebble.basicAuth.config

import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import com.pebble.basicAuth.persistence.RefreshTokenRepository

class FormLoginSuccessHandlerTest {

    private val jwtProvider: JwtProvider = mock()
    private val refreshTokenRepository: RefreshTokenRepository = mock()
    private val redisUserSessionService: RedisUserSessionService = mock()

    private val handler = FormLoginSuccessHandler(
        jwtProvider, refreshTokenRepository, redisUserSessionService,
        secureCookie = false, gatewayUrl = "http://localhost:8082", redirectPath = "/dashboard"
    )

    @Test
    fun `로그인 성공 시 Redis에 유저 세션을 저장한다`() {
        whenever(jwtProvider.createAccessToken(any(), any())).thenReturn("access.token.value")
        whenever(jwtProvider.createRefreshToken(any())).thenReturn("refresh.token.value")
        whenever(jwtProvider.accessExpiration).thenReturn(900000L)
        whenever(jwtProvider.refreshExpiration).thenReturn(86400000L)

        val auth = UsernamePasswordAuthenticationToken(
            "user1", null, listOf(SimpleGrantedAuthority("ROLE_USER"))
        )
        handler.onAuthenticationSuccess(MockHttpServletRequest(), MockHttpServletResponse(), auth)

        verify(redisUserSessionService).save(
            eq("user1"),
            argThat { userId == "user1" && roles.contains("ROLE_USER") },
            eq(900L)
        )
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.FormLoginSuccessHandlerTest"
```

Expected: FAIL — `FormLoginSuccessHandler`에 `redisUserSessionService` 파라미터 없음

- [ ] **Step 3: FormLoginSuccessHandler.kt 수정**

`auth-module/src/main/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandler.kt` 전체 교체:

```kotlin
package com.pebble.basicAuth.config

import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component

@Component
class FormLoginSuccessHandler(
    private val jwtProvider: JwtProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val redisUserSessionService: RedisUserSessionService,
    @Value("\${auth.cookie.secure:true}") private val secureCookie: Boolean,
    @Value("\${auth.gateway-url}") private val gatewayUrl: String,
    @Value("\${auth.redirect-path}") private val redirectPath: String
) : SimpleUrlAuthenticationSuccessHandler("/") {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val username = authentication.name
        val role = authentication.authorities.firstOrNull()?.authority ?: "ROLE_USER"

        val accessToken  = jwtProvider.createAccessToken(username, role)
        val refreshToken = jwtProvider.createRefreshToken(username)

        refreshTokenRepository.save(username, refreshToken, jwtProvider.refreshExpiration)

        redisUserSessionService.save(
            userId = username,
            session = RedisUserSessionService.UserSession(
                userId = username,
                roles = authentication.authorities.map { it.authority }
            ),
            ttlSeconds = jwtProvider.accessExpiration / 1000
        )

        addCookie(response, "accessToken",  accessToken,  (jwtProvider.accessExpiration  / 1000).toInt())
        addCookie(response, "refreshToken", refreshToken, (jwtProvider.refreshExpiration / 1000).toInt())

        redirectStrategy.sendRedirect(request, response, "$gatewayUrl$redirectPath")
    }

    private fun addCookie(response: HttpServletResponse, name: String, value: String, maxAge: Int) {
        val cookie = Cookie(name, value).apply {
            path      = "/"
            isHttpOnly = true
            secure    = secureCookie
            this.maxAge = maxAge
        }
        response.addCookie(cookie)
    }
}
```

- [ ] **Step 4: OAuth2SuccessHandler.kt 수정**

`auth-module/src/main/kotlin/com/pebble/basicAuth/config/oauth2/OAuth2SuccessHandler.kt` 전체 교체:

```kotlin
package com.pebble.basicAuth.config.oauth2

import com.pebble.basicAuth.config.JwtProvider
import com.pebble.basicAuth.config.RedisUserSessionService
import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component

@Component
class OAuth2SuccessHandler(
    private val jwtProvider: JwtProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val redisUserSessionService: RedisUserSessionService,
    @Value("\${auth.cookie.secure:true}") private val secureCookie: Boolean,
    @Value("\${auth.gateway-url}") private val gatewayUrl: String,
    @Value("\${auth.redirect-path}") private val redirectPath: String
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oAuth2User = authentication.principal as CustomOAuth2User
        val username = oAuth2User.user.username
        val role = oAuth2User.user.role.name

        val accessToken  = jwtProvider.createAccessToken(username, role)
        val refreshToken = jwtProvider.createRefreshToken(username)

        refreshTokenRepository.save(username, refreshToken, jwtProvider.refreshExpiration)

        redisUserSessionService.save(
            userId = username,
            session = RedisUserSessionService.UserSession(
                userId = username,
                roles = listOf(role),
                email = oAuth2User.getAttribute("email") ?: ""
            ),
            ttlSeconds = jwtProvider.accessExpiration / 1000
        )

        addCookie(response, "accessToken",  accessToken,  (jwtProvider.accessExpiration  / 1000).toInt())
        addCookie(response, "refreshToken", refreshToken, (jwtProvider.refreshExpiration / 1000).toInt())

        redirectStrategy.sendRedirect(request, response, "$gatewayUrl$redirectPath")
    }

    private fun addCookie(response: HttpServletResponse, name: String, value: String, maxAge: Int) {
        val cookie = Cookie(name, value).apply {
            path      = "/"
            isHttpOnly = true
            secure    = secureCookie
            this.maxAge = maxAge
        }
        response.addCookie(cookie)
    }
}
```

- [ ] **Step 5: CustomAuthenticationHandler.kt 수정 — 로그아웃 시 Redis 세션 삭제**

`auth-module/src/main/kotlin/com/pebble/basicAuth/config/CustomAuthenticationHandler.kt` 전체 교체:

```kotlin
package com.pebble.basicAuth.config

import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler
import org.springframework.stereotype.Component

@Component
class CustomAuthenticationHandler(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val redisUserSessionService: RedisUserSessionService
) : AuthenticationEntryPoint, LogoutSuccessHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        val accept = request.getHeader("Accept")
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect("/login.html")
        } else {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"message\":\"인증이 필요합니다.\"}")
        }
    }

    override fun onLogoutSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?
    ) {
        if (authentication != null && authentication.name != null) {
            refreshTokenRepository.deleteByUsername(authentication.name)
            redisUserSessionService.delete(authentication.name)
        }

        response.status = HttpServletResponse.SC_OK
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("{\"message\":\"로그아웃 되었습니다.\"}")
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```
./gradlew :auth-module:test --tests "com.pebble.basicAuth.config.FormLoginSuccessHandlerTest"
```

Expected: PASS

- [ ] **Step 7: 커밋**

```
git add auth-module/src/main/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandler.kt
git add auth-module/src/main/kotlin/com/pebble/basicAuth/config/oauth2/OAuth2SuccessHandler.kt
git add auth-module/src/main/kotlin/com/pebble/basicAuth/config/CustomAuthenticationHandler.kt
git add auth-module/src/test/kotlin/com/pebble/basicAuth/config/FormLoginSuccessHandlerTest.kt
git commit -m "feat(auth): 로그인/로그아웃 핸들러에 Redis 세션 저장/삭제 연결"
```

---

## Task 4: task-module — SecurityConfig 강화 (aud 검증 + JWKS 캐시 + @EnableMethodSecurity)

**Files:**
- Modify: `task-module/src/main/java/com/pebble/task/config/SecurityConfig.java`
- Test: `task-module/src/test/java/com/pebble/task/config/SecurityConfigTest.java`

- [ ] **Step 1: 테스트 디렉토리 생성 확인**

```
ls task-module/src/test/java/com/pebble/task/
```

없으면 생성:
```
mkdir -p task-module/src/test/java/com/pebble/task/config
```

- [ ] **Step 2: 실패 테스트 작성**

`task-module/src/test/java/com/pebble/task/config/SecurityConfigTest.java` 생성:

```java
package com.pebble.task.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void 인증_없이_API_호출시_401_반환() throws Exception {
        mockMvc.perform(get("/api/tasks"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void health_엔드포인트는_인증_없이_접근_가능() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: 테스트 실행 — 현재 상태 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.config.SecurityConfigTest"
```

Expected: PASS (현재도 인증 없이 401은 동작) — 테스트가 이미 통과하면 다음 step으로 진행

- [ ] **Step 4: SecurityConfig.java 전체 교체**

`task-module/src/main/java/com/pebble/task/config/SecurityConfig.java` 전체 교체:

```java
package com.pebble.task.config;

import com.nimbusds.jose.jwk.source.DefaultJWKSetCache;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

@EnableMethodSecurity
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/", "/index.html", "/static/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
                .authenticationEntryPoint(jsonAuthenticationEntryPoint())
            );
        http.headers(headers -> headers.frameOptions(frame -> frame.disable()));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri
    ) throws MalformedURLException {
        var cache = new DefaultJWKSetCache(10, 2, TimeUnit.MINUTES);
        JWKSource<SecurityContext> jwkSource = new RemoteJWKSet<>(new URL(jwkSetUri), null, cache);
        var decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();

        var audienceValidator = (org.springframework.security.oauth2.core.OAuth2TokenValidator<
                org.springframework.security.oauth2.jwt.Jwt>) token -> {
            if (token.getAudience().contains("task-service"))
                return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required audience 'task-service' not present", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), audienceValidator));
        return decoder;
    }

    @Bean
    public AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            String json = "{\"status\": 401, \"error\": \"Unauthorized\", " +
                "\"message\": \"인증이 필요한 서비스입니다. 게이트웨이 포털에서 로그인을 먼저 진행해주세요.\", " +
                "\"path\": \"" + request.getRequestURI() + "\"}";
            response.getWriter().write(json);
        };
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.config.SecurityConfigTest"
```

Expected: PASS

- [ ] **Step 6: 커밋**

```
git add task-module/src/main/java/com/pebble/task/config/SecurityConfig.java
git add task-module/src/test/java/com/pebble/task/config/SecurityConfigTest.java
git commit -m "feat(task): SecurityConfig 강화 - @EnableMethodSecurity, JwtDecoder aud 검증, JWKS 캐시 10분"
```

---

## Task 5: task-module — TaskController @PreAuthorize 추가

**Files:**
- Modify: `task-module/src/main/java/com/pebble/task/controller/TaskController.java`
- Test: `task-module/src/test/java/com/pebble/task/controller/TaskControllerAuthTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`task-module/src/test/java/com/pebble/task/controller/TaskControllerAuthTest.java` 생성:

```java
package com.pebble.task.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerAuthTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void ROLE_USER로_태스크_목록_조회_성공() throws Exception {
        // @WithMockUser는 @AuthenticationPrincipal Jwt를 null로 만들기 때문에
        // jwt() 포스트프로세서로 JwtAuthenticationToken을 직접 주입한다
        mockMvc.perform(get("/api/tasks")
                .with(jwt()
                    .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                    .jwt(j -> j.subject("user1"))))
               .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "ROLE_GUEST")
    void ROLE_GUEST는_태스크_목록_조회_403() throws Exception {
        // 403은 컨트롤러에 도달하기 전에 처리되므로 @WithMockUser 사용 가능
        mockMvc.perform(get("/api/tasks"))
               .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.controller.TaskControllerAuthTest"
```

Expected: `ROLE_GUEST` 테스트가 FAIL — 현재 `@PreAuthorize` 없어서 200 반환

- [ ] **Step 3: TaskController.java 수정 — @PreAuthorize 추가**

`task-module/src/main/java/com/pebble/task/controller/TaskController.java` 전체 교체:

```java
package com.pebble.task.controller;

import com.pebble.task.domain.Task;
import com.pebble.task.domain.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @PreAuthorize("hasAuthority('ROLE_USER')")
    @PostMapping
    public ResponseEntity<Task> createTask(@RequestBody Task task, @AuthenticationPrincipal Jwt jwt) {
        task.setUserId(jwt.getSubject());
        return ResponseEntity.ok(taskService.createTask(task));
    }

    @PreAuthorize("hasAuthority('ROLE_USER')")
    @GetMapping
    public ResponseEntity<List<Task>> getMyTasks(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(taskService.getMyTasks(jwt.getSubject()));
    }

    @PreAuthorize("hasAuthority('ROLE_USER')")
    @PatchMapping("/{taskId}")
    public ResponseEntity<Task> updateTaskStatus(
            @PathVariable Long taskId,
            @RequestParam boolean completed,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(taskService.updateTaskStatus(taskId, jwt.getSubject(), completed));
    }

    @PreAuthorize("hasAuthority('ROLE_USER')")
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long taskId, @AuthenticationPrincipal Jwt jwt) {
        taskService.deleteTask(taskId, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
```

(주의: 기존 `/access-info` 엔드포인트는 개발 편의용이므로 제거한다)

- [ ] **Step 4: 테스트 통과 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.controller.TaskControllerAuthTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```
git add task-module/src/main/java/com/pebble/task/controller/TaskController.java
git add task-module/src/test/java/com/pebble/task/controller/TaskControllerAuthTest.java
git commit -m "feat(task): TaskController 모든 엔드포인트에 ROLE_USER 인가 추가"
```

---

## Task 6: task-module — MatchingClient 구현

**Files:**
- Create: `task-module/src/main/java/com/pebble/task/config/MatchingClient.java`
- Modify: `task-module/src/main/resources/application.yaml`
- Modify: `task-module/src/main/resources/application-dev.yaml`
- Test: `task-module/src/test/java/com/pebble/task/config/MatchingClientTest.java`

- [ ] **Step 1: application.yaml에 matching URL 추가**

`task-module/src/main/resources/application.yaml` 하단에 추가:

```yaml
internal:
  matching-service:
    url: ${INTERNAL_MATCHING_SERVICE_URL:http://matching-service:8081}
```

- [ ] **Step 2: application-dev.yaml에 matching URL 추가**

`task-module/src/main/resources/application-dev.yaml` 하단에 추가:

```yaml
internal:
  matching-service:
    url: http://localhost:8081
```

- [ ] **Step 3: 실패 테스트 작성**

`task-module/src/test/java/com/pebble/task/config/MatchingClientTest.java` 생성:

```java
package com.pebble.task.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MatchingClientTest {

    private MockRestServiceServer server;
    private MatchingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MatchingClient(builder, "http://matching-service:8081");
    }

    @Test
    void getMyMatches는_Authorization_헤더를_포워딩하고_올바른_경로를_호출한다() {
        server.expect(requestTo("http://matching-service:8081/internal/matching/status/user123"))
              .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test.jwt.token"))
              .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<MatchingClient.MatchDto> result = client.getMyMatches("user123", "Bearer test.jwt.token");

        assertThat(result).isNotNull();
        server.verify();
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.config.MatchingClientTest"
```

Expected: FAIL — `MatchingClient` 클래스 없음

- [ ] **Step 5: MatchingClient.java 구현**

`task-module/src/main/java/com/pebble/task/config/MatchingClient.java` 생성:

```java
package com.pebble.task.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class MatchingClient {

    private final RestClient restClient;

    public MatchingClient(
        RestClient.Builder builder,
        @Value("${internal.matching-service.url}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<MatchDto> getMyMatches(String userId, String bearerToken) {
        return restClient.get()
            .uri("/internal/matching/status/{userId}", userId)
            .header(HttpHeaders.AUTHORIZATION, bearerToken)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    }

    public record MatchDto(String id, Long userA, Long userB, String createdAt) {}
}
```

- [ ] **Step 6: 테스트 통과 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.config.MatchingClientTest"
```

Expected: PASS

- [ ] **Step 7: 커밋**

```
git add task-module/src/main/java/com/pebble/task/config/MatchingClient.java
git add task-module/src/main/resources/application.yaml
git add task-module/src/main/resources/application-dev.yaml
git add task-module/src/test/java/com/pebble/task/config/MatchingClientTest.java
git commit -m "feat(task): MatchingClient 구현 - 인클러스터 RestClient + JWT 전파"
```

---

## Task 7: task-module — MatchingStatusController 추가

**Files:**
- Create: `task-module/src/main/java/com/pebble/task/controller/MatchingStatusController.java`
- Test: `task-module/src/test/java/com/pebble/task/controller/MatchingStatusControllerTest.java`

- [ ] **Step 1: 실패 테스트 작성**

`task-module/src/test/java/com/pebble/task/controller/MatchingStatusControllerTest.java` 생성:

```java
package com.pebble.task.controller;

import com.pebble.task.config.MatchingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MatchingStatusControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean MatchingClient matchingClient;

    @Test
    @WithMockUser(authorities = "ROLE_USER", username = "user1")
    void ROLE_USER는_매칭_상태를_조회할_수_있다() throws Exception {
        when(matchingClient.getMyMatches(any(), any()))
            .thenReturn(List.of(new MatchingClient.MatchDto("match1", 1L, 2L, "2026-05-10T10:00:00")));

        mockMvc.perform(get("/api/tasks/matching/status")
                .header("Authorization", "Bearer test.token"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value("match1"));
    }

    @Test
    void 인증_없이_매칭_상태_조회_401() throws Exception {
        mockMvc.perform(get("/api/tasks/matching/status"))
               .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.controller.MatchingStatusControllerTest"
```

Expected: FAIL — 404 (컨트롤러 없음)

- [ ] **Step 3: MatchingStatusController.java 구현**

`task-module/src/main/java/com/pebble/task/controller/MatchingStatusController.java` 생성:

```java
package com.pebble.task.controller;

import com.pebble.task.config.MatchingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks/matching")
@RequiredArgsConstructor
public class MatchingStatusController {
    private final MatchingClient matchingClient;

    @PreAuthorize("hasAuthority('ROLE_USER')")
    @GetMapping("/status")
    public ResponseEntity<List<MatchingClient.MatchDto>> getMyMatchingStatus(
        @AuthenticationPrincipal Jwt jwt,
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization
    ) {
        return ResponseEntity.ok(matchingClient.getMyMatches(jwt.getSubject(), authorization));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
./gradlew :task-module:test --tests "com.pebble.task.controller.MatchingStatusControllerTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```
git add task-module/src/main/java/com/pebble/task/controller/MatchingStatusController.java
git add task-module/src/test/java/com/pebble/task/controller/MatchingStatusControllerTest.java
git commit -m "feat(task): MatchingStatusController 추가 - task→matching 인클러스터 조회"
```

---

## Task 8: matching-module — InternalMatchingController 추가

**Files:**
- Create: `matching-module/src/main/kotlin/com/pebble/matching/controller/InternalMatchingController.kt`
- Test: `matching-module/src/test/kotlin/com/pebble/matching/controller/InternalMatchingControllerTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`matching-module/src/test/kotlin/com/pebble/matching/controller/InternalMatchingControllerTest.kt` 생성:

```kotlin
package com.pebble.matching.controller

import com.pebble.matching.domain.MatchingService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
class InternalMatchingControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockBean lateinit var jwtDecoder: JwtDecoder
    @MockBean lateinit var matchingService: MatchingService

    @Test
    fun `인증된 사용자는 내부 매칭 상태를 조회할 수 있다`() {
        whenever(matchingService.getMyMatches(1L)).thenReturn(emptyList())

        // InternalMatchingController는 @PathVariable만 사용하므로 @WithMockUser도 가능하지만
        // 일관성을 위해 jwt() 포스트프로세서 사용
        mockMvc.perform(
            get("/internal/matching/status/1")
                .with(SecurityMockMvcRequestPostProcessors.jwt()
                    .authorities(SimpleGrantedAuthority("ROLE_USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
    }

    @Test
    fun `인증 없이 내부 엔드포인트 호출 시 401`() {
        mockMvc.perform(get("/internal/matching/status/1"))
            .andExpect(status().isUnauthorized)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :matching-module:test --tests "com.pebble.matching.controller.InternalMatchingControllerTest"
```

Expected: FAIL — 404 (컨트롤러 없음) 또는 401 (보안 설정에 따라)

- [ ] **Step 3: InternalMatchingController.kt 구현**

`matching-module/src/main/kotlin/com/pebble/matching/controller/InternalMatchingController.kt` 생성:

```kotlin
package com.pebble.matching.controller

import com.pebble.matching.domain.ChatMatch
import com.pebble.matching.domain.MatchingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/matching")
class InternalMatchingController(
    private val matchingService: MatchingService
) {
    @GetMapping("/status/{userId}")
    fun getMatchingStatus(@PathVariable userId: Long): ResponseEntity<List<ChatMatch>> {
        return ResponseEntity.ok(matchingService.getMyMatches(userId))
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
./gradlew :matching-module:test --tests "com.pebble.matching.controller.InternalMatchingControllerTest"
```

Expected: PASS

- [ ] **Step 5: 커밋**

```
git add matching-module/src/main/kotlin/com/pebble/matching/controller/InternalMatchingController.kt
git add matching-module/src/test/kotlin/com/pebble/matching/controller/InternalMatchingControllerTest.kt
git commit -m "feat(matching): 서비스 간 통신용 InternalMatchingController 추가"
```

---

## Task 9: matching-module — SecurityConfig 강화 (aud 검증 + JWKS 캐시 + ROLE_USER + internal 인가)

**Files:**
- Modify: `matching-module/src/main/kotlin/com/pebble/matching/config/MatchingSecurityConfig.kt`
- Test: `matching-module/src/test/kotlin/com/pebble/matching/config/MatchingSecurityConfigTest.kt`

- [ ] **Step 1: 실패 테스트 작성**

`matching-module/src/test/kotlin/com/pebble/matching/config/MatchingSecurityConfigTest.kt` 생성:

```kotlin
package com.pebble.matching.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class MatchingSecurityConfigTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockBean lateinit var jwtDecoder: JwtDecoder

    @Test
    @WithMockUser(authorities = ["ROLE_GUEST"])
    fun `ROLE_GUEST는 매칭 API에 접근 불가 - 403`() {
        mockMvc.perform(get("/api/v1/matching/recommendations"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(authorities = ["ROLE_USER"])
    fun `ROLE_USER는 매칭 API에 접근 가능`() {
        mockMvc.perform(get("/api/v1/matching/matches"))
            .andExpect(status().isOk)
    }

    @Test
    @WithMockUser(authorities = ["ROLE_USER"])
    fun `ROLE_USER는 admin internal API에 접근 불가 - 403`() {
        mockMvc.perform(get("/internal/admin/users"))
            .andExpect(status().isForbidden)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```
./gradlew :matching-module:test --tests "com.pebble.matching.config.MatchingSecurityConfigTest"
```

Expected: `ROLE_GUEST` 테스트가 FAIL — 현재 `/api/v1/matching/**`에 `ROLE_USER` 인가 없음

- [ ] **Step 3: MatchingSecurityConfig.kt 전체 교체**

`matching-module/src/main/kotlin/com/pebble/matching/config/MatchingSecurityConfig.kt` 전체 교체:

```kotlin
package com.pebble.matching.config

import com.nimbusds.jose.jwk.source.DefaultJWKSetCache
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import java.net.URL
import java.util.concurrent.TimeUnit

@EnableMethodSecurity
@Configuration
class MatchingSecurityConfig {

    @Bean
    fun matchingSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health", "/", "/index.html", "/static/**").permitAll()
                    .requestMatchers("/internal/admin/**").hasAuthority("ROLE_ADMIN")
                    .requestMatchers("/internal/matching/**").authenticated()
                    .requestMatchers("/api/v1/matching/**").hasAuthority("ROLE_USER")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                oauth2.authenticationEntryPoint(jsonAuthenticationEntryPoint())
            }
            .build()
    }

    @Bean
    fun jwtDecoder(
        @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") jwkSetUri: String
    ): JwtDecoder {
        val cache = DefaultJWKSetCache(10, 2, TimeUnit.MINUTES)
        val jwkSource = RemoteJWKSet<com.nimbusds.jose.proc.SecurityContext>(URL(jwkSetUri), null, cache)
        val decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build()

        val audienceValidator = { token: Jwt ->
            if (token.audience.contains("matching-service"))
                OAuth2TokenValidatorResult.success()
            else
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_token", "Required audience 'matching-service' not present", null))
        }
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(JwtValidators.createDefault(), audienceValidator))
        return decoder
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val grantedAuthoritiesConverter = JwtGrantedAuthoritiesConverter()
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles")
        grantedAuthoritiesConverter.setAuthorityPrefix("")
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter)
        return converter
    }

    @Bean
    fun jsonAuthenticationEntryPoint(): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { request, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            val json = """{"status": 401, "error": "Unauthorized", "message": "인증이 필요한 매칭 서비스입니다. 게이트웨이 포털에서 로그인을 먼저 진행해주세요.", "path": "${request.requestURI}"}"""
            response.writer.write(json)
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```
./gradlew :matching-module:test --tests "com.pebble.matching.config.MatchingSecurityConfigTest"
```

Expected: PASS

- [ ] **Step 5: 전체 matching-module 테스트 확인**

```
./gradlew :matching-module:test
```

Expected: 모든 테스트 PASS

- [ ] **Step 6: 커밋**

```
git add matching-module/src/main/kotlin/com/pebble/matching/config/MatchingSecurityConfig.kt
git add matching-module/src/test/kotlin/com/pebble/matching/config/MatchingSecurityConfigTest.kt
git commit -m "feat(matching): SecurityConfig 강화 - aud 검증, JWKS 캐시, ROLE_USER 인가, internal 경로 보호"
```

---

## Task 10: Docker Compose 업데이트

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: docker-compose.yml의 task-service 수정**

`docker-compose.yml`의 `task-service` 블록 전체 교체:

```yaml
  task-service:
    build:
      context: .
      dockerfile: task-module/Dockerfile
    container_name: task-service
    ports:
      - "8082:8082"
    environment:
      - INTERNAL_MATCHING_SERVICE_URL=http://matching-service:8081
    depends_on:
      auth-service:
        condition: service_started
      matching-service:
        condition: service_started
```

- [ ] **Step 2: Docker Compose 구성 검증**

```
docker compose config --quiet && echo "OK"
```

Expected: `OK` (YAML 문법 오류 없음)

- [ ] **Step 3: 전체 빌드 확인**

```
./gradlew :task-module:build :matching-module:build :auth-module:build
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```
git add docker-compose.yml
git commit -m "chore: task-service에 matching-service 인클러스터 URL 환경변수 및 depends_on 추가"
```

---

## 최종 검증

- [ ] **전체 테스트 실행**

```
./gradlew test
```

Expected: 모든 모듈 BUILD SUCCESSFUL, 테스트 PASS

- [ ] **원칙 체크리스트 최종 확인**

| 원칙 | 구현 위치 | 확인 방법 |
|---|---|---|
| 1. 인클러스터 통신 | `MatchingClient` + `docker-compose.yml` env | task-service → `http://matching-service:8081` (GW 미경유) |
| 2. ID Token 거부 | `SecurityConfig.jwtDecoder()` (task/matching) | aud=task-service/matching-service 없는 토큰 → 401 |
| 3. GW는 인증만 | `@PreAuthorize` in task + matching SecurityConfig | ROLE_GUEST로 요청 → 403 |
| 4. JWKS 캐싱 | `DefaultJWKSetCache(10, 2, MINUTES)` (양 모듈) | NimbusJwtDecoder에 10분 TTL 명시 |
| 5. RBAC 구현 | `@PreAuthorize("hasAuthority('ROLE_USER')")` | Redis 세션에 ABAC용 필드 준비 (subscriptionLevel, region) |
