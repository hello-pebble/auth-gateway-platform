# 📅 2026-05-19 개발 일지: [matching-module] 내부 API에 패널티 상태(정지 사유, 기간 등) 도메인 모델 반영

## 1. 시작 전
- **목표**: `matching-module` 내부에 패널티 상태를 저장할 도메인 모델(`PenaltyInfo`)을 추가하고, Admin의 패널티 부여 요청을 처리할 내부 API 구현
- **배경**: `admin-module`에서 전송되는 패널티 정보(사유, 기간, 영구 여부)를 수신하여 매칭 로직에서 활용할 수 있도록 상태를 영속화(현재는 InMemory)해야 함

## 2. 진행 과정

### 🚨 Case Study: 패널티 도메인 모델 반영 시 발생 가능한 문제 상황
패널티 상태 관리 로직 구현 시 발생할 수 있는 엣지 케이스를 고려하여 설계합니다.

*   **Case 2.1: 패널티 기간 만료 처리 (Lazy Evaluation)**
    *   **상황**: 7일 정지 패널티를 받은 사용자의 정지 기간이 지났음에도 계속 정지 상태로 남아 매칭을 받지 못함.
    *   **방어책**: 도메인 모델에 `expiresAt` (만료 일시) 필드를 두고, 사용자가 매칭을 요청하거나 추천을 받을 때 (Lazy Evaluation) 만료 여부를 검사하여 동적으로 상태를 해제하도록 처리. (배치 스케줄러 대비 리소스 절약)
*   **Case 2.2: 기존 패널티 덮어쓰기 (Overwrite Policy)**
    *   **상황**: 이미 '3일 정지'를 받은 사용자에게 관리자가 '영구 정지' 패널티를 다시 부여함.
    *   **방어책**: 새로운 패널티 요청이 들어오면 기존 패널티 정보를 덮어쓰도록 `InMemoryMatchingStore`의 로직을 구성.
*   **Case 2.3: 존재하지 않는 사용자 패널티**
    *   **상황**: 매칭 서비스에 프로필이 아직 생성되지 않은 사용자에게 패널티가 인입됨.
    *   **방어책**: 패널티 정보를 받으면, 즉시 비활성 상태(`isExposed=false, isBlocked=true`)의 새 `MatchingProfile`을 생성하고 패널티 정보를 기록함.

### 🛠️ 구현 내용
- [x] `MatchingModels.kt`에 `PenaltyInfo` 도메인(값 객체) 추가 및 `MatchingProfile`에 필드 병합
- [x] `InMemoryMatchingStore`에 패널티 적용 및 만료 검사 로직 추가 (Lazy Evaluation)
- [x] `AdminInternalController`에 `/internal/admin/users/{userId}/penalty` POST 엔드포인트 추가

## 3. 마무리
- **결과**: `matching-module`이 Admin의 패널티 요청을 수용하고, 기간 만료 로직을 안전하게 처리할 수 있는 기반 확보
- **다음 단계**: (5/20) 통합 연동 테스트 및 확인
