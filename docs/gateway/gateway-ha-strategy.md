# Gateway HA 전략

## 현재 구조와 문제

```
클라이언트 → Gateway(8000)
              ├─ Cookie → Authorization 헤더 변환
              ├─ auth-module   (8080)
              ├─ task-module   (8083)
              ├─ matching-module (8081)
              └─ preview-module  (8084)
```

게이트웨이가 유일한 진입점이자 쿠키→JWT 변환 담당 → **SPOF(단일 장애점)**

---

## 방법론 비교

### 대중적인 방법론

| 방법론 | 개요 | 장점 | 단점 | 난이도 |
|---|---|---|---|---|
| **게이트웨이 다중화 + LB** | Nginx/HAProxy 앞단, 게이트웨이 N개 운영 | 검증된 방식, 무중단 | 인프라 비용 증가 | ★★☆ |
| **Kubernetes HPA** | K8s가 Pod 자동 복구/스케일 | 자동화, 운영 부담 최소 | K8s 셋업 필요 | ★★★ |
| **Service Mesh (Istio)** | 사이드카로 서비스 간 직접 통신 | 게이트웨이 의존 제거 | 복잡도 매우 높음 | ★★★★ |
| **Circuit Breaker (Resilience4j)** | 하위 서비스 장애 시 fallback 반환 | 장애 전파 차단 | 게이트웨이 자체 장애엔 무력 | ★★☆ |
| **DNS Failover** | 헬스체크 + TTL로 백업 게이트웨이 전환 | 구현 간단 | TTL 전파 지연(수십 초) | ★★☆ |

### 참신한 방법론

| 방법론 | 개요 | 장점 | 단점 | 난이도 |
|---|---|---|---|---|
| **Edge Gateway (Cloudflare Workers)** | 변환 로직을 CDN 엣지에 배포 | 중앙 서버 장애와 무관 | 외부 서비스 의존 | ★★★ |
| **클라이언트 직접 디스커버리** | Consul/Eureka로 클라이언트가 서비스 직접 발견 | 게이트웨이 완전 제거 가능 | 클라이언트 복잡도 증가 | ★★★ |
| **eBPF 기반 메시 (Cilium)** | 커널 레벨 트래픽 제어, 사이드카 불필요 | 오버헤드 최소 | 인프라 수준 설정 필요 | ★★★★★ |
| **게이트웨이 로직 라이브러리화** | 각 서비스에 공통 필터 내장 | 게이트웨이 의존 제거 | 버전 관리 부담 | ★★★ |

---

## 현 프로젝트 적용 제안

### 1단계 — 게이트웨이 다중화 + Nginx (즉시 적용 권장)

```
클라이언트
    ↓
  Nginx (80/443)  ← 헬스체크 + 라운드로빈
  ├─ gateway:8000 (인스턴스 1)
  └─ gateway:8083 (인스턴스 2)
```

`CookieToAuthorizationFilter`는 stateless → 다중 인스턴스에 바로 적용 가능

### 2단계 — Resilience4j Circuit Breaker (중기)

`gateway-service/build.gradle.kts`에 의존성 추가 후 라우트별 circuit breaker 설정.  
하위 서비스 장애 시 fallback 응답 반환으로 장애 전파 차단.

### 장기 — 쿠키 변환 책임 이동

`CookieToAuthorizationFilter`는 이미 `Authorization` 헤더가 있으면 통과하는 분기를 가짐.  
클라이언트가 쿠키 대신 `Bearer` 헤더를 직접 전송하도록 변경하면 게이트웨이 의존도를 낮출 수 있음.
