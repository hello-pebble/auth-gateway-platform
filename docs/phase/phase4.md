## Phase 4: 트래픽 제어 및 대기열 관리 (Traffic Control & Waiting Room)

인증 시스템의 입구에서 트래픽을 정교하게 제어하여, 어떤 상황에서도 서버의 생존을 보장하고 사용자에게 예측 가능한 대기 경험을 제공합니다.

#### 4-1. 비정상 트래픽 차단 (Filtering)
| 항목 | 세부 내용 | 상태 |
| :--- | :--- | :---: |
| IP 기반 Rate Limit | Bucket4j를 활용하여 짧은 시간 내 과도한 요청을 보내는 IP 즉시 차단 (429 Too Many Requests) | 📅 |
| Brute-force 방어 | 로그인 실패 횟수에 따른 계정 임시 잠금 및 요청 제한 | 📅 |

#### 4-2. 가상 대기열 도입 (Queuing & Batching)
| 항목 | 세부 내용 | 상태 |
| :--- | :--- | :---: |
| Redis 기반 대기열 | 서버 수용량 초과 시, 요청을 즉시 처리하지 않고 Redis Sorted Set에 담아 순번 부여 | 📅 |
| 폴링/웹소켓 응답 | 사용자에게 "현재 대기 순번"을 반환하고, 서버가 처리 가능한 시점에 진입 허용 | 📅 |
| 처리량 조절 (Shaping) | 일정 단위(Batch)로 대기열의 유저를 통과시켜 서버 부하를 일정하게 유지 | 📅 |

#### 4-3. 트래픽 제어 통합 시나리오 (End-to-End Flow)

사용자가 로그인을 시도할 때 시스템이 트래픽을 제어하는 전체 흐름입니다.

1.  **입구 컷 (Filtering)**:
    *   `RateLimitFilter`가 요청 IP를 확인.
    *   1초 내 과도한 요청(매크로) 시 즉시 `429 Too Many Requests` 반환.
2.  **로그인 시도 (First Attempt)**:
    *   사용자가 `POST /api/v1/login` 호출.
3.  **대기열 판단 (Queuing)**:
    *   `WaitingRoomService`가 서버 수용량(Capacity) 확인.
    *   **[즉시 허용]**: 수용량 여유 시 즉시 로그인 로직 수행 및 JWT 발급.
    *   **[대기 필요]**: 수용량 초과 시 `403 Forbidden`과 함께 **현재 대기 순번** 반환.
4.  **대기 및 폴링 (Waiting & Polling)**:
    *   클라이언트는 `GET /api/v1/waiting-room/status`를 주기적으로 호출하여 순번 확인.
    *   `WaitingRoomScheduler`가 1초마다 일정 인원(Batch)을 '진입 허용' 상태로 전환.
4.  **최종 진입 (Final Access)**:
    *   상태가 `ALLOWED`로 바뀌면 클라이언트는 다시 `POST /api/v1/login` 호출.
    *   서버는 '허용 명부' 확인 후 실제 인증 및 로그인 완료.

#### 4-4. 가상 대기열 구현 전략 및 검증 (Implementation Strategy & Validation)

`WaitingRoomService`는 Redis의 특수한 데이터 구조를 활용하여 고부하 상황에서도 성능 저하 없이 대기열을 관리합니다.

| 기술 요소 | 구현 방식 및 상세 로직 | 설계 의도 및 기대 효과 |
| :--- | :--- | :--- |
| **Redis ZSET** | `WAITING_QUEUE_KEY` (Sorted Set) 사용. `score`에 요청 시각(Timestamp) 부여 | **공정한 순서 보장 (FIFO)**: O(log N) 속도로 정확한 대기 순번(`ZRANK`)을 실시간 계산 |
| **Redis SET** | `PROCEED_USER_KEY` (Standard Set) 사용. 진입 허용된 유저 목록 관리 | **보안 및 접근 제어**: 대기열을 통과한 유저만 실제 API를 호출할 수 있도록 O(1) 속도로 즉시 검증 |
| **Batch Processing** | `allowUserBatch()`를 통해 일정 주기마다 `CAPACITY_THRESHOLD`만큼 유저 전환 | **서버 자원 보호**: CPU 집약적인 인증(BCrypt) 연산의 초당 처리량(TPS)을 강제로 제한하여 가용성 유지 |

**[이론적 기대 vs 실제 구현 비교]**

| 항목 | 기대하는 이론적 강점 (Theory) | 실제 구현 상태 (WaitingRoomService.kt) | 분석 결과 |
| :--- | :--- | :--- | :--- |
| **순위 조회** | 수만 명 중 내 순번을 ms 단위로 조회 | `ZRANK` 기반 실시간 순번 반환 구현 완료 | ✅ 일치 |
| **중복 방지** | 동일 사용자의 중복 진입 자동 차단 | Redis ZSET Member 유일성으로 중복 제거 구현 완료 | ✅ 일치 |
| **진입 제어** | 대량의 유저를 한꺼번에 진입 처리 | `ZRANGE` 및 `SADD`를 통한 배치 처리 로직 구현 완료 | ✅ 일치 |
| **이탈자 처리** | `ZREMRANGEBYSCORE`로 유령 유저 자동 정리 | 현재 수동 삭제 로직만 존재 (Heartbeat 로직 미구현) | ⚠️ 보완 필요 |

#### 4-5. 암호화 알고리즘 마이그레이션 (Argon2 → BCrypt)

보안 강화와 리소스 효율성 확보를 위해 비밀번호 해시 알고리즘을 변경하였습니다.

| 변경 사항 | 세부 내용 | 결정 사유 |
| :--- | :--- | :--- |
| **알고리즘** | Argon2 (Spring Security Default) → **BCrypt** | **유지보수 지속성**: Argon2 라이브러리의 업데이트 중단(Dead project) 확인 및 장기적 보안 리스크 우려 |
| **리소스 관리** | Memory-hard (Argon2) → **CPU-intensive (BCrypt)** | **가벼운 검증**: 대용량 트래픽 상황에서 Argon2의 과도한 리소스 소모를 줄여 서버의 요청 처리량(TPS) 확보 |
| **표준 준수** | 최신 기술 스택 도입 → **대중성 및 검증된 안정성** | **검증된 에코시스템**: 가장 대중적으로 사용되며 지속적인 보안 패치가 보장되는 BCrypt로 전환하여 안정성 강화 |

---
### 자료구조 선택

| 관점      | 고민 내용                            | 필요한 기능     | 자료구조 검토 | 선택 결과       |
| ------- | -------------------------------- | ---------- | ------- | ----------- |
| 사용자 경험  | 요청을 바로 실패시키지 않고 **대기 후 처리**하고 싶음 | Queue      | List    | 시간 제어 불가    |
| 트래픽 제어  | 특정 시간당 요청 수 제한 필요                | Rate Limit | String  | Burst 제어 불가 |
| 데이터 관리  | 요청 상태 관리 및 정렬 필요                 | 정렬 구조      | Set     | 정렬 불가       |
| 최종 요구사항 | **대기 + 시간 기반 제어 + 정렬**           | 모두 필요      | ZSET    | ✅ 충족        |

#### 4-6. 커스텀 인증 UI 구현 (Custom Auth UI)

Spring Security 기본 제공 로그인 화면을 제거하고 서비스 전용 UI로 교체하였습니다.

| 항목 | 세부 내용 | 상태 |
| :--- | :--- | :---: |
| **커스텀 로그인 페이지** | `/login.html` — formLogin + oauth2Login 모두 동일 페이지 사용 | ✅ |
| **커스텀 회원가입 페이지** | `/signup.html` — `POST /api/v1/users/signup` 비동기 호출 | ✅ |
| **Google OAuth2 버튼** | `/oauth2/authorization/google` 연동, 로그인 페이지 내 소셜 버튼 | ✅ |
| **로그인 실패 안내** | `?error` 파라미터 감지 → 실패 안내 박스 노출 (`FormLoginFailureHandler`) | ✅ |
| **DefaultLoginPageGeneratingFilter 비활성화** | `oauth2Login.loginPage("/login.html")` 명시로 Spring 기본 필터 제거 | ✅ |

**핵심 결정**: `formLogin`과 `oauth2Login` 양쪽 모두 `.loginPage("/login.html")`을 명시하지 않으면 `DefaultLoginPageGeneratingFilter`가 살아남아 Spring 기본 화면이 우선 적용됩니다.

#### 4-7. MSA 게이트웨이 인증 연동 강화

| 항목 | 세부 내용 | 상태 |
| :--- | :--- | :---: |
| **CookieToAuthorizationFilter** | 게이트웨이에서 `accessToken` 쿠키 → `Authorization: Bearer` 헤더 변환 | ✅ |
| **대시보드 인증 상태** | `GET /api/v1/users/me` 응답으로 로그인 여부 판별, 로그아웃 버튼 활성화 | ✅ |
| **JWT HttpOnly 쿠키** | `FormLoginSuccessHandler` · `OAuth2SuccessHandler` — `accessToken` / `refreshToken` 쿠키 발급 | ✅ |
| **포트 마스킹** | `RewriteLocationResponseHeader=AS_IN_REQUEST` 게이트웨이 필터로 내부 포트(8080) 노출 차단 | 🔄 |
| **forward-headers-strategy** | `framework` 설정으로 프록시 헤더(`X-Forwarded-*`) 정상 처리 | ✅ |

#### 4-8. Render 배포 준비 (환경변수 분리)

| 환경변수 | 용도 | 기본값 (로컬) |
| :--- | :--- | :--- |
| `GATEWAY_URL` | 로그인 성공 후 리다이렉트 기준 URL | `http://localhost:8000` |
| `REDIRECT_PATH` | 로그인 성공 후 이동 경로 | `/me` |
| `OAUTH2_CLIENT_SECRET` | OAuth2 클라이언트 시크릿 | `{noop}secret` |
| `OAUTH2_REDIRECT_URI` | OAuth2 인가 코드 콜백 URI | `http://127.0.0.1:8080/...` |
| `GOOGLE_CLIENT_ID` | Google OAuth2 클라이언트 ID | `dummy` |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 클라이언트 시크릿 | `dummy` |
| `JWT_SECRET` | JWT 서명 키 | 로컬 기본값 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 데이터베이스 연결 정보 | 로컬 PostgreSQL |

**분리 원칙**: 모든 민감 정보 및 환경 종속 URL을 `@Value("${KEY:default}")` 패턴으로 주입. `application-dev.yaml`에서 `auth.cookie.secure: false`를 설정하여 로컬 HTTP 환경에서도 쿠키 동작 보장.

---
### 🔗 연관 자료

- **[트래픽 제어 전략 문서](../TRAFFIC_CONTROL_STRATEGY.md)**