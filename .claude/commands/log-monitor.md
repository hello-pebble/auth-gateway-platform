# 로그 & 모니터링 코드 작성

이 명령이 실행되면 아래 규칙에 따라 **구조화된 로깅과 모니터링 코드**를 작성한다.
이 프로젝트는 대용량 트래픽 인증 시스템이므로, 로그는 장애 추적과 성능 병목 파악에 직결된다.

---

## Logger 선언

**Kotlin:**
```kotlin
private val log = LoggerFactory.getLogger(javaClass)
```

**Java:**
```java
// Lombok 사용 시
@Slf4j
public class TaskService { ... }
```

## 로그 레벨 기준

| 레벨 | 사용 기준 |
|---|---|
| `ERROR` | 복구 불가능한 장애 — 예외 스택 반드시 포함 |
| `WARN` | 비정상이나 서비스 계속 가능 (토큰 재사용 시도, Rate Limit 근접) |
| `INFO` | 주요 비즈니스 이벤트 (로그인 성공, 회원가입, 토큰 발급) |
| `DEBUG` | 개발/디버깅용 — prod 미출력 |

## 구조화 로그 패턴

**인증 이벤트 (auth-module):**
```kotlin
log.info("LOGIN_SUCCESS userId={} provider={} ip={}", userId, provider, clientIp)
log.warn("LOGIN_FAILED username={} reason={} ip={}", username, reason, clientIp)
log.warn("TOKEN_REUSE_DETECTED userId={} tokenId={}", userId, tokenId)
log.error("TOKEN_SIGNATURE_INVALID ip={}", clientIp, e)
```

**Rate Limiting (gateway-service):**
```kotlin
log.warn("RATE_LIMIT_EXCEEDED ip={} endpoint={} count={}", ip, path, requestCount)
log.info("QUEUE_ENTER userId={} position={}", userId, queuePosition)
log.info("QUEUE_ALLOWED userId={}", userId)
```

**비즈니스 이벤트 (task-module):**
```java
log.info("TASK_CREATED taskId={} userId={}", task.getId(), userId);
log.info("TASK_DELETED taskId={} userId={}", taskId, userId);
```

## 금지 패턴

```kotlin
// 문자열 연결 금지 (검색 불가)
log.info("User " + username + " logged in")

// 민감정보 로깅 금지
log.debug("password={} token={}", password, accessToken)

// 예외 메시지만 로깅 금지 — e 자체를 마지막 파라미터로 전달
log.error("에러: {}", e.message)        // ❌
log.error("DB_QUERY_FAILED userId={}", userId, e)  // ✅
```

## 성능 측정

```kotlin
val start = System.currentTimeMillis()
val token = jwtProvider.generateToken(user)
log.info("TOKEN_ISSUED userId={} durationMs={}", user.id, System.currentTimeMillis() - start)
```

## 트래픽 이상 감지 기준

| 패턴 | 임계치 | 의심 원인 |
|---|---|---|
| `LOGIN_FAILED` 연속 | 동일 IP 5회/분 | 브루트포스 공격 |
| `TOKEN_REUSE_DETECTED` | 1회라도 발생 | 토큰 탈취 시도 |
| `RATE_LIMIT_EXCEEDED` | 1분 내 100건+ | 매크로/DDoS |
| `DB_QUERY_FAILED` | 연속 3회+ | DB 연결 장애 |

## Logback 설정 위치

`src/main/resources/logback-spring.xml` — dev는 콘솔 패턴, prod는 JSON 구조화 출력.
상세 설정은 `docs/skills/log-monitoring.md` 참조.
