package com.pebble.matching.controller

import com.pebble.matching.domain.ChatMatch
import com.pebble.matching.domain.MatchResult
import com.pebble.matching.domain.MatchingService
import com.pebble.matching.domain.RecommendedUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/matching")
class MatchingController(
    private val matchingService: MatchingService
) {
    private fun getUserIdFromJwt(jwt: Jwt?): Long {
        return jwt?.subject?.toLongOrNull() ?: 0L
    }

    @GetMapping("/recommendations")
    fun getRecommendations(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<RecommendedUser>> {
        return ResponseEntity.ok(matchingService.getRecommendations(getUserIdFromJwt(jwt)))
    }

    @PostMapping("/rank")
    fun rankUser(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: RankRequest
    ): ResponseEntity<MatchResult> {
        return ResponseEntity.ok(matchingService.rankUser(getUserIdFromJwt(jwt), request.toUserId, request.rank))
    }

    @PutMapping("/exposure")
    fun updateExposure(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: ExposureRequest
    ): ResponseEntity<String> {
        matchingService.updateExposure(getUserIdFromJwt(jwt), request.isExposed)
        return ResponseEntity.ok("매칭 노출 설정이 변경되었습니다.")
    }

    @GetMapping("/matches")
    fun getMyMatches(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<ChatMatch>> {
        return ResponseEntity.ok(matchingService.getMyMatches(getUserIdFromJwt(jwt)))
    }

    @GetMapping("/access-info")
    fun getAccessInfo(
        @RequestHeader headers: Map<String, String>,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Map<String, Any>> {
        val response = mutableMapOf<String, Any>()
        response["service"] = "Matching Service"
        
        val accessInfo = mapOf(
            "viaGateway" to headers.containsKey("x-forwarded-host"),
            "gatewayHeaders" to headers.filterKeys { it.startsWith("x-") }
        )
        
        val authInfo = mutableMapOf<String, Any>()
        if (jwt != null) {
            authInfo["authenticated"] = true
            authInfo["subject"]       = jwt.subject
            authInfo["roles"]         = jwt.claims["roles"]?.toString() ?: ""
            authInfo["token_preview"] = jwt.tokenValue.take(15) + "..."
        } else {
            authInfo["authenticated"] = false
        }

        val matches = if (jwt != null) matchingService.getMyMatches(getUserIdFromJwt(jwt)) else emptyList()

        response["accessInfo"]   = accessInfo
        response["authInfo"]     = authInfo
        response["matchesCount"] = matches.size
        response["matches"]      = matches
        
        return ResponseEntity.ok(response)
    }

    data class RankRequest(val toUserId: Long, val rank: Int)
    data class ExposureRequest(val isExposed: Boolean)
}
