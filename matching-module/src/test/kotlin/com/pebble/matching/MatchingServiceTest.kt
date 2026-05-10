package com.pebble.matching

import com.pebble.matching.domain.ExternalUser
import com.pebble.matching.domain.MatchingService
import com.pebble.matching.domain.UserProvider
import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class MatchingServiceTest {

    private lateinit var store: InMemoryMatchingStore
    private lateinit var userProvider: UserProvider
    private lateinit var matchingService: MatchingService

    @BeforeEach
    fun setUp() {
        store = InMemoryMatchingStore()
        userProvider = mock(UserProvider::class.java)
        matchingService = MatchingService(store, userProvider)
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
