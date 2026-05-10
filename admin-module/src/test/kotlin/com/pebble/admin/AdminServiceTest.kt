package com.pebble.admin

import com.pebble.admin.client.MatchingInternalClient
import com.pebble.admin.dto.AdminMatchDto
import com.pebble.admin.dto.AdminRankingDto
import com.pebble.admin.dto.AdminUserDto
import com.pebble.admin.service.AdminService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.given
import org.mockito.kotlin.mock

class AdminServiceTest {
    private lateinit var matchingClient: MatchingInternalClient
    private lateinit var adminService: AdminService

    private val token = "test-token"
    private val now = "2026-05-10T10:00:00"

    @BeforeEach
    fun setUp() {
        matchingClient = mock()
        adminService = AdminService(matchingClient)
    }

    @Test
    fun `getSummaryStats returns correct counts`() {
        given(matchingClient.getAllUsers(token)).willReturn(listOf(
            AdminUserDto(1L, true, false, now),
            AdminUserDto(2L, false, false, now),
            AdminUserDto(3L, true, true, now)
        ))
        given(matchingClient.getAllMatches(token)).willReturn(listOf(
            AdminMatchDto("m1", 1L, 2L, now)
        ))

        val stats = adminService.getSummaryStats(token)

        assertEquals(3, stats.totalUsers)
        assertEquals(1, stats.activeUsers)
        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.blockCount)
    }

    @Test
    fun `getMatchRate calculates correctly`() {
        given(matchingClient.getAllRankings(token)).willReturn(listOf(
            AdminRankingDto(1L, 2L, 1, now),
            AdminRankingDto(2L, 1L, 1, now),
            AdminRankingDto(3L, 4L, 2, now)
        ))
        given(matchingClient.getAllMatches(token)).willReturn(listOf(
            AdminMatchDto("m1", 1L, 2L, now)
        ))

        val result = adminService.getMatchRate(token)

        assertEquals(3, result.totalRankings)
        assertEquals(1, result.totalMatches)
        assertEquals(2.0 / 3.0, result.matchRate, 0.001)
    }

    @Test
    fun `getTopRanked returns users sorted by received count`() {
        given(matchingClient.getAllRankings(token)).willReturn(listOf(
            AdminRankingDto(1L, 2L, 1, now),
            AdminRankingDto(3L, 2L, 2, now),
            AdminRankingDto(1L, 3L, 1, now)
        ))

        val result = adminService.getTopRanked(token, 2)

        assertEquals(2, result.size)
        assertEquals(2L, result[0].userId)
        assertEquals(2, result[0].receivedCount)
    }

    @Test
    fun `getAllUsers paginates correctly`() {
        given(matchingClient.getAllUsers(token)).willReturn(listOf(
            AdminUserDto(1L, true, false, now),
            AdminUserDto(2L, true, false, now),
            AdminUserDto(3L, true, false, now)
        ))

        val result = adminService.getAllUsers(token, page = 1, size = 2)

        assertEquals(1, result.content.size)
        assertEquals(3L, result.content[0].userId)
        assertEquals(3, result.totalElements)
    }

    @Test
    fun `getMatchRate returns zero when no rankings`() {
        given(matchingClient.getAllRankings(token)).willReturn(emptyList())
        given(matchingClient.getAllMatches(token)).willReturn(emptyList())

        val result = adminService.getMatchRate(token)

        assertEquals(0.0, result.matchRate)
    }
}
