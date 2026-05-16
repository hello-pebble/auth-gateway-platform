# 📅 2026-05-16 개발 일지: [admin-module] 관리자 강제 계정 정지(Penalty) 정책 설계 및 API 초안 작성

## 1. 시작 전
- **목표**: `admin-module`에 사용자 강제 계정 정지 및 패널티 부여 기능(사유, 기간 등) 설계 및 API 구현
- **배경**: 단순 `isBlocked` 상태 변경을 넘어, 정지 사유와 기간(영구/일시)을 명확히 관리하기 위한 패널티 기능 고도화

## 2. 진행 과정

### 🚨 Case Study: 관리자 패널티 부여 시 발생 가능한 문제 상황 (Edge Cases)
패널티 기능을 구현하기 전, 발생할 수 있는 케이스를 먼저 정의하고 이를 방어하는 설계를 반영합니다.

*   **Case 1.1: 중복 패널티 부여 (Idempotency)**
    *   **상황**: 관리자가 이미 영구 정지된 사용자에게 다시 일시 정지 패널티를 부여하려고 시도함.
    *   **방어책**: 현재 사용자가 이미 영구 정지 상태라면 추가 일시 정지를 막거나 덮어쓰기 여부를 묻는 정책 필요. API 단에서는 상태 충돌 시 예외 반환.
*   **Case 1.2: 잘못된 기간 설정 (Validation)**
    *   **상황**: 관리자가 패널티 기간을 음수나 0으로 설정하여 요청함.
    *   **방어책**: 요청 DTO 검증(Validation)을 통해 1일 이상 또는 영구(Permanent) 상태만 허용되도록 `durationDays` 값 검증.
*   **Case 1.3: 자기 자신(관리자) 정지 (Self-Harm)**
    *   **상황**: 관리자 본인의 ID를 대상으로 패널티 API를 호출함.
    *   **방어책**: 대상 `userId`가 현재 인증된 관리자의 `userId`와 동일한지 체크하여 블락. (현재 시스템 상 토큰 정보에서 검증 가능)

### 🛠️ 구현 내용
- [x] Case Study 기반 패널티 정책(사유, 기간) 정의
- [x] `admin-module`의 `PenaltyRequest` DTO 생성 및 `AdminController`에 새로운 패널티 API 엔드포인트 추가
- [x] `MatchingInternalClient`의 호출 인터페이스 확장 (`blockUser` 외에 `applyPenalty` 메서드 추가)

## 3. 마무리
- **결과**: `admin-module` 단의 패널티 API 설계 및 DTO 구현 완료
- **다음 단계**: (5/19) `matching-module` 내부 API에 패널티 상태 도메인 모델 반영
- **관련 문서**: `docs/DECISION_LOG_WHY.md` (패널티 정책 결정 사항 기록 예정)
