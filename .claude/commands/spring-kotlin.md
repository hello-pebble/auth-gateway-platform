# Spring Boot / Kotlin 코드 작성

이 명령이 실행되면 아래 규칙에 따라 **Kotlin + Spring Boot 3.5 코드**를 작성한다.
이 프로젝트의 주력 언어는 Kotlin이며, 모든 코드는 대용량 트래픽 인증 시스템의 안정성을 최우선으로 한다.

---

## 계층 구조

```
Controller → Service → Repository (Impl) → JpaRepository
```

- **Controller**: HTTP 요청/응답만 처리. 비즈니스 로직 금지.
- **Service**: 트랜잭션 경계 소유. 도메인 예외 발생.
- **Repository**: 추상 인터페이스 + `*RepositoryImpl` 구현체 패턴 유지.

## 클래스 설계

```kotlin
// DTO: data class 필수
data class UserSignUpRequest(
    @field:NotBlank @field:Size(min = 4, max = 20) val username: String,
    @field:NotBlank val password: String
)

// Service: constructor injection, readOnly 기본
@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    @Transactional
    fun signUp(request: UserSignUpRequest): User { ... }
}

// Controller: LoggerFactory 사용
@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: UserSignUpRequest): ResponseEntity<UserResponse> { ... }
}
```

## 예외 처리

- 도메인 예외: `UserException(message)` (모듈별 커스텀 예외) 사용
- 메시지: **한국어**로 작성
- 전역 처리: `GlobalExceptionHandler`에서 `@ExceptionHandler`로 통합
- `RuntimeException` 직접 throw 금지

```kotlin
throw UserException("이미 존재하는 사용자명입니다.")

userRepository.findByUsername(username)
    ?: throw UserException("사용자를 찾을 수 없습니다.")
```

## 트랜잭션 전략

| 상황 | 어노테이션 |
|---|---|
| 클래스 기본 | `@Transactional(readOnly = true)` |
| 쓰기 메서드 | `@Transactional` (메서드 오버라이드) |
| Controller | 트랜잭션 어노테이션 금지 |

## 소프트 삭제

```kotlin
var deletedAt: LocalDateTime? = null
fun softDelete() { deletedAt = LocalDateTime.now() }
```

조회 시 `deletedAt == null` 조건 포함.

## Spring Security / JWT

- JWT는 HttpOnly Secure Cookie로 전달
- Refresh Token Rotation(RTR): 재발급 시 기존 토큰 즉시 삭제
- `@AuthenticationPrincipal`로 인증 정보 주입
- Access Token: 15분 / Refresh Token: 7일

## Null Safety 규칙

- `!!` 사용 금지 — `?: throw` 또는 `?.let` 사용
- 외부 입력 nullable 처리 후 명시적 검증

## 테스트

```kotlin
@ExtendWith(MockitoExtension::class)
class UserServiceTest {
    @Mock lateinit var userRepository: UserRepository
    @InjectMocks lateinit var userService: UserService

    @Test
    @DisplayName("중복 사용자명 가입 시 예외 발생")
    fun `signUp throws exception on duplicate username`() {
        given(userRepository.findByUsername("test")).willReturn(User(...))
        assertThrows<UserException> { userService.signUp(request) }
    }
}
```

- 단위: Mockito-Kotlin (`given`/`verify` 패턴)
- 통합: `@SpringBootTest` + mock OAuth/Redis
- DisplayName: 한국어 또는 backtick 자연어 표현
