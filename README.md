<div align="center">

# Auth Gateway Platform

**여러 서비스의 인증·인가를 어떻게 일관되게 관리할 것인가?**

</div>

## 소개

Spring Cloud Gateway를 단일 진입점으로 두고, Authorization Server와 여러 Resource Server의 인증·인가 경계를 설계한 멀티모듈 백엔드 시스템입니다.

인증과 JWT 서명은 Authorization Server 한 곳으로 모으고, 토큰 검증과 인가는 자원을 가진 각 서비스가 직접 수행합니다. Gateway는 라우팅과 `accessToken` Cookie → `Authorization: Bearer` 변환만 담당하며 JWT를 검증하지 않습니다.

## 서비스 구성

| 모듈 | 포트 | 역할 |
|:---|:---:|:---|
| **gateway-service** | 8082 | 단일 진입점, 라우팅, Cookie → Bearer 적응 |
| **auth-module** | 8080 | 사용자 인증, JWT 발급·갱신(RTR), JWKS 공개 |
| **task-module** | 8083 | 인증 서비스 — JWT 검증 후 Task API |
| **admin-module** | 8085 | 인증·권한 서비스 — JWT 검증 + `ROLE_ADMIN` 인가 |
| **preview-module** | 8084 | 미인증 서비스 — 모든 요청 공개 |

## 핵심 아키텍처

```mermaid
flowchart LR
    Client([Client]) --> Gateway["Gateway :8082<br/>라우팅 · Cookie → Bearer"]
    Gateway --> Auth["auth-module :8080<br/>인증 · JWT 발급 · RTR"]
    Gateway --> Task["task-module :8083<br/>JWT 검증 · 인증 서비스"]
    Gateway --> Admin["admin-module :8085<br/>JWT 검증 · ROLE_ADMIN"]
    Gateway --> Preview["preview-module :8084<br/>미인증 서비스"]
    Auth ~~~ Task ~~~ Preview ~~~ Admin
    Auth --> JWKS["/oauth2/jwks<br/>공개키"]
    JWKS -.-> Task
    JWKS -.-> Admin

    style Gateway fill:#3b82f6,color:#fff
    style Auth fill:#f59e0b,color:#fff
    style Task fill:#10b981,color:#fff
    style Admin fill:#ef4444,color:#fff
    style Preview fill:#64748b,color:#fff
```

개인키는 auth-module에만 두고 공개키만 JWKS로 배포합니다. Resource Server는 매 요청마다 Authorization Server에 묻지 않고 공개키로 서명을 검증합니다.

설계 배경, 의사결정 근거, 요청 흐름, RTR 상세는 [아키텍처 설계 문서](./docs/architecture/auth-gateway-design.md)에 있습니다.

## 실행 방법

```bash
./gradlew build
```

모듈별 실행 (dev 프로파일은 H2 인메모리 사용):

```bash
./gradlew :auth-module:bootRun --args='--spring.profiles.active=dev'
```

prod 프로파일은 다음 환경변수가 필요합니다.

`DB_URL` · `DB_USERNAME` · `DB_PASSWORD` · `GOOGLE_CLIENT_ID` · `GOOGLE_CLIENT_SECRET` · `JWT_SECRET` · `GATEWAY_URL`

테스트:

```bash
./gradlew test
```

## 핵심 링크

| 문서 | 내용 |
|:---|:---|
| [아키텍처 설계](./docs/architecture/auth-gateway-design.md) | 문제 정의부터 최종 구조까지 전체 설계 기록 |
| [토큰 전략](./docs/auth/token_strategy_guide.md) | Access/Refresh Token 정책 |
| [기술 선택 기록](./docs/DECISION_LOG_WHY.md) | 왜 이 기술을 골랐는가 |
| [트래픽 제어 전략](./docs/TRAFFIC_CONTROL_STRATEGY.md) | Waiting Room·Rate Limit 확장 방향 |
| [엔지니어링 정리](./docs/engineering/2026-05-10-harness-engineering.md) | 구현 과정 전체 회고 |

### Live Demo

| 서비스 | URL |
|:---|:---|
| Gateway Portal | [api-gateway-m46j.onrender.com](https://api-gateway-m46j.onrender.com) |
| Preview Portal | [preview-l7aj.onrender.com](https://preview-l7aj.onrender.com) |
| Task Portal | [task-1px8.onrender.com](https://task-1px8.onrender.com) |
