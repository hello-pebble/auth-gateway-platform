package com.pebble.matching.domain

import java.time.LocalDateTime
import java.util.UUID

data class PenaltyInfo(
    val reason: String,
    val isPermanent: Boolean,
    val expiresAt: LocalDateTime? = null
)

data class MatchingProfile(
    val userId: Long,
    val isExposed: Boolean = false,
    val isBlocked: Boolean = false,
    val penaltyInfo: PenaltyInfo? = null,
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun isCurrentlyBlocked(): Boolean {
        if (penaltyInfo != null) {
            if (penaltyInfo.isPermanent) return true
            if (penaltyInfo.expiresAt != null && LocalDateTime.now().isBefore(penaltyInfo.expiresAt)) {
                return true
            }
        }
        return isBlocked
    }
}

data class MatchRanking(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(rank in 1..3) { "순위는 1위에서 3위 사이여야 합니다." }
    }
}

data class ChatMatch(
    val id: String = UUID.randomUUID().toString(),
    val userA: Long,
    val userB: Long,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

interface UserProvider {
    fun getUserInfo(userId: Long): ExternalUser?
}

data class ExternalUser(val id: Long, val username: String)
