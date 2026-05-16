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

    fun applyPenalty(adminId: Long, targetUserId: Long, request: com.pebble.admin.dto.PenaltyRequest, token: String) {
        // Case 1.3: 자기 자신(관리자) 정지 시도 차단
        if (adminId == targetUserId) {
            throw IllegalArgumentException("관리자 본인에게 패널티를 부여할 수 없습니다.")
        }
        
        // Case 1.2: 잘못된 기간 설정 차단
        if (!request.isPermanent && (request.durationDays == null || request.durationDays <= 0)) {
            throw IllegalArgumentException("일시 정지의 경우 1일 이상의 기간을 설정해야 합니다.")
        }

        // Case 1.1 중복 정지 방어는 matching-module 상태를 직접 조회해서 체크할 수도 있고, 
        // 하위 모듈에 위임할 수도 있습니다. 여기서는 matching-module이 예외를 반환하도록 위임합니다.
        matchingClient.applyPenalty(targetUserId, request, token)
    }

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
