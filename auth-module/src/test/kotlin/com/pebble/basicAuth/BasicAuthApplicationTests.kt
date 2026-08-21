package com.pebble.basicAuth

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringBootTest
class BasicAuthApplicationTests {

    @MockitoBean
    private lateinit var clientRegistrationRepository: ClientRegistrationRepository

    @Test
    fun contextLoads() {
    }

}
