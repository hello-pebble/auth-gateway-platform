# Admin Management System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 별도 admin-module(포트 8085)을 생성하여 운영 관리(사용자 관리, 매칭 관리)와 데이터 분석(통계) API를 ROLE_ADMIN JWT 보호 하에 제공한다.

**Architecture:** admin-module은 `/api/v1/admin/**` 엔드포인트를 gateway를 통해 노출하며, matching-module의 `/internal/admin/**` 내부 API를 직접(gateway 우회) 호출한다. auth-module JWT의 `roles` 클레임에서 `ROLE_ADMIN`을 추출하며, 두 모듈 모두 `JwtGrantedAuthoritiesConverter`로 커스텀 권한 변환을 적용한다. 모든 저장소는 인메모리 유지.

**Tech Stack:** Kotlin 2.2.0, Spring Boot 3.5.3, Spring Security OAuth2 Resource Server, RestClient (Spring 6.1), JUnit 5, Mockito-Kotlin 5.4.0

---

## File Map

### matching-module (수정)
| 파일 | 변경 |
|---|---|
| `domain/MatchingModels.kt` | `MatchingProfile`에 `isBlocked: Boolean` 추가 |
| `infrastructure/InMemoryMatchingStore.kt` | `getAllProfiles`, `getAllRankings`, `getAllMatches`, `deleteMatch`, `blockUser`, `isBlocked` 추가; `getAllExposedUsers`에 차단 필터 추가 |
| `domain/MatchingService.kt` | 추천·순위 부여 시 차단 사용자 필터링 |
| `config/MatchingSecurityConfig.kt` | `JwtGrantedAuthoritiesConverter` 등록, `/internal/admin/**` → `ROLE_ADMIN` 보호 |
| `controller/AdminInternalController.kt` | 신규 - `/internal/admin/**` 엔드포인트 |
| `MatchingServiceTest.kt` | 차단 관련 테스트 추가 |

### admin-module (신규, 포트 8085)
| 파일 | 역할 |
|---|---|
| `build.gradle.kts` | 빌드 설정 |
| `AdminApplication.kt` | 진입점 |
| `config/AdminSecurityConfig.kt` | ROLE_ADMIN JWT 검증 |
| `dto/AdminDtos.kt` | 요청/응답 DTO |
| `client/MatchingInternalClient.kt` | RestClient → matching-module 내부 API 호출 |
| `service/AdminService.kt` | 비즈니스 로직, 통계 집계 |
| `controller/AdminController.kt` | REST 엔드포인트 |
| `resources/application.yaml` | 포트, JWKS, matching URL 설정 |
| `resources/application-dev.yaml` | 로컬 개발용 설정 |
| `test/.../AdminServiceTest.kt` | AdminService 단위 테스트 |

### gateway-service (수정)
| 파일 | 변경 |
|---|---|
| `resources/application.yaml` | `/api/v1/admin/**` → admin-module 라우팅 추가 |

### 루트
| 파일 | 변경 |
|---|---|
| `settings.gradle.kts` | `include("admin-module")` 추가 |

---

## Task 1: matching-module — MatchingProfile isBlocked 추가 + InMemoryMatchingStore 확장

**Files:**
- Modify: `matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingModels.kt`
- Modify: `matching-module/src/main/kotlin/com/pebble/matching/infrastructure/InMemoryMatchingStore.kt`

- [ ] **Step 1: MatchingProfile에 isBlocked 필드 추가**

`matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingModels.kt` 전체 교체:
```kotlin
package com.pebble.matching.domain

import java.time.LocalDateTime
import java.util.UUID

data class MatchingProfile(
    val userId: Long,
    val isExposed: Boolean = false,
    val isBlocked: Boolean = false,
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class MatchRanking(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(rank in 1..3) { "순위는 1위에서 3위 사이여야 합니다." }
    }
}

data class ChatMatch(
    val id: String = UUID.randomUUID().toString(),
    val userA: Long,
    val userB: Long,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface UserProvider {
    fun getUserInfo(userId: Long): ExternalUser?
}

data class ExternalUser(val id: Long, val username: String)
```

- [ ] **Step 2: InMemoryMatchingStore admin 메서드 추가**

`matching-module/src/main/kotlin/com/pebble/matching/infrastructure/InMemoryMatchingStore.kt` 전체 교체:
```kotlin
package com.pebble.matching.infrastructure

import com.pebble.matching.domain.ChatMatch
import com.pebble.matching.domain.MatchRanking
import com.pebble.matching.domain.MatchingProfile
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryMatchingStore {
    private val profiles = ConcurrentHashMap<Long, MatchingProfile>()
    private val rankings = ConcurrentHashMap<Pair<Long, Long>, MatchRanking>()
    private val matches = ConcurrentHashMap<String, ChatMatch>()

    fun saveProfile(profile: MatchingProfile) { profiles[profile.userId] = profile }
    fun getProfile(userId: Long): MatchingProfile? = profiles[userId]

    fun getAllExposedUsers(): List<Long> =
        profiles.filter { it.value.isExposed && !it.value.isBlocked }.keys.toList()

    fun getAllProfiles(): List<MatchingProfile> = profiles.values.toList()

    fun blockUser(userId: Long, isBlocked: Boolean) {
        val existing = profiles.getOrDefault(userId, MatchingProfile(userId))
        profiles[userId] = existing.copy(isBlocked = isBlocked, updatedAt = LocalDateTime.now())
    }

    fun isBlocked(userId: Long): Boolean = profiles[userId]?.isBlocked ?: false

    fun saveRanking(ranking: MatchRanking) {
        rankings[Pair(ranking.fromUserId, ranking.toUserId)] = ranking
    }

    fun getRanking(fromUserId: Long, toUserId: Long): MatchRanking? =
        rankings[Pair(fromUserId, toUserId)]

    fun getAllRankings(): List<MatchRanking> = rankings.values.toList()

    fun saveMatch(match: ChatMatch) { matches[match.id] = match }

    fun getMatchesForUser(userId: Long): List<ChatMatch> =
        matches.values.filter { it.userA == userId || it.userB == userId }

    fun getAllMatches(): List<ChatMatch> = matches.values.toList()

    fun deleteMatch(matchId: String) { matches.remove(matchId) }

    fun alreadyMatched(userA: Long, userB: Long): Boolean =
        matches.values.any {
            (it.userA == userA && it.userB == userB) || (it.userA == userB && it.userB == userA)
        }
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :matching-module:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingModels.kt
git add matching-module/src/main/kotlin/com/pebble/matching/infrastructure/InMemoryMatchingStore.kt
git commit -m "feat(matching): add isBlocked to MatchingProfile and expand InMemoryMatchingStore"
```

---

## Task 2: matching-module — MatchingService 차단 필터링 (TDD)

**Files:**
- Modify: `matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingService.kt`
- Test: `matching-module/src/test/kotlin/com/pebble/matching/MatchingServiceTest.kt`

- [ ] **Step 1: 차단 사용자 테스트 작성**

`MatchingServiceTest.kt`의 기존 테스트 아래에 추가:
```kotlin
@Test
fun `Blocked user is excluded from recommendations`() {
    val userA = 1L
    val userB = 2L

    matchingService.updateExposure(userB, true)
    store.blockUser(userB, true)
    `when`(userProvider.getUserInfo(userB)).thenReturn(ExternalUser(userB, "userB"))

    val recommendations = matchingService.getRecommendations(userA)

    assertEquals(0, recommendations.size)
}

@Test
fun `Blocked user cannot receive ranking`() {
    val fromUserId = 1L
    val blockedUserId = 2L

    store.blockUser(blockedUserId, true)

    assertThrows(IllegalArgumentException::class.java) {
        matchingService.rankUser(fromUserId, blockedUserId, 1)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
./gradlew :matching-module:test
```
Expected: `Blocked user cannot receive ranking` FAIL (rankUser가 차단 확인 안 함)  
`Blocked user is excluded from recommendations` PASS (store가 이미 필터링)

- [ ] **Step 3: MatchingService에 차단 검증 추가**

`matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingService.kt` 전체 교체:
```kotlin
package com.pebble.matching.domain

import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class MatchingService(
    private val store: InMemoryMatchingStore,
    private val userProvider: UserProvider
) {
    fun getRecommendations(userId: Long): List<RecommendedUser> {
        val exposedUserIds = store.getAllExposedUsers()
            .filter { it != userId }
            .shuffled()
            .take(5)

        return exposedUserIds.mapNotNull { id ->
            userProvider.getUserInfo(id)?.let { user ->
                RecommendedUser(user.id, user.username)
            }
        }
    }

    fun rankUser(fromUserId: Long, toUserId: Long, rank: Int): MatchResult {
        if (fromUserId == toUserId) throw IllegalArgumentException("본인에게 순위를 부여할 수 없습니다.")
        if (store.isBlocked(toUserId)) throw IllegalArgumentException("차단된 사용자에게 순위를 부여할 수 없습니다.")

        val ranking = MatchRanking(fromUserId, toUserId, rank)
        store.saveRanking(ranking)

        if (store.alreadyMatched(fromUserId, toUserId)) {
            return MatchResult(isMatched = true, message = "이미 매칭된 회원입니다.")
        }

        val oppositeRank = store.getRanking(toUserId, fromUserId)
        if (oppositeRank != null && oppositeRank.rank <= 3) {
            val newMatch = ChatMatch(userA = fromUserId, userB = toUserId)
            store.saveMatch(newMatch)
            return MatchResult(isMatched = true, matchId = newMatch.id, message = "축하합니다! 상호 매칭되었습니다.")
        }

        return MatchResult(isMatched = false, message = "상대방의 선택을 기다리고 있습니다.")
    }

    fun updateExposure(userId: Long, isExposed: Boolean) {
        val profile = MatchingProfile(userId, isExposed, updatedAt = LocalDateTime.now())
        store.saveProfile(profile)
    }

    fun getMyMatches(userId: Long): List<ChatMatch> = store.getMatchesForUser(userId)
}

data class RecommendedUser(val id: Long, val username: String)
data class MatchResult(val isMatched: Boolean, val matchId: String? = null, val message: String)
```

- [ ] **Step 4: 테스트 모두 통과 확인**

```bash
./gradlew :matching-module:test
```
Expected: 4개 테스트 모두 PASS

- [ ] **Step 5: 커밋**

```bash
git add matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingService.kt
git add matching-module/src/test/kotlin/com/pebble/matching/MatchingServiceTest.kt
git commit -m "feat(matching): filter blocked users from recommendations and rankings"
```

---

## Task 3: matching-module — AdminInternalController 생성 + SecurityConfig 업데이트

**Files:**
- Create: `matching-module/src/main/kotlin/com/pebble/matching/controller/AdminInternalController.kt`
- Modify: `matching-module/src/main/kotlin/com/pebble/matching/config/MatchingSecurityConfig.kt`

- [ ] **Step 1: MatchingSecurityConfig에 JwtAuthenticationConverter + /internal/admin/** 보호 추가**

`matching-module/src/main/kotlin/com/pebble/matching/config/MatchingSecurityConfig.kt` 전체 교체:
```kotlin
package com.pebble.matching.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.AuthenticationEntryPoint

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
                    .requestMatchers("/api/v1/matching/**").authenticated()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                oauth2.authenticationEntryPoint(jsonAuthenticationEntryPoint())
            }
            .build()
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

- [ ] **Step 2: AdminInternalController 생성**

`matching-module/src/main/kotlin/com/pebble/matching/controller/AdminInternalController.kt` 생성:
```kotlin
package com.pebble.matching.controller

import com.pebble.matching.domain.MatchingProfile
import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

@RestController
@RequestMapping("/internal/admin")
class AdminInternalController(
    private val store: InMemoryMatchingStore
) {
    @GetMapping("/users")
    fun getAllUsers(): ResponseEntity<List<UserDto>> =
        ResponseEntity.ok(store.getAllProfiles().map {
            UserDto(it.userId, it.isExposed, it.isBlocked, it.updatedAt.toString())
        })

    @PatchMapping("/users/{userId}/exposure")
    fun updateExposure(
        @PathVariable userId: Long,
        @RequestBody request: ExposureRequest
    ): ResponseEntity<Void> {
        val existing = store.getProfile(userId) ?: MatchingProfile(userId)
        store.saveProfile(existing.copy(isExposed = request.isExposed, updatedAt = LocalDateTime.now()))
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/users/{userId}/block")
    fun blockUser(
        @PathVariable userId: Long,
        @RequestBody request: BlockRequest
    ): ResponseEntity<Void> {
        store.blockUser(userId, request.isBlocked)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/matches")
    fun getAllMatches(): ResponseEntity<List<MatchDto>> =
        ResponseEntity.ok(store.getAllMatches().map {
            MatchDto(it.id, it.userA, it.userB, it.createdAt.toString())
        })

    @DeleteMapping("/matches/{matchId}")
    fun deleteMatch(@PathVariable matchId: String): ResponseEntity<Void> {
        store.deleteMatch(matchId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/rankings")
    fun getAllRankings(): ResponseEntity<List<RankingDto>> =
        ResponseEntity.ok(store.getAllRankings().map {
            RankingDto(it.fromUserId, it.toUserId, it.rank, it.createdAt.toString())
        })

    data class UserDto(val userId: Long, val isExposed: Boolean, val isBlocked: Boolean, val updatedAt: String)
    data class MatchDto(val matchId: String, val userA: Long, val userB: Long, val createdAt: String)
    data class RankingDto(val fromUserId: Long, val toUserId: Long, val rank: Int, val createdAt: String)
    data class ExposureRequest(val isExposed: Boolean)
    data class BlockRequest(val isBlocked: Boolean)
}
```

- [ ] **Step 3: 빌드 + 테스트 확인**

```bash
./gradlew :matching-module:build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add matching-module/src/main/kotlin/com/pebble/matching/
git commit -m "feat(matching): add AdminInternalController and ROLE_ADMIN JWT authority converter"
```

---

## Task 4: admin-module 빌드 설정

**Files:**
- Create: `admin-module/build.gradle.kts`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: settings.gradle.kts에 admin-module 추가**

`settings.gradle.kts`의 마지막 줄 뒤에 추가:
```kotlin
include("admin-module")
```

최종 파일:
```kotlin
rootProject.name = "base-auth"

include("auth-module")
include("matching-module")
include("gateway-service")
include("task-module")
include("preview-module")
include("admin-module")
```

- [ ] **Step 2: admin-module/build.gradle.kts 생성**

```kotlin
plugins {
    id("org.springframework.boot")
    kotlin("plugin.spring")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    testImplementation("org.springframework.security:spring-security-test")
}
```

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :admin-module:dependencies
```
Expected: 의존성 트리 출력, BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add settings.gradle.kts admin-module/build.gradle.kts
git commit -m "chore: add admin-module to build"
```

---

## Task 5: admin-module 기반 구성 (Application, Security, DTOs, Config)

**Files:**
- Create: `admin-module/src/main/kotlin/com/pebble/admin/AdminApplication.kt`
- Create: `admin-module/src/main/kotlin/com/pebble/admin/config/AdminSecurityConfig.kt`
- Create: `admin-module/src/main/kotlin/com/pebble/admin/dto/AdminDtos.kt`
- Create: `admin-module/src/main/resources/application.yaml`
- Create: `admin-module/src/main/resources/application-dev.yaml`

- [ ] **Step 1: AdminApplication.kt 생성**

```kotlin
package com.pebble.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AdminApplication

fun main(args: Array<String>) {
    runApplication<AdminApplication>(*args)
}
```

- [ ] **Step 2: AdminDtos.kt 생성**

```kotlin
package com.pebble.admin.dto

data class AdminUserDto(
    val userId: Long,
    val isExposed: Boolean,
    val isBlocked: Boolean,
    val updatedAt: String
)

data class AdminMatchDto(
    val matchId: String,
    val userA: Long,
    val userB: Long,
    val createdAt: String
)

data class AdminRankingDto(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,
    val createdAt: String
)

data class SummaryStatsDto(
    val totalUsers: Int,
    val activeUsers: Int,
    val totalMatches: Int,
    val blockCount: Int
)

data class MatchRateDto(
    val totalRankings: Int,
    val totalMatches: Int,
    val matchRate: Double
)

data class TopRankedUserDto(
    val userId: Long,
    val receivedCount: Int
)

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Int
)

data class ExposureUpdateRequest(val isExposed: Boolean)
data class BlockUpdateRequest(val isBlocked: Boolean)
```

- [ ] **Step 3: AdminSecurityConfig.kt 생성**

```kotlin
package com.pebble.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.AuthenticationEntryPoint

@Configuration
class AdminSecurityConfig {

    @Bean
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/v1/admin/**").hasAuthority("ROLE_ADMIN")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                oauth2.authenticationEntryPoint(adminAuthenticationEntryPoint())
            }
            .build()
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
    fun adminAuthenticationEntryPoint(): AuthenticationEntryPoint {
        return AuthenticationEntryPoint { request, response, _ ->
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            val json = """{"status": 401, "error": "Unauthorized", "message": "관리자 인증이 필요합니다.", "path": "${request.requestURI}"}"""
            response.writer.write(json)
        }
    }
}
```

- [ ] **Step 4: application.yaml 생성**

```yaml
server:
  port: ${PORT:8085}

spring:
  application:
    name: admin-service
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${AUTH_SERVICE_URL:http://localhost:8080}/oauth2/jwks

matching:
  internal-url: ${MATCHING_SERVICE_URL:http://localhost:8081}

logging:
  level:
    com.pebble.admin: DEBUG
    org.springframework.security: DEBUG
```

- [ ] **Step 5: application-dev.yaml 생성**

```yaml
# 로컬 개발 환경 (application.yaml의 기본값으로 충분, 필요 시 오버라이드)
logging:
  level:
    com.pebble.admin: DEBUG
```

- [ ] **Step 6: 빌드 확인**

```bash
./gradlew :admin-module:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add admin-module/src/main/kotlin/com/pebble/admin/AdminApplication.kt
git add admin-module/src/main/kotlin/com/pebble/admin/config/AdminSecurityConfig.kt
git add admin-module/src/main/kotlin/com/pebble/admin/dto/AdminDtos.kt
git add admin-module/src/main/resources/
git commit -m "feat(admin): add AdminApplication, SecurityConfig, DTOs, and application config"
```

---

## Task 6: admin-module — MatchingInternalClient 생성

**Files:**
- Create: `admin-module/src/main/kotlin/com/pebble/admin/client/MatchingInternalClient.kt`

- [ ] **Step 1: MatchingInternalClient.kt 생성**

```kotlin
package com.pebble.admin.client

import com.pebble.admin.dto.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class MatchingInternalClient(
    @Value("\${matching.internal-url}") private val matchingUrl: String
) {
    private val restClient = RestClient.create()

    fun getAllUsers(token: String): List<AdminUserDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/users")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminUserDto>>() {})
            ?: emptyList()

    fun updateExposure(userId: Long, request: ExposureUpdateRequest, token: String) {
        restClient.patch()
            .uri("$matchingUrl/internal/admin/users/$userId/exposure")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toBodilessEntity()
    }

    fun blockUser(userId: Long, request: BlockUpdateRequest, token: String) {
        restClient.patch()
            .uri("$matchingUrl/internal/admin/users/$userId/block")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toBodilessEntity()
    }

    fun getAllMatches(token: String): List<AdminMatchDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/matches")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminMatchDto>>() {})
            ?: emptyList()

    fun deleteMatch(matchId: String, token: String) {
        restClient.delete()
            .uri("$matchingUrl/internal/admin/matches/$matchId")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .toBodilessEntity()
    }

    fun getAllRankings(token: String): List<AdminRankingDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/rankings")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminRankingDto>>() {})
            ?: emptyList()
}
```

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew :admin-module:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add admin-module/src/main/kotlin/com/pebble/admin/client/MatchingInternalClient.kt
git commit -m "feat(admin): add MatchingInternalClient with RestClient"
```

---

## Task 7: admin-module — AdminService (TDD)

**Files:**
- Create: `admin-module/src/main/kotlin/com/pebble/admin/service/AdminService.kt`
- Create: `admin-module/src/test/kotlin/com/pebble/admin/AdminServiceTest.kt`

- [ ] **Step 1: 테스트 파일 먼저 작성**

`admin-module/src/test/kotlin/com/pebble/admin/AdminServiceTest.kt` 생성:
```kotlin
package com.pebble.admin

import com.pebble.admin.client.MatchingInternalClient
import com.pebble.admin.dto.*
import com.pebble.admin.service.AdminService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.given
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class AdminServiceTest {
    private lateinit var matchingClient: MatchingInternalClient
    private lateinit var adminService: AdminService

    private val token = "test-token"
    private val now = "2026-05-10T10:00:00"

    @BeforeEach
    fun setUp() {
        matchingClient = mock()
        adminService = AdminService(matchingClient)
    }

    @Test
    fun `getSummaryStats returns correct counts`() {
        given(matchingClient.getAllUsers(token)).willReturn(listOf(
            AdminUserDto(1L, true, false, now),
            AdminUserDto(2L, false, false, now),
            AdminUserDto(3L, true, true, now)
        ))
        given(matchingClient.getAllMatches(token)).willReturn(listOf(
            AdminMatchDto("m1", 1L, 2L, now)
        ))

        val stats = adminService.getSummaryStats(token)

        assertEquals(3, stats.totalUsers)
        assertEquals(1, stats.activeUsers)
        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.blockCount)
    }

    @Test
    fun `getMatchRate calculates correctly`() {
        given(matchingClient.getAllRankings(token)).willReturn(listOf(
            AdminRankingDto(1L, 2L, 1, now),
            AdminRankingDto(2L, 1L, 1, now),
            AdminRankingDto(3L, 4L, 2, now)
        ))
        given(matchingClient.getAllMatches(token)).willReturn(listOf(
            AdminMatchDto("m1", 1L, 2L, now)
        ))

        val result = adminService.getMatchRate(token)

        assertEquals(3, result.totalRankings)
        assertEquals(1, result.totalMatches)
        assertEquals(2.0 / 3.0, result.matchRate, 0.001)
    }

    @Test
    fun `getTopRanked returns users sorted by received count`() {
        given(matchingClient.getAllRankings(token)).willReturn(listOf(
            AdminRankingDto(1L, 2L, 1, now),
            AdminRankingDto(3L, 2L, 2, now),
            AdminRankingDto(1L, 3L, 1, now)
        ))

        val result = adminService.getTopRanked(token, 2)

        assertEquals(2, result.size)
        assertEquals(2L, result[0].userId)
        assertEquals(2, result[0].receivedCount)
    }

    @Test
    fun `getAllUsers paginates correctly`() {
        given(matchingClient.getAllUsers(token)).willReturn(listOf(
            AdminUserDto(1L, true, false, now),
            AdminUserDto(2L, true, false, now),
            AdminUserDto(3L, true, false, now)
        ))

        val result = adminService.getAllUsers(token, page = 1, size = 2)

        assertEquals(1, result.content.size)
        assertEquals(3L, result.content[0].userId)
        assertEquals(3, result.totalElements)
    }

    @Test
    fun `getMatchRate returns zero when no rankings`() {
        given(matchingClient.getAllRankings(token)).willReturn(emptyList())
        given(matchingClient.getAllMatches(token)).willReturn(emptyList())

        val result = adminService.getMatchRate(token)

        assertEquals(0.0, result.matchRate)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인 (AdminService 없음)**

```bash
./gradlew :admin-module:compileTestKotlin
```
Expected: FAIL — `AdminService` 클래스를 찾을 수 없음

- [ ] **Step 3: AdminService 구현**

`admin-module/src/main/kotlin/com/pebble/admin/service/AdminService.kt` 생성:
```kotlin
package com.pebble.admin.service

import com.pebble.admin.client.MatchingInternalClient
import com.pebble.admin.dto.*
import org.springframework.stereotype.Service

@Service
class AdminService(
    private val matchingClient: MatchingInternalClient
) {
    fun getAllUsers(token: String, page: Int, size: Int): PageResponse<AdminUserDto> {
        val all = matchingClient.getAllUsers(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun updateExposure(userId: Long, request: ExposureUpdateRequest, token: String) =
        matchingClient.updateExposure(userId, request, token)

    fun blockUser(userId: Long, request: BlockUpdateRequest, token: String) =
        matchingClient.blockUser(userId, request, token)

    fun getAllMatches(token: String, page: Int, size: Int): PageResponse<AdminMatchDto> {
        val all = matchingClient.getAllMatches(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun deleteMatch(matchId: String, token: String) =
        matchingClient.deleteMatch(matchId, token)

    fun getAllRankings(token: String, page: Int, size: Int): PageResponse<AdminRankingDto> {
        val all = matchingClient.getAllRankings(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun getSummaryStats(token: String): SummaryStatsDto {
        val users = matchingClient.getAllUsers(token)
        return SummaryStatsDto(
            totalUsers = users.size,
            activeUsers = users.count { it.isExposed && !it.isBlocked },
            totalMatches = matchingClient.getAllMatches(token).size,
            blockCount = users.count { it.isBlocked }
        )
    }

    fun getMatchRate(token: String): MatchRateDto {
        val rankings = matchingClient.getAllRankings(token)
        val matches = matchingClient.getAllMatches(token)
        val rate = if (rankings.isEmpty()) 0.0 else (matches.size * 2.0) / rankings.size
        return MatchRateDto(rankings.size, matches.size, rate)
    }

    fun getTopRanked(token: String, limit: Int): List<TopRankedUserDto> =
        matchingClient.getAllRankings(token)
            .groupBy { it.toUserId }
            .map { (userId, rankings) -> TopRankedUserDto(userId, rankings.size) }
            .sortedByDescending { it.receivedCount }
            .take(limit)
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
./gradlew :admin-module:test
```
Expected: 5개 테스트 모두 PASS

- [ ] **Step 5: 커밋**

```bash
git add admin-module/src/main/kotlin/com/pebble/admin/service/AdminService.kt
git add admin-module/src/test/kotlin/com/pebble/admin/AdminServiceTest.kt
git commit -m "feat(admin): add AdminService with pagination and stats logic"
```

---

## Task 8: admin-module — AdminController 생성

**Files:**
- Create: `admin-module/src/main/kotlin/com/pebble/admin/controller/AdminController.kt`

- [ ] **Step 1: AdminController.kt 생성**

```kotlin
package com.pebble.admin.controller

import com.pebble.admin.dto.*
import com.pebble.admin.service.AdminService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val adminService: AdminService
) {
    @GetMapping("/users")
    fun getAllUsers(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminUserDto>> =
        ResponseEntity.ok(adminService.getAllUsers(jwt.tokenValue, page, size))

    @PatchMapping("/users/{userId}/exposure")
    fun updateExposure(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: Long,
        @RequestBody request: ExposureUpdateRequest
    ): ResponseEntity<Void> {
        adminService.updateExposure(userId, request, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/users/{userId}/block")
    fun blockUser(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: Long,
        @RequestBody request: BlockUpdateRequest
    ): ResponseEntity<Void> {
        adminService.blockUser(userId, request, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/matches")
    fun getAllMatches(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminMatchDto>> =
        ResponseEntity.ok(adminService.getAllMatches(jwt.tokenValue, page, size))

    @DeleteMapping("/matches/{matchId}")
    fun deleteMatch(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable matchId: String
    ): ResponseEntity<Void> {
        adminService.deleteMatch(matchId, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/rankings")
    fun getAllRankings(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminRankingDto>> =
        ResponseEntity.ok(adminService.getAllRankings(jwt.tokenValue, page, size))

    @GetMapping("/stats/summary")
    fun getSummaryStats(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<SummaryStatsDto> =
        ResponseEntity.ok(adminService.getSummaryStats(jwt.tokenValue))

    @GetMapping("/stats/match-rate")
    fun getMatchRate(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MatchRateDto> =
        ResponseEntity.ok(adminService.getMatchRate(jwt.tokenValue))

    @GetMapping("/stats/top-ranked")
    fun getTopRanked(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<TopRankedUserDto>> =
        ResponseEntity.ok(adminService.getTopRanked(jwt.tokenValue, limit))
}
```

- [ ] **Step 2: 전체 빌드 확인**

```bash
./gradlew :admin-module:build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add admin-module/src/main/kotlin/com/pebble/admin/controller/AdminController.kt
git commit -m "feat(admin): add AdminController with user/match/ranking/stats endpoints"
```

---

## Task 9: gateway-service — admin-module 라우팅 추가

**Files:**
- Modify: `gateway-service/src/main/resources/application.yaml`

- [ ] **Step 1: gateway application.yaml에 admin 라우팅 추가**

기존 `# 4. PRIVATE: Matching Service` 라우트 아래에 추가:
```yaml
        # 5. PRIVATE: Admin Service (ROLE_ADMIN JWT 필요)
        - id: private-admin-route
          uri: ${ADMIN_SERVICE_URL}
          predicates:
            - Path=/api/v1/admin/**
```

최종 routes 섹션 (전체):
```yaml
      routes:
        # 1. PUBLIC: Auth Service
        - id: public-access-route
          uri: ${AUTH_SERVICE_URL}
          order: -1
          predicates:
            - Path=/login.html, /signup.html, /login, /signup, /favicon.ico, /api/v1/login, /api/v1/users/signup, /api/v1/users/me, /api/v1/logout, /api/v1/refresh, /api/tasks/access-info

        # Auth: /me 페이지 → auth 모듈 index.html
        - id: me-page
          uri: ${AUTH_SERVICE_URL}
          predicates:
            - Path=/me, /me/
          filters:
            - RewritePath=/me/?, /index.html

        # 2. PRIVATE: Task Service
        - id: private-task-route
          uri: ${TASK_SERVICE_URL}
          predicates:
            - Path=/api/v1/tasks/**, /api/v1/dates/**, /tasks/**

        # 3. PUBLIC: Preview Service
        - id: public-preview-route
          uri: ${PREVIEW_SERVICE_URL}
          predicates:
            - Path=/api/v1/preview/**, /preview/**

        # 4. PRIVATE: Matching Service
        - id: private-matching-route
          uri: ${MATCHING_SERVICE_URL}
          predicates:
            - Path=/api/v1/matching/**, /matching/**

        # 5. PRIVATE: Admin Service (ROLE_ADMIN JWT 필요)
        - id: private-admin-route
          uri: ${ADMIN_SERVICE_URL}
          predicates:
            - Path=/api/v1/admin/**
```

> **참고:** `/internal/**` 경로는 gateway에 라우팅 없음 → 자동으로 404 반환 (별도 차단 규칙 불필요)

- [ ] **Step 2: 빌드 확인**

```bash
./gradlew :gateway-service:build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add gateway-service/src/main/resources/application.yaml
git commit -m "feat(gateway): add admin-module routing for /api/v1/admin/**"
```

---

## 수동 검증 체크리스트

모든 태스크 완료 후 로컬에서 순서대로 실행:

1. auth-module 실행: `./gradlew :auth-module:bootRun`
2. matching-module 실행: `./gradlew :matching-module:bootRun`
3. admin-module 실행: `./gradlew :admin-module:bootRun`

ROLE_ADMIN 토큰 발급 후:
- [ ] `GET /api/v1/admin/users` → 사용자 목록 반환
- [ ] `PATCH /api/v1/admin/users/3/block` `{"isBlocked": true}` → 204 반환
- [ ] `GET /api/v1/admin/stats/summary` → totalUsers, activeUsers, blockCount 확인
- [ ] `GET /api/v1/admin/stats/match-rate` → matchRate 값 확인
- [ ] `GET /api/v1/admin/stats/top-ranked?limit=3` → Top 3 사용자 확인
- [ ] `DELETE /api/v1/admin/matches/match_001` → 204 반환
- [ ] ROLE_USER 토큰으로 `GET /api/v1/admin/users` → 401 반환 확인
