# [BUG-001] updateExposure 호출 시 isBlocked 상태 초기화

**발견일:** 2026-05-10  
**심각도:** High  
**상태:** Fixed  
**컴포넌트:** matching-module / MatchingService  
**발견 경위:** Admin 시스템 구현 후 코드 리뷰

---

## 요약

관리자가 사용자를 차단(`isBlocked = true`)한 후, 해당 사용자가 노출 설정을 변경하면 차단 상태가 `false`로 초기화되는 버그.

---

## 재현 시나리오

1. 관리자가 `PATCH /api/v1/admin/users/1/block {"isBlocked": true}` 호출 → userId=1 차단
2. userId=1이 `PUT /api/v1/matching/exposure {"isExposed": true}` 호출 → 노출 설정 변경
3. userId=1의 `isBlocked`가 `false`로 초기화됨
4. 차단된 사용자가 다시 추천 목록에 노출되고 순위 부여를 받을 수 있게 됨

---

## 근본 원인

`MatchingService.updateExposure`가 기존 프로필을 조회하지 않고 새 `MatchingProfile` 객체를 생성:

```kotlin
// 버그 코드: isBlocked가 data class 기본값(false)으로 초기화됨
fun updateExposure(userId: Long, isExposed: Boolean) {
    val profile = MatchingProfile(userId, isExposed, updatedAt = LocalDateTime.now())
    store.saveProfile(profile)
}
```

`MatchingProfile`의 `isBlocked` 기본값은 `false`이므로, 기존 차단 상태와 무관하게 항상 `false`로 덮어씌워짐.

대조적으로 `AdminInternalController.updateExposure`는 `existing.copy()`로 올바르게 구현되어 있음:

```kotlin
// AdminInternalController: 올바른 구현 (기존 상태 보존)
val existing = store.getProfile(userId) ?: MatchingProfile(userId)
store.saveProfile(existing.copy(isExposed = request.isExposed, updatedAt = LocalDateTime.now()))
```

---

## 영향 범위

| 영향 | 설명 |
|---|---|
| 보안 | 관리자 차단이 사용자 행동으로 무력화 가능 |
| 데이터 무결성 | `isBlocked` 상태가 비결정적으로 변경됨 |
| 호출 경로 | `PUT /api/v1/matching/exposure` → `MatchingController` → `MatchingService.updateExposure` |

---

## 수정 내용

**파일:** `matching-module/src/main/kotlin/com/pebble/matching/domain/MatchingService.kt`

```kotlin
// 수정 전
fun updateExposure(userId: Long, isExposed: Boolean) {
    val profile = MatchingProfile(userId, isExposed, updatedAt = LocalDateTime.now())
    store.saveProfile(profile)
}

// 수정 후: 기존 프로필을 조회한 뒤 isExposed만 교체
fun updateExposure(userId: Long, isExposed: Boolean) {
    val existing = store.getProfile(userId) ?: MatchingProfile(userId)
    store.saveProfile(existing.copy(isExposed = isExposed, updatedAt = LocalDateTime.now()))
}
```

---

## 회귀 테스트

**파일:** `matching-module/src/test/kotlin/com/pebble/matching/MatchingServiceTest.kt`

```kotlin
@Test
fun `updateExposure preserves isBlocked status`() {
    val userId = 1L
    store.blockUser(userId, true)

    matchingService.updateExposure(userId, true)

    assertTrue(store.isBlocked(userId))
}
```

---

## 교훈

- 인메모리 저장소의 부분 업데이트는 반드시 **read-modify-write** 패턴을 사용해야 함
- `data class`의 기본값은 새 객체 생성 시 항상 리셋되므로, 기존 상태 보존이 필요한 경우 `copy()`를 사용
- Admin 로직(`AdminInternalController`)과 도메인 로직(`MatchingService`)이 동일한 저장소를 다르게 조작하는 구조에서 일관성 검토 필요
