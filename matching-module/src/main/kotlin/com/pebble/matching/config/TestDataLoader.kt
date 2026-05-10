package com.pebble.matching.config

import com.pebble.matching.domain.ChatMatch
import com.pebble.matching.domain.MatchRanking
import com.pebble.matching.domain.MatchingProfile
import com.pebble.matching.infrastructure.InMemoryMatchingStore
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.LocalDateTime

@Configuration
@Profile("!test")
class TestDataLoader(
    private val store: InMemoryMatchingStore
) {

    @Bean
    fun initMatchingData() = CommandLineRunner {
        // auth-module 생성 순서: admin(1), user(2), alice(3), bob(4),
        // charlie(5), diana(6), evan(7), fiona(8), grace(9), henry(10), irene(11), james(12)
        val allUserIds = (1L..12L).toList()

        // 1. 전원 매칭 노출 활성화
        allUserIds.forEach { id ->
            store.saveProfile(MatchingProfile(userId = id, isExposed = true))
        }

        // 2. 매칭 성사 데이터 (task-module의 matchingId와 일치)
        val matches = listOf(
            Triple("match_001", 3L,  4L),   // alice  <-> bob
            Triple("match_002", 5L,  6L),   // charlie <-> diana
            Triple("match_003", 7L,  8L),   // evan   <-> fiona
            Triple("match_004", 9L,  10L),  // grace  <-> henry
            Triple("match_005", 11L, 12L),  // irene  <-> james
            Triple("match_006", 2L,  3L),   // user   <-> alice
        )
        matches.forEach { (id, a, b) ->
            store.saveMatch(ChatMatch(
                id = id,
                userA = a,
                userB = b,
                createdAt = LocalDateTime.now().minusDays((1..10).random().toLong())
            ))
        }

        // 3. 랭킹 데이터 (서로 상대방을 1위로)
        val rankings = listOf(
            Triple(3L,  4L,  1),  // alice  → bob    1위
            Triple(4L,  3L,  1),  // bob    → alice  1위
            Triple(5L,  6L,  1),  // charlie→ diana  1위
            Triple(6L,  5L,  1),  // diana  → charlie 1위
            Triple(7L,  8L,  1),  // evan   → fiona  1위
            Triple(8L,  7L,  1),  // fiona  → evan   1위
            Triple(9L,  10L, 1),  // grace  → henry  1위
            Triple(10L, 9L,  1),  // henry  → grace  1위
            Triple(2L,  3L,  1),  // user   → alice  1위
            Triple(2L,  5L,  2),  // user   → charlie 2위
            Triple(2L,  7L,  3),  // user   → evan   3위
        )
        rankings.forEach { (from, to, rank) ->
            store.saveRanking(MatchRanking(fromUserId = from, toUserId = to, rank = rank))
        }

        println(">>> Matching Module Test Data Initialized (12 profiles, 6 matches, 11 rankings)")
    }
}
