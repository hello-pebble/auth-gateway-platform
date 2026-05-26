package com.pebble.matching

import com.pebble.matching.domain.ExternalUser
import com.pebble.matching.domain.MatchingService
import com.pebble.matching.domain.UserProvider
import com.pebble.matching.infrastructure.InMemoryMatchingStore
import com.pebble.matching.infrastructure.UserPairLockManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MatchingServiceTest {

    private lateinit var store: InMemoryMatchingStore
    private lateinit var userProvider: UserProvider
    private lateinit var lockManager: UserPairLockManager
    private lateinit var matchingService: MatchingService

    @BeforeEach
    fun setUp() {
        store = InMemoryMatchingStore()
        userProvider = mock(UserProvider::class.java)
        lockManager = UserPairLockManager()
        matchingService = MatchingService(store, userProvider, lockManager)
    }

    @Test
    fun `Concurrent ranking should result in exactly one match`() {
        val userA = 1L
        val userB = 2L
        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        
        // 유저 A와 B가 서로를 동시에 10번씩 선택하는 상황 시뮬레이션
        repeat(threadCount) {
            executor.submit { matchingService.rankUser(userA, userB, 1) }
            executor.submit { matchingService.rankUser(userB, userA, 1) }
        }
        
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        
        // 결과 확인: 중복 매칭 없이 단 1개의 ChatMatch만 생성되어야 함
        val matchesA = matchingService.getMyMatches(userA)
        val matchesB = matchingService.getMyMatches(userB)
        
        assertEquals(1, matchesA.size, "User A should have exactly 1 match")
        assertEquals(1, matchesB.size, "User B should have exactly 1 match")
        assertEquals(matchesA[0].id, matchesB[0].id, "Match IDs should be identical")
    }

    @Test
    fun `Matching Success when both rank within top 3`() {
        val userA = 1L
        val userB = 2L
        
        // When A ranks B as 1
        val result1 = matchingService.rankUser(userA, userB, 1)
        assertFalse(result1.isMatched)
        
        // When B ranks A as 3
        val result2 = matchingService.rankUser(userB, userA, 3)
        
        // Then
        assertTrue(result2.isMatched)
        assertNotNull(result2.matchId)
        
        val myMatches = matchingService.getMyMatches(userA)
        assertEquals(1, myMatches.size)
    }

    @Test
    fun `Exposed users only in recommendations`() {
        val userA = 1L
        val userB = 2L

        matchingService.updateExposure(userB, true)
        `when`(userProvider.getUserInfo(userB)).thenReturn(ExternalUser(userB, "userB"))

        val recommendations = matchingService.getRecommendations(userA)

        assertEquals(1, recommendations.size)
        assertEquals("userB", recommendations[0].username)
    }

    @Test
    fun `Blocked user is excluded from recommendations`() {
        val userA = 1L
        val userB = 2L

        matchingService.updateExposure(userB, true)
        store.blockUser(userB, true)
        `when`(userProvider.getUserInfo(userB)).thenReturn(ExternalUser(userB, "userB"))

        val recommendations = matchingService.getRecommendations(userA)

        assertEquals(0, recommendations.size)
    }

    @Test
    fun `Blocked user cannot receive ranking`() {
        val fromUserId = 1L
        val blockedUserId = 2L

        store.blockUser(blockedUserId, true)

        assertThrows(IllegalArgumentException::class.java) {
            matchingService.rankUser(fromUserId, blockedUserId, 1)
        }
    }

    @Test
    fun `updateExposure preserves isBlocked status`() {
        val userId = 1L
        store.blockUser(userId, true)

        matchingService.updateExposure(userId, true)

        assertTrue(store.isBlocked(userId))
    }
}
