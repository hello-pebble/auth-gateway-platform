package com.pebble.basicAuth.domain

import java.time.LocalDateTime

data class User(
    val id: Long? = null,
    val username: String,
    val email: String? = null,
    val password: String? = null,
    val provider: String? = null,
    val providerId: String? = null,
    val role: UserRole = UserRole.ROLE_USER,
    var deletedAt: LocalDateTime? = null
) {
    fun delete() {
        this.deletedAt = LocalDateTime.now()
    }

    fun isDeleted(): Boolean = this.deletedAt != null

    companion object {
        @JvmStatic
        fun createSocialUser(username: String, email: String, provider: String, providerId: String): User {
            return User(
                username = username,
                email = email,
                provider = provider,
                providerId = providerId
            )
        }
    }
}
