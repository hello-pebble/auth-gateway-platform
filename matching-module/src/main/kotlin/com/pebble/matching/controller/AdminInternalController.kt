package com.pebble.matching.controller

import com.pebble.matching.domain.MatchingProfile
import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/internal/admin")
class AdminInternalController(
    private val store: InMemoryMatchingStore
) {
    @GetMapping("/users")
    fun getAllUsers(): ResponseEntity<List<UserDto>> =
        ResponseEntity.ok(store.getAllProfiles().map {
            UserDto(it.userId, it.isExposed, it.isBlocked, it.updatedAt.toString())
        })

    @PatchMapping("/users/{userId}/exposure")
    fun updateExposure(
        @PathVariable userId: Long,
        @RequestBody request: ExposureRequest
    ): ResponseEntity<Void> {
        val existing = store.getProfile(userId) ?: MatchingProfile(userId)
        store.saveProfile(existing.copy(isExposed = request.isExposed, updatedAt = LocalDateTime.now()))
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/users/{userId}/block")
    fun blockUser(
        @PathVariable userId: Long,
        @RequestBody request: BlockRequest
    ): ResponseEntity<Void> {
        store.blockUser(userId, request.isBlocked)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/users/{userId}/penalty")
    fun applyPenalty(
        @PathVariable userId: Long,
        @RequestBody request: PenaltyRequest
    ): ResponseEntity<Void> {
        val expiresAt = if (request.isPermanent || request.durationDays == null) {
            null
        } else {
            LocalDateTime.now().plusDays(request.durationDays.toLong())
        }
        
        val penaltyInfo = com.pebble.matching.domain.PenaltyInfo(
            reason = request.reason,
            isPermanent = request.isPermanent,
            expiresAt = expiresAt
        )
        store.applyPenalty(userId, penaltyInfo)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/matches")
    fun getAllMatches(): ResponseEntity<List<MatchDto>> =
        ResponseEntity.ok(store.getAllMatches().map {
            MatchDto(it.id, it.userA, it.userB, it.createdAt.toString())
        })

    @DeleteMapping("/matches/{matchId}")
    fun deleteMatch(@PathVariable matchId: String): ResponseEntity<Void> {
        store.deleteMatch(matchId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/rankings")
    fun getAllRankings(): ResponseEntity<List<RankingDto>> =
        ResponseEntity.ok(store.getAllRankings().map {
            RankingDto(it.fromUserId, it.toUserId, it.rank, it.createdAt.toString())
        })

    data class UserDto(val userId: Long, val isExposed: Boolean, val isBlocked: Boolean, val updatedAt: String)
    data class MatchDto(val matchId: String, val userA: Long, val userB: Long, val createdAt: String)
    data class RankingDto(val fromUserId: Long, val toUserId: Long, val rank: Int, val createdAt: String)
    data class ExposureRequest(val isExposed: Boolean)
    data class BlockRequest(val isBlocked: Boolean)
    data class PenaltyRequest(val reason: String, val durationDays: Int? = null, val isPermanent: Boolean = false)
}
