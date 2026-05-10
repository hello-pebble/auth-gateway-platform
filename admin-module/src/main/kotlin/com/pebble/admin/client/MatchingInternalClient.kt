package com.pebble.admin.client

import com.pebble.admin.dto.AdminMatchDto
import com.pebble.admin.dto.AdminRankingDto
import com.pebble.admin.dto.AdminUserDto
import com.pebble.admin.dto.BlockUpdateRequest
import com.pebble.admin.dto.ExposureUpdateRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class MatchingInternalClient(
    @param:Value("\${matching.internal-url}") private val matchingUrl: String
) {
    private val restClient = RestClient.create()

    fun getAllUsers(token: String): List<AdminUserDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/users")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminUserDto>>() {})
            ?: emptyList()

    fun updateExposure(userId: Long, request: ExposureUpdateRequest, token: String) {
        restClient.patch()
            .uri("$matchingUrl/internal/admin/users/$userId/exposure")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toBodilessEntity()
    }

    fun blockUser(userId: Long, request: BlockUpdateRequest, token: String) {
        restClient.patch()
            .uri("$matchingUrl/internal/admin/users/$userId/block")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .retrieve()
            .toBodilessEntity()
    }

    fun getAllMatches(token: String): List<AdminMatchDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/matches")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminMatchDto>>() {})
            ?: emptyList()

    fun deleteMatch(matchId: String, token: String) {
        restClient.delete()
            .uri("$matchingUrl/internal/admin/matches/$matchId")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .toBodilessEntity()
    }

    fun getAllRankings(token: String): List<AdminRankingDto> =
        restClient.get()
            .uri("$matchingUrl/internal/admin/rankings")
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<AdminRankingDto>>() {})
            ?: emptyList()
}
