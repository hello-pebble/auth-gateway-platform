package com.pebble.matching.infrastructure

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@Component
class UserPairLockManager {
    private val locks = ConcurrentHashMap<Pair<Long, Long>, ReentrantLock>()

    /**
     * 두 사용자 ID를 기반으로 유일한 락을 획득합니다.
     * 데드락 방지를 위해 항상 ID가 작은 쪽을 앞에 둡니다.
     */
    fun lock(userA: Long, userB: Long, timeoutSeconds: Long = 3): Boolean {
        val pair = if (userA < userB) Pair(userA, userB) else Pair(userB, userA)
        val lock = locks.computeIfAbsent(pair) { ReentrantLock() }
        
        return lock.tryLock(timeoutSeconds, TimeUnit.SECONDS)
    }

    fun unlock(userA: Long, userB: Long) {
        val pair = if (userA < userB) Pair(userA, userB) else Pair(userB, userA)
        locks[pair]?.let {
            if (it.isHeldByCurrentThread) {
                it.unlock()
            }
        }
    }
}
