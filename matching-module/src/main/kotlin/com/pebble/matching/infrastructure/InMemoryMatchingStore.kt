package com.pebble.matching.infrastructure

import com.pebble.matching.domain.ChatMatch
import com.pebble.matching.domain.MatchRanking
import com.pebble.matching.domain.MatchingProfile
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryMatchingStore {
    private val profiles = ConcurrentHashMap<Long, MatchingProfile>()
    private val rankings = ConcurrentHashMap<Pair<Long, Long>, MatchRanking>()
    private val matches = ConcurrentHashMap<String, ChatMatch>()

    fun saveProfile(profile: MatchingProfile) { profiles[profile.userId] = profile }
    fun getProfile(userId: Long): MatchingProfile? = profiles[userId]

    fun getAllExposedUsers(): List<Long> =
        profiles.filter { it.value.isExposed && !it.value.isBlocked }.keys.toList()

    fun getAllProfiles(): List<MatchingProfile> = profiles.values.toList()

    fun blockUser(userId: Long, isBlocked: Boolean) {
        val existing = profiles.getOrDefault(userId, MatchingProfile(userId))
        profiles[userId] = existing.copy(isBlocked = isBlocked, updatedAt = LocalDateTime.now())
    }

    fun isBlocked(userId: Long): Boolean = profiles[userId]?.isBlocked ?: false

    fun saveRanking(ranking: MatchRanking) {
        rankings[Pair(ranking.fromUserId, ranking.toUserId)] = ranking
    }

    fun getRanking(fromUserId: Long, toUserId: Long): MatchRanking? =
        rankings[Pair(fromUserId, toUserId)]

    fun getAllRankings(): List<MatchRanking> = rankings.values.toList()

    fun saveMatch(match: ChatMatch) { matches[match.id] = match }

    fun getMatchesForUser(userId: Long): List<ChatMatch> =
        matches.values.filter { it.userA == userId || it.userB == userId }

    fun getAllMatches(): List<ChatMatch> = matches.values.toList()

    fun deleteMatch(matchId: String) { matches.remove(matchId) }

    fun alreadyMatched(userA: Long, userB: Long): Boolean =
        matches.values.any {
            (it.userA == userA && it.userB == userB) || (it.userA == userB && it.userB == userA)
        }
}
