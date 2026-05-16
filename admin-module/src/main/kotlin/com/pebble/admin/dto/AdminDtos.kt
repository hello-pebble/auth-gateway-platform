package com.pebble.admin.dto

data class AdminUserDto(
    val userId: Long,
    val isExposed: Boolean,
    val isBlocked: Boolean,
    val updatedAt: String
)

data class AdminMatchDto(
    val matchId: String,
    val userA: Long,
    val userB: Long,
    val createdAt: String
)

data class AdminRankingDto(
    val fromUserId: Long,
    val toUserId: Long,
    val rank: Int,
    val createdAt: String
)

data class SummaryStatsDto(
    val totalUsers: Int,
    val activeUsers: Int,
    val totalMatches: Int,
    val blockCount: Int
)

data class MatchRateDto(
    val totalRankings: Int,
    val totalMatches: Int,
    val matchRate: Double
)

data class TopRankedUserDto(
    val userId: Long,
    val receivedCount: Int
)

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Int
)

data class ExposureUpdateRequest(val isExposed: Boolean)
data class BlockUpdateRequest(val isBlocked: Boolean)

data class PenaltyRequest(
    val reason: String,
    val durationDays: Int? = null,
    val isPermanent: Boolean = false
)
