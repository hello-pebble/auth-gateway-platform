package com.pebble.matching.domain

import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class MatchingService(
    private val store: InMemoryMatchingStore,
    private val userProvider: UserProvider,
    private val lockManager: com.pebble.matching.infrastructure.UserPairLockManager
) {
    fun getRecommendations(userId: Long): List<RecommendedUser> {
        val exposedUserIds = store.getAllExposedUsers()
            .filter { it != userId }
            .shuffled()
            .take(5)

        return exposedUserIds.mapNotNull { id ->
            userProvider.getUserInfo(id)?.let { user ->
                RecommendedUser(user.id, user.username)
            }
        }
    }

    fun rankUser(fromUserId: Long, toUserId: Long, rank: Int): MatchResult {
        if (fromUserId == toUserId) throw IllegalArgumentException("본인에게 순위를 부여할 수 없습니다.")

        // 락 획득 시도 (Case 5.1: Timeout 방어)
        val locked = lockManager.lock(fromUserId, toUserId)
        if (!locked) {
            return MatchResult(isMatched = false, message = "현재 요청이 많습니다. 잠시 후 다시 시도해주세요.")
        }

        try {
            if (store.isBlocked(toUserId)) throw IllegalArgumentException("차단된 사용자에게 순위를 부여할 수 없습니다.")

            val ranking = MatchRanking(fromUserId, toUserId, rank)
            store.saveRanking(ranking)

            if (store.alreadyMatched(fromUserId, toUserId)) {
                return MatchResult(isMatched = true, message = "이미 매칭된 회원입니다.")
            }

            val oppositeRank = store.getRanking(toUserId, fromUserId)
            if (oppositeRank != null && oppositeRank.rank <= 3) {
                val newMatch = ChatMatch(userA = fromUserId, userB = toUserId)
                store.saveMatch(newMatch)
                return MatchResult(isMatched = true, matchId = newMatch.id, message = "축하합니다! 상호 매칭되었습니다.")
            }

            return MatchResult(isMatched = false, message = "상대방의 선택을 기다리고 있습니다.")
        } finally {
            // Case 5.2: 락 해제 보장
            lockManager.unlock(fromUserId, toUserId)
        }
    }

    fun updateExposure(userId: Long, isExposed: Boolean) {
        val existing = store.getProfile(userId) ?: MatchingProfile(userId)
        store.saveProfile(existing.copy(isExposed = isExposed, updatedAt = LocalDateTime.now()))
    }

    fun getMyMatches(userId: Long): List<ChatMatch> = store.getMatchesForUser(userId)
}

data class RecommendedUser(val id: Long, val username: String)
data class MatchResult(val isMatched: Boolean, val matchId: String? = null, val message: String)
