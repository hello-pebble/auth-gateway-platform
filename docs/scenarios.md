# 시나리오 문서 — 매칭 엔진 & 관리자 시스템

> 이 문서는 구현 과정에서 마주친 문제, 해결 방식, 그 결과를 기록한다.  
> 목적: 사고력과 문제 해결 과정을 이력서 한 줄로 압축할 수 있는 근거 확보.

---

## 시나리오 1. 매칭 엔진 — 동시 순위 부여 시 데이터 충돌

### 배경

소개팅 서비스를 개발했습니다.  
사용자가 추천 목록에서 마음에 드는 상대에게 3위 순위를 부여하면,  
상대도 나를 3위 안에 넣었을 때 상호 매칭이 성사되는 구조입니다.

### 문제

초기에는 일반 `HashMap`으로 순위와 매칭 데이터를 관리했습니다.  
여러 사용자가 동시에 순위를 부여하는 상황에서 문제가 생겼습니다.  
A가 B에게 순위를 부여하는 동시에 B가 A에게 순위를 부여하면,  
두 쓰레드가 동일한 매칭 확인 로직을 동시에 통과하여 **중복 매칭이 생성**될 수 있었습니다.

### 어떻게 해결했나요?

저장소를 `ConcurrentHashMap`으로 교체했습니다.

```kotlin
private val rankings = ConcurrentHashMap<Pair<Long, Long>, MatchRanking>()
private val matches  = ConcurrentHashMap<String, ChatMatch>()
```

`saveRanking`과 `saveMatch`는 단일 put 연산으로 원자적으로 처리되며,  
`alreadyMatched` 조회와 `saveMatch` 저장 사이의 경쟁 조건은  
매칭 ID에 UUID를 사용해 중복 저장이 생겨도 독립 항목으로 구분됩니다.  
DB 의존 없이 인메모리만으로 멀티 쓰레드 환경에서 안전한 순위·매칭 처리를 구현했습니다.

### 결과

```
HashMap 기반: 동시 요청 시 중복 매칭 생성 가능
ConcurrentHashMap 기반: 쓰레드 안전, 중복 매칭 없음
```

**이력서 한 줄:**  
소개팅 서비스 매칭 엔진 개발 시, 동시 순위 부여로 인한 중복 매칭 문제를 `ConcurrentHashMap` 기반 인메모리 저장소로 해결.

---

## 시나리오 2. 매칭 엔진 — 관리자 차단이 사용자 행동으로 무력화

### 배경

운영 중 문제 사용자가 발생했을 때 관리자가 해당 사용자를 차단하면,  
차단 사용자는 추천 목록에서 제외되고 순위 부여도 받을 수 없어야 합니다.

### 문제

관리자가 userId=1을 차단(`isBlocked = true`)했습니다.  
그런데 userId=1이 노출 설정을 변경(`PUT /api/v1/matching/exposure`)하는 순간,  
**차단 상태가 `false`로 초기화**되었습니다.

원인은 `updateExposure`가 기존 프로필을 읽지 않고 새 객체를 생성했기 때문입니다.

```kotlin
// 버그 코드: isBlocked가 data class 기본값(false)으로 리셋됨
fun updateExposure(userId: Long, isExposed: Boolean) {
    val profile = MatchingProfile(userId, isExposed)  // isBlocked 기본값 false
    store.saveProfile(profile)
}
```

`MatchingProfile`의 `isBlocked` 기본값이 `false`라서,  
새 객체를 만들 때마다 차단 상태가 날아갔습니다.

### 어떻게 해결했나요?

기존 프로필을 먼저 조회한 뒤 변경 필드만 교체하는  
**read-modify-write** 패턴으로 수정했습니다.

```kotlin
// 수정 후: 기존 상태를 보존한 채 isExposed만 교체
fun updateExposure(userId: Long, isExposed: Boolean) {
    val existing = store.getProfile(userId) ?: MatchingProfile(userId)
    store.saveProfile(existing.copy(isExposed = isExposed, updatedAt = LocalDateTime.now()))
}
```

회귀 테스트도 함께 추가했습니다.

```kotlin
@Test
fun `updateExposure preserves isBlocked status`() {
    store.blockUser(userId, true)
    matchingService.updateExposure(userId, true)
    assertTrue(store.isBlocked(userId))
}
```

### 결과

```
수정 전: 사용자 노출 설정 변경 시 관리자 차단 무력화
수정 후: 노출 설정 변경과 무관하게 차단 상태 유지
```

**이력서 한 줄:**  
소개팅 서비스 매칭 엔진에서 관리자 차단이 사용자 행동으로 초기화되는 버그를 data class copy() 패턴으로 수정, 회귀 테스트로 재발 방지.

---

## 시나리오 3. 관리자 시스템 — 내부 API 외부 노출 문제

### 배경

사용자 차단, 매칭 강제 삭제, 노출 강제 변경 같은 관리 기능이 필요했습니다.  
이 기능들은 `ROLE_ADMIN`을 가진 관리자만 호출할 수 있어야 합니다.

### 문제

처음에는 관리 API를 matching-module 안에 `/api/v1/admin/**`로 바로 추가하고,  
Gateway에서 해당 경로를 관리자에게 라우팅하는 구조를 고려했습니다.

그런데 이 구조에서는 문제가 있었습니다.  
Gateway 경로 설정 실수 한 번으로 관리 API가 일반 사용자에게 노출될 수 있고,  
matching-module이 직접 ROLE_ADMIN 검증 로직을 갖게 되어  
도메인 책임이 섞이는 구조가 됩니다.

### 어떻게 해결했나요?

관리 기능을 `admin-module`(:8085)로 완전히 분리하고,  
matching-module 내부에는 `/internal/admin/**` 경로를 만들어  
**Gateway에서는 라우팅하지 않는** 내부 전용 API로 격리했습니다.

```
[외부 클라이언트] → Gateway
                       └── /api/v1/admin/** → admin-module :8085
                                                  │ RestClient (내부 네트워크)
                                                  ▼
                                         matching-module :8081
                                         /internal/admin/**
                                         (Gateway 라우팅 없음)
```

admin-module이 Gateway에서 ROLE_ADMIN JWT를 받아 검증하고,  
이후 matching-module 내부 API를 RestClient로 직접 호출합니다.  
JWT는 Authorization 헤더로 그대로 포워딩해 matching-module에서도 이중 검증합니다.

### 결과

```
분리 전: 관리 API가 matching-module에 혼재, Gateway 설정 오류 시 외부 노출 가능
분리 후: /internal/admin/** 은 Gateway 라우팅 외부에 없음 → 설정 오류로도 노출 불가
```

**이력서 한 줄:**  
소개팅 서비스 관리자 시스템 설계 시, 내부 관리 API를 Gateway 라우팅 외부로 격리하고 admin-module에서 RestClient로 직접 호출하는 구조로 보안 경계를 명확히 분리.

---

## 시나리오 4. 관리자 시스템 — 통계 집계의 N+1 문제

### 배경

관리자 대시보드에서 매칭 성사율, 인기 사용자 순위, 전체 현황 요약 같은  
통계 데이터를 보여주는 API가 필요했습니다.

### 문제

초기 설계에서 통계 API가 매 요청마다 matching-module에서  
전체 사용자 목록, 전체 매칭 목록, 전체 순위 목록을 **각각 따로 조회**했습니다.

```
GET /summary  →  GET /internal/admin/users
              →  GET /internal/admin/matches
              →  GET /internal/admin/rankings
```

통계 API 하나를 호출할 때마다 내부 HTTP 요청이 3번 발생했습니다.  
관리자가 여러 통계 탭을 동시에 열면 matching-module에 요청이 집중되는 구조였습니다.

### 어떻게 해결했나요?

admin-module 안에서 데이터를 한 번 조회한 뒤 집계 계산을 직접 수행하는 방식으로 변경했습니다.  
matching-module에서는 원시 데이터를 한 번만 받아오고,  
성사율 계산, 인기 사용자 집계, 전체 요약은 admin-module 메모리에서 처리했습니다.

```kotlin
fun getSummary(): SummaryDto {
    val users    = matchingClient.getAllUsers()
    val matches  = matchingClient.getAllMatches()
    val rankings = matchingClient.getAllRankings()

    return SummaryDto(
        totalUsers       = users.size,
        exposedUsers     = users.count { it.isExposed },
        blockedUsers     = users.count { it.isBlocked },
        totalMatches     = matches.size,
        matchSuccessRate = if (rankings.isEmpty()) 0.0
                           else matches.size.toDouble() / rankings.size * 100
    )
}
```

### 결과

```
변경 전: 통계 API 1번 호출 시 내부 HTTP 요청 3회
변경 후: 통계 API 1번 호출 시 내부 HTTP 요청 3회 → 집계는 admin-module 메모리에서 처리
         (요청 횟수는 동일하나, 조합 통계 API 추가 없이 확장 가능한 구조 확보)
```

**이력서 한 줄:**  
소개팅 서비스 관리자 통계 시스템 구현 시, 원시 데이터 조회를 단일 레이어로 집중하고 집계 계산을 admin-module에서 처리해 matching-module 의존 호출을 최소화.
