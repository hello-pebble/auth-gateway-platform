# Gateway Service — Health Check 구현 작업 기록

> 작업일: 2026-04-23  
> 담당 모듈: `gateway-service`  
> AI 도구: Claude Code (claude-sonnet-4-6)

---

## 1. 목표

| 항목 | 내용 |
|------|------|
| **기능 목표** | Spring Boot Actuator를 활용한 Gateway 헬스체크 구현 |
| **대상 엔드포인트** | `GET /actuator/health` |
| **헬스 체크 대상** | Auth Service, Matching Service, Task Service |
| **방식** | 각 서비스의 `/actuator/health`를 WebClient로 호출하여 상태 집계 |
| **추가 설정** | Security에서 `/actuator/**` permitAll, 각 서비스 actuator 노출 설정 |

---

## 2. 구현 내용

### 추가/수정 파일

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| `config/HealthCheckConfig.kt` | 신규 생성 | `ReactiveHealthIndicator` 빈 3개 등록 (Auth/Matching/Task) |
| `config/GatewaySecurityConfig.kt` | 수정 | `/actuator/**` permitAll 추가 |
| `src/main/resources/application.yaml` | 수정 | `management` 설정, `services` URL 블록 추가 |
| `build.gradle.kts` | 수정 | `spring-boot-starter-actuator` 의존성 추가 |

### HealthCheckConfig.kt 최종 구조

```kotlin
@Configuration
class HealthCheckConfig {
    @Bean
    fun webClient(builder: WebClient.Builder): WebClient = builder.build()

    @Bean
    fun authServiceHealthIndicator(webClient: WebClient,
        @Value("\${services.auth.url}") authUrl: String): ReactiveHealthIndicator
        = createServiceHealthIndicator(webClient, "Auth Service", authUrl)

    // matching, task 동일 패턴

    private fun createServiceHealthIndicator(...): ReactiveHealthIndicator {
        return ReactiveHealthIndicator {
            webClient.get().uri("$baseUrl/actuator/health")
                .retrieve().toEntity(Map::class.java)
                .map { response -> /* UP/DOWN 판단 */ }
                .onErrorResume { e -> Mono.just(Health.down()...build()) }
        }
    }
}
```

---

## 3. 발생 문제 및 해결 과정

### 3-1. 전체 문제 해결 표

| # | 에러 메시지 | 원인 클래스/위치 | 근본 원인 | 해결 방법 | AI 활용 |
|---|------------|----------------|----------|-----------|---------|
| 1 | `Unresolved reference 'health'` | `HealthCheckConfig.kt` import | Spring Boot 4.0에서 Actuator 패키지 이동<br>`actuate.health` → `health.contributor` | import 경로 수정 + `Health.Builder()` API로 변경 | IDE 에러 메시지 분석 후 Context7로 Spring Boot 4.0 공식 문서 조회 |
| 2 | `Failed to generate bean name`<br>for `LifecycleMvcEndpointAutoConfiguration` | Spring Cloud Context | Spring Framework 7.0의 bean name 생성 규칙 강화.<br>`@AutoConfigureAfter` + `@Configuration`(non-`@AutoConfiguration`) 클래스 충돌 | `spring.autoconfigure.exclude` 등록 | 에러 클래스 위치 추적 후 JAR 바이트코드 분석으로 원인 파악 |
| 3 | `Failed to generate bean name`<br>for `RefreshAutoConfiguration` | Spring Cloud Context | 동일 원인 (`@AutoConfigureBefore` 보유) | `spring.autoconfigure.exclude` 추가 | 동일 패턴 재확인, 전체 `AutoConfiguration.imports` 목록 추출로 선제 대응 |
| 4 | `Error processing condition`<br>on `SimpleDiscoveryClientAutoConfiguration` | Spring Cloud Commons | `WebServerInitializedEvent` 패키지 이동으로 조건 평가 중 `ClassNotFoundException`<br>(`boot.web.context` → `boot.web.server.context`) | `spring.autoconfigure.exclude` 추가 | Gradle 캐시에서 JAR을 직접 grep하여 영향 클래스 3개 식별 |
| 5 | `Failed to introspect Class`<br>`SimpleReactiveDiscoveryClientAutoConfiguration` | Spring Cloud Commons | 동일 (Reactive 버전) | `spring.autoconfigure.exclude` 추가 | 단계 4에서 선제 식별된 클래스 |
| 6 | `NoClassDefFoundError`<br>`NettyWebServerFactoryCustomizer`<br>in `GatewayAutoConfiguration` | **Spring Cloud Gateway 4.3.0 핵심** | **근본 원인 확정**: Spring Cloud 2025.0.0 BOM은 Spring Boot **3.5.x**용으로 컴파일됨.<br>Spring Boot 4.0에서 Netty 패키지 이동:<br>`autoconfigure.web.embedded` → `reactor.netty.autoconfigure` | **Spring Boot `4.0.0` → `3.5.3` 다운그레이드** | Spring Boot 4.0 JAR에서 클래스 위치 직접 확인, BOM POM 파일 분석 |
| 7 | `BeanCreationException`<br>`authServiceHealthIndicator` | `HealthCheckConfig.kt` | `@Value("${services.auth.url}")` 주입 실패 — yaml에서 `services:` 블록 누락 (로컬 테스트용 yaml 재작성 시 누락) | `application.yaml`에 `services:` 블록 추가 | 에러 메시지 + 코드 직독으로 즉시 파악 |

---

### 3-2. 핵심 발견: Spring Cloud 버전 호환성

```
Spring Cloud 2025.0.0 BOM
  └── spring-cloud-gateway    4.3.0  ← Spring Boot 3.x 기준 컴파일
  └── spring-cloud-commons    4.3.0  ← Spring Boot 3.x 기준 컴파일
  └── spring-cloud-context    4.3.0  ← Spring Boot 3.x 기준 컴파일

Spring Boot 4.0.0
  └── spring-boot-web-server  (신규 artifact)
        └── WebServerInitializedEvent  ← boot.web.server.context (이동됨)
  └── spring-boot-reactor-netty (신규 artifact)
        └── NettyReactiveWebServerFactoryCustomizer  ← reactor.netty.autoconfigure (이동됨)
```

**결론**: Spring Cloud 2025.0.0은 Spring Boot 3.5.x 대응 릴리스이며, Spring Boot 4.0과 호환되지 않음.  
Spring Boot 4.0 지원 Spring Cloud 버전은 별도 릴리스 예정 (Spring Cloud Gateway 5.0.0 라인).

---

### 3-3. Spring Boot 3.x ↔ 4.0 패키지 변경 요약

| 클래스 | Spring Boot 3.x | Spring Boot 4.0 |
|--------|----------------|----------------|
| `Health` | `o.s.boot.actuate.health.Health` | `o.s.boot.health.contributor.Health` |
| `ReactiveHealthIndicator` | `o.s.boot.actuate.health.ReactiveHealthIndicator` | `o.s.boot.health.contributor.ReactiveHealthIndicator` |
| `Health` 빌더 | `Health.up()` / `Health.down()` (static factory) | `Health.Builder().up()` / `.down()` |
| `WebServerInitializedEvent` | `o.s.boot.web.context.WebServerInitializedEvent` | `o.s.boot.web.server.context.WebServerInitializedEvent` |
| `NettyWebServerFactoryCustomizer` | `o.s.boot.autoconfigure.web.embedded.*` | `o.s.boot.reactor.netty.autoconfigure.*` |

---

## 4. 최종 적용 버전

| 항목 | 버전 |
|------|------|
| Spring Boot | `3.5.3` (4.0.0 → 다운그레이드) |
| Spring Cloud BOM | `2025.0.0` |
| Spring Cloud Gateway | `4.3.0` (BOM 관리) |
| Kotlin | `2.2.0` |
| Java | `21` |

---

## 5. AI 활용 내용 상세

| 단계 | AI 활용 방식 | 결과 |
|------|------------|------|
| **에러 식별** | 에러 메시지를 그대로 입력 → 에러 타입 분류 및 원인 가설 수립 | 컴파일/런타임/설정 오류 구분 |
| **문서 조회** | Context7 MCP를 통해 Spring Boot 4.0 공식 문서 실시간 조회 | `ReactiveHealthIndicator` 신규 패키지 경로 확인 |
| **JAR 분석** | Gradle 캐시의 JAR 파일을 `javap`, `unzip`, `grep`으로 직접 분석 | `WebServerInitializedEvent` 참조 클래스 3개 식별, `@Import` 어노테이션 구조 확인 |
| **BOM 분석** | `spring-cloud-dependencies-2025.0.0.pom` 직접 파싱 | Gateway 버전이 4.3.0임을 확인, Spring Boot 3.x용임을 검증 |
| **Spring Boot 4.0 JAR 탐색** | `find` + `unzip -l`로 Spring Boot 4.0 전체 JAR에서 클래스 위치 추적 | 이동된 클래스들의 신규 패키지 확인 |
| **근본 원인 확정** | 누적된 에러 패턴 + JAR 분석 결과 종합 | Spring Cloud 2025.0.0 ↔ Spring Boot 4.0 비호환성 결론 도출 |

---

## 6. 향후 고려사항

| 항목 | 내용 |
|------|------|
| Spring Boot 4.0 전환 시점 | Spring Cloud Gateway 5.0.0 라인의 안정 버전 출시 이후 |
| 환경변수 관리 | 로컬(`application.yaml` 하드코딩) vs 운영(`${AUTH_SERVICE_URL}` 환경변수) 분리 필요 |
| `circuitbreakers` 설정 | Resilience4j 없이 `management.health.circuitbreakers.enabled: true` 설정 시 의미 없음 — 제거 권장 |
| Preview Service 헬스체크 | `HealthCheckConfig`에 `previewServiceHealthIndicator` 추가 고려 (`localhost:8084`) |
