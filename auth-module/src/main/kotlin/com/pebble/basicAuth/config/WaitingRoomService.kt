package com.pebble.basicAuth.config

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * [Phase 4: Virtual Waiting Room Service]
 * Refined to handle post-login service entry.
 */
@Service
class WaitingRoomService {

    // 사용자별 서비스 접근 허용 여부 관리 (실제 환경에선 Redis 권장)
    private val grantedUsers = ConcurrentHashMap<String, MutableSet<String>>()

    fun register(userId: String, serviceId: String): WaitingStatus {
        // 임시 로직: 즉시 허용 (실제 트래픽 제어 로직 추가 가능)
        grantedUsers.computeIfAbsent(userId) { mutableSetOf() }.add(serviceId)
        return WaitingStatus(status = "ALLOWED", rank = 0)
    }

    fun isUserAllowed(userId: String, serviceId: String): Boolean {
        return grantedUsers[userId]?.contains(serviceId) ?: false
    }

    data class WaitingStatus(val status: String, val rank: Long)
}
