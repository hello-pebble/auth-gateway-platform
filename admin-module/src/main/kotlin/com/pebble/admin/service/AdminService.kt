package com.pebble.admin.service

import com.pebble.admin.client.MatchingInternalClient
import com.pebble.admin.dto.AdminMatchDto
import com.pebble.admin.dto.AdminRankingDto
import com.pebble.admin.dto.AdminUserDto
import com.pebble.admin.dto.BlockUpdateRequest
import com.pebble.admin.dto.ExposureUpdateRequest
import com.pebble.admin.dto.MatchRateDto
import com.pebble.admin.dto.PageResponse
import com.pebble.admin.dto.SummaryStatsDto
import com.pebble.admin.dto.TopRankedUserDto
import org.springframework.stereotype.Service

@Service
class AdminService(
    private val matchingClient: MatchingInternalClient
) {
    fun getAllUsers(token: String, page: Int, size: Int): PageResponse<AdminUserDto> {
        val all = matchingClient.getAllUsers(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun updateExposure(userId: Long, request: ExposureUpdateRequest, token: String) =
        matchingClient.updateExposure(userId, request, token)

    fun blockUser(userId: Long, request: BlockUpdateRequest, token: String) =
        matchingClient.blockUser(userId, request, token)

    fun getAllMatches(token: String, page: Int, size: Int): PageResponse<AdminMatchDto> {
        val all = matchingClient.getAllMatches(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun deleteMatch(matchId: String, token: String) =
        matchingClient.deleteMatch(matchId, token)

    fun getAllRankings(token: String, page: Int, size: Int): PageResponse<AdminRankingDto> {
        val all = matchingClient.getAllRankings(token)
        val paged = all.drop(page * size).take(size)
        return PageResponse(paged, page, size, all.size)
    }

    fun getSummaryStats(token: String): SummaryStatsDto {
        val users = matchingClient.getAllUsers(token)
        return SummaryStatsDto(
            totalUsers = users.size,
            activeUsers = users.count { it.isExposed && !it.isBlocked },
            totalMatches = matchingClient.getAllMatches(token).size,
            blockCount = users.count { it.isBlocked }
        )
    }

    fun getMatchRate(token: String): MatchRateDto {
        val rankings = matchingClient.getAllRankings(token)
        val matches = matchingClient.getAllMatches(token)
        val rate = if (rankings.isEmpty()) 0.0 else (matches.size * 2.0) / rankings.size
        return MatchRateDto(rankings.size, matches.size, rate)
    }

    fun getTopRanked(token: String, limit: Int): List<TopRankedUserDto> =
        matchingClient.getAllRankings(token)
            .groupBy { it.toUserId }
            .map { (userId, rankings) -> TopRankedUserDto(userId, rankings.size) }
            .sortedByDescending { it.receivedCount }
            .take(limit)
}
