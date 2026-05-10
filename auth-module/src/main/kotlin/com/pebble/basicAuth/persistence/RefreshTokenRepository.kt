package com.pebble.basicAuth.persistence

import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * [Phase 2-2] 인메모리(HashMap)를 이용한 Refresh Token 저장소
 * (배포 편의를 위해 Redis 대신 In-memory 방식을 사용합니다.)
 */
@Repository
class RefreshTokenRepository {

    private val storage = ConcurrentHashMap<String, String>()

    /**
     * Refresh Token 저장
     */
    fun save(username: String, refreshToken: String, expirationMillis: Long) {
        storage[PREFIX + username] = refreshToken
    }

    /**
     * 사용자명으로 Refresh Token 조회
     */
    fun findByUsername(username: String): Optional<String> {
        val token = storage[PREFIX + username]
        return Optional.ofNullable(token)
    }

    /**
     * Refresh Token 삭제
     */
    fun deleteByUsername(username: String) {
        storage.remove(PREFIX + username)
    }

    companion object {
        private const val PREFIX = "RT:" 
    }
}
