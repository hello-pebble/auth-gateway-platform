package com.pebble.basicAuth.controller

import com.pebble.basicAuth.config.JwtProvider
import com.pebble.basicAuth.domain.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserControllerMeTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val userService: UserService,
    private val jwtProvider: JwtProvider
) {

    @MockitoBean
    private lateinit var clientRegistrationRepository: ClientRegistrationRepository

    @MockitoBean
    private lateinit var redisTemplate: StringRedisTemplate

    @MockitoBean
    private lateinit var redissonClient: RedissonClient

    private val username = "testuser"
    private val password = "password123!"
    private lateinit var accessToken: String

    @BeforeEach
    fun setUp() {
        // 1. Create test user
        userService.signUp(username, null, password)

        // 2. Issue JWT token
        accessToken = jwtProvider.createAccessToken(username, "ROLE_USER")
    }

    @Test
    @DisplayName("Get current user info (/api/v1/users/me) Success Test")
    fun `getCurrentUserInfo Success`() {
        // 3. API Call and Verification
        mockMvc.get("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.username") { value(username) }
            jsonPath("$.id") { exists() }
        }
    }

    @Test
    @DisplayName("Unauthorized error when fetching info without token")
    fun `getCurrentUserInfo Unauthorized`() {
        mockMvc.get("/api/v1/users/me")
            .andExpect {
                status { isUnauthorized() }
            }
    }
}
