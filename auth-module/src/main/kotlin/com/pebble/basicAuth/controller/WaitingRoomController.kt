package com.pebble.basicAuth.controller

import com.pebble.basicAuth.config.WaitingRoomService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/**
 * [Phase 4: Waiting Room Polling API]
 */
@RestController
@RequestMapping("/api/v1/waiting-room")
class WaitingRoomController(
    private val waitingRoomService: WaitingRoomService
) {

    /**
     * 서비스 진입 요청 (대기열 등록)
     */
    @PostMapping("/enter")
    fun enterService(
        authentication: Authentication,
        @RequestParam serviceId: String
    ): ResponseEntity<WaitingRoomService.WaitingStatus> {
        val status = waitingRoomService.register(authentication.name, serviceId)
        return ResponseEntity.ok(status)
    }

    /**
     * 현재 대기 상태 확인 (Polling)
     */
    @GetMapping("/status")
    fun getStatus(
        @RequestParam userId: String,
        @RequestParam(defaultValue = "matching-service") serviceId: String
    ): ResponseEntity<WaitingRoomService.WaitingStatus> {
        val isAllowed = waitingRoomService.isUserAllowed(userId, serviceId)
        val status = if (isAllowed) "ALLOWED" else "WAITING"
        return ResponseEntity.ok(WaitingRoomService.WaitingStatus(status, 0))
    }
}
