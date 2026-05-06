# Spring Boot / Java 코드 작성

이 명령이 실행되면 아래 규칙에 따라 **Java + Spring Boot 3.5 코드**를 작성한다.
이 프로젝트에서 Java는 `task-module`에 사용되며, Kotlin 모듈과 동일한 아키텍처 원칙을 따른다.

---

## 계층 구조

```
Controller → Service → Repository → JpaRepository
```

- **Controller**: HTTP 요청/응답만 처리. 비즈니스 로직 금지.
- **Service**: 트랜잭션 경계 소유. 도메인 예외 발생.
- **Repository**: Spring Data JPA 인터페이스 + 필요 시 커스텀 구현체.

## 클래스 설계

```java
// DTO: record 사용
public record TaskCreateRequest(
    @NotBlank String title,
    @NotNull Long userId
) {}

// Service: Lombok @RequiredArgsConstructor, readOnly 기본
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TaskService {
    private final TaskRepository taskRepository;

    @Transactional
    public Task createTask(TaskCreateRequest request) { ... }
}

// Controller: ResponseEntity 반환
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<TaskResponse> create(
        @Valid @RequestBody TaskCreateRequest request,
        @AuthenticationPrincipal UserDetails user
    ) {
        return ResponseEntity.ok(taskService.createTask(request));
    }
}
```

## 예외 처리

- 엔티티 미존재: `IllegalArgumentException` 사용
- 권한 위반: `IllegalStateException` 또는 `AccessDeniedException`
- 메시지: **한국어**로 작성
- 전역 처리: `GlobalExceptionHandler`에서 `@ExceptionHandler`로 통합

```java
Task task = taskRepository.findById(id)
    .orElseThrow(() -> new IllegalArgumentException("태스크를 찾을 수 없습니다. id=" + id));
```

## 트랜잭션 전략

| 상황 | 어노테이션 |
|---|---|
| 클래스 기본 | `@Transactional(readOnly = true)` |
| 쓰기 메서드 | `@Transactional` (메서드에 개별 선언) |
| Controller | 트랜잭션 어노테이션 금지 |

## 소프트 삭제

```java
@Column
private LocalDateTime deletedAt;

public void softDelete() { this.deletedAt = LocalDateTime.now(); }
```

조회 쿼리에 `WHERE deleted_at IS NULL` 조건 포함.

## Lombok 규칙

| 허용 | 금지 |
|---|---|
| `@RequiredArgsConstructor` | `@Data` (equals/hashCode 충돌) |
| `@Getter`, `@Setter` | `@AllArgsConstructor` (JPA 기본 생성자 충돌) |
| `@Builder` | `@ToString` on Entity (N+1 위험) |

## JPA Entity 설계

```java
@Entity
@Table(name = "tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    public static Task create(String title, Long userId) {
        Task task = new Task();
        task.title = title;
        return task;
    }
}
```

## Spring Security

- JWT 파싱: `@AuthenticationPrincipal`로 인증 객체 주입
- Gateway 헤더(`x-user-id`, `x-user-role`) 신뢰
- 메서드 보안: `@PreAuthorize("hasRole('ADMIN')")` 활용

## 테스트

```java
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    @Mock TaskRepository taskRepository;
    @InjectMocks TaskService taskService;

    @Test
    @DisplayName("존재하지 않는 태스크 조회 시 예외 발생")
    void getTask_notFound_throwsException() {
        given(taskRepository.findById(99L)).willReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
            () -> taskService.getTask(99L));
    }
}
```

- 단위: JUnit 5 + Mockito (`given`/`verify`)
- 통합: `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- DisplayName: 한국어로 작성
