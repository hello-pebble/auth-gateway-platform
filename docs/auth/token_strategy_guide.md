# 🔑 Token & Payload Strategy Guide

본 문서는 `base-auth` 프로젝트의 현재 토큰 운용 방식과 향후 페이로드 설계 방향을 구분해 기록합니다.

---

## 1. 토큰 관리 전략 (Token Management)

| 전략 | 방식 | 장점 | 단점 | 적용 여부 |
| :--- | :--- | :--- | :--- | :--- |
| **Stateless JWT** | 서버에 저장하지 않음 | 일반 API 요청 시 중앙 저장소 조회 불필요 | 토큰 탈취 시 즉시 무효화 불가 | **채택 (Access Token)** |
| **Refresh Token Rotation** | 재발급 시 기존 Refresh Token 교체 | 재사용 토큰 거부 가능 | 서버 측 상태 저장 필요 | **채택 (현재 인메모리 저장소)** |
| **HttpOnly Cookie** | 브라우저 쿠키 저장 | XSS 공격 방어 | CSRF 대응 필요 | **채택** |

---

## 2. 페이로드 설계 전략 (Payload Design)

| 전략 | 포함 정보 | 장점 | 단점 | 비고 |
| :--- | :--- | :--- | :--- | :--- |
| **Lean Token** | User ID 만 포함 | 토큰 크기 최소화 | 서비스마다 추가 조회 가능 | 미채택 |
| **Fat Token** | ID, 권한, 프로필 포함 | 인가에 필요한 추가 조회 감소 | 토큰 크기·정보 최신성 관리 필요 | **본 프로젝트 채택** |
| **Hybrid Token** | ID + 필수 Context | 효율적인 밸런스 | 여전히 상세정보 조회 필요 | 보조적 활용 |

---

## 3. [Recommended] 3-Tier 페이로드 설계안

인증 DB와 서비스 DB가 분리된 환경에서 검토할 수 있는 향후 설계안입니다. 현재 모든 claim이 구현된 것은 아닙니다.

| 계층 (Tier) | 데이터 성격 | 포함 항목 (Claims) | 관리 방식 |
| :--- | :--- | :--- | :--- |
| **Tier 1: High Availability** | 즉시 인가/UI용 | `sub` (UUID), `roles`, `nickname` | **JWT 페이로드에 포함** |
| **Tier 2: Service Context** | 서비스별 특화 정보 | `tenant_id`, `service_plan` | **JWT 페이로드에 포함** |
| **Tier 3: Sensitive PII** | 민감 개인정보 | 이름, 전화번호, 이메일, 주소 | **토큰 제외 (UserInfo API로 제공)** |

---

## 4. 운영 및 보안 가이드라인

| 구분 | 전략 | 상세 내용 |
| :--- | :--- | :--- |
| **식별자 정책** | **UUID (sub)** | 인증 DB의 PK 노출 방지 및 서비스 간 느슨한 결합 |
| **유효 기간** | **Short-lived** | Access Token(15m~30m), Refresh Token(7d~14d) |
| **데이터 정합성** | **Event-Driven** | 닉네임 변경 시 로그아웃 유도 또는 짧은 TTL로 자연 갱신 |
| **전송 보안** | **JWE (선택사항)** | 페이로드 암호화가 필요한 경우 도입 검토 |

---
*관련 문서: [PROJECT_MANIFESTO.md](../PROJECT_MANIFESTO.md), [ROADMAP_TO_ZERO_LOADING.md](../ROADMAP_TO_ZERO_LOADING.md)*
