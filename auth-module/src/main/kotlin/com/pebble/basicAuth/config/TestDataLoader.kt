package com.pebble.basicAuth.config

import com.pebble.basicAuth.domain.UserRole
import com.pebble.basicAuth.domain.UserService
import com.pebble.basicAuth.domain.UserRepository
import com.pebble.basicAuth.domain.User
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder

@Configuration
@Profile("!test") // 테스트 환경이 아닐 때만 실행 (local, dev 등)
class TestDataLoader(
    private val userService: UserService,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    @Bean
    fun initData() = CommandLineRunner {
        // 관리자
        if (!userRepository.existsByUsernameAndDeletedAtIsNull("admin")) {
            userRepository.save(User(
                id = null, username = "admin", email = "admin@example.com",
                password = passwordEncoder.encode("password123"),
                provider = null, providerId = null,
                role = UserRole.ROLE_ADMIN, deletedAt = null
            ))
        }

        // 일반 회원 10명
        val members = listOf(
            Triple("user",    "user@example.com",    "password123"),
            Triple("alice",   "alice@example.com",   "password123"),
            Triple("bob",     "bob@example.com",     "password123"),
            Triple("charlie", "charlie@example.com", "password123"),
            Triple("diana",   "diana@example.com",   "password123"),
            Triple("evan",    "evan@example.com",    "password123"),
            Triple("fiona",   "fiona@example.com",   "password123"),
            Triple("grace",   "grace@example.com",   "password123"),
            Triple("henry",   "henry@example.com",   "password123"),
            Triple("irene",   "irene@example.com",   "password123"),
            Triple("james",   "james@example.com",   "password123"),
        )
        members.forEach { (username, email, pw) ->
            if (!userRepository.existsByUsernameAndDeletedAtIsNull(username)) {
                userService.signUp(username, email, pw)
            }
        }

        println(">>> Auth Module Test Data Initialized (admin + 10 members)")
    }
}
