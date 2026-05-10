package com.pebble.admin.controller

import com.pebble.admin.dto.AdminMatchDto
import com.pebble.admin.dto.AdminRankingDto
import com.pebble.admin.dto.AdminUserDto
import com.pebble.admin.dto.BlockUpdateRequest
import com.pebble.admin.dto.ExposureUpdateRequest
import com.pebble.admin.dto.MatchRateDto
import com.pebble.admin.dto.PageResponse
import com.pebble.admin.dto.SummaryStatsDto
import com.pebble.admin.dto.TopRankedUserDto
import com.pebble.admin.service.AdminService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val adminService: AdminService
) {
    @GetMapping("/users")
    fun getAllUsers(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminUserDto>> =
        ResponseEntity.ok(adminService.getAllUsers(jwt.tokenValue, page, size))

    @PatchMapping("/users/{userId}/exposure")
    fun updateExposure(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: Long,
        @RequestBody request: ExposureUpdateRequest
    ): ResponseEntity<Void> {
        adminService.updateExposure(userId, request, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/users/{userId}/block")
    fun blockUser(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable userId: Long,
        @RequestBody request: BlockUpdateRequest
    ): ResponseEntity<Void> {
        adminService.blockUser(userId, request, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/matches")
    fun getAllMatches(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminMatchDto>> =
        ResponseEntity.ok(adminService.getAllMatches(jwt.tokenValue, page, size))

    @DeleteMapping("/matches/{matchId}")
    fun deleteMatch(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable matchId: String
    ): ResponseEntity<Void> {
        adminService.deleteMatch(matchId, jwt.tokenValue)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/rankings")
    fun getAllRankings(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<PageResponse<AdminRankingDto>> =
        ResponseEntity.ok(adminService.getAllRankings(jwt.tokenValue, page, size))

    @GetMapping("/stats/summary")
    fun getSummaryStats(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<SummaryStatsDto> =
        ResponseEntity.ok(adminService.getSummaryStats(jwt.tokenValue))

    @GetMapping("/stats/match-rate")
    fun getMatchRate(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MatchRateDto> =
        ResponseEntity.ok(adminService.getMatchRate(jwt.tokenValue))

    @GetMapping("/stats/top-ranked")
    fun getTopRanked(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "10") limit: Int
    ): ResponseEntity<List<TopRankedUserDto>> =
        ResponseEntity.ok(adminService.getTopRanked(jwt.tokenValue, limit))
}
