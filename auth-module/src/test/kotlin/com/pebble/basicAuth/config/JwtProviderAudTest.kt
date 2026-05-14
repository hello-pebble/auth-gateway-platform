package com.pebble.basicAuth.config

import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

class JwtProviderAudTest {

    private lateinit var jwtProvider: JwtProvider
    private lateinit var publicKey: RSAPublicKey

    @BeforeEach
    fun setUp() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        publicKey = keyPair.public as RSAPublicKey
        val privKey = keyPair.private as RSAPrivateKey

        jwtProvider = JwtProvider(mock(), "http://localhost:8080")
        jwtProvider.accessExpiration = 900000L
        jwtProvider.refreshExpiration = 86400000L

        val field = JwtProvider::class.java.getDeclaredField("privateKey")
        field.isAccessible = true
        field.set(jwtProvider, privKey)
    }

    @Test
    fun `액세스 토큰에 aud 클레임이 포함된다`() {
        val token = jwtProvider.createAccessToken("user1", "ROLE_USER")
        val claims = Jwts.parser()
            .verifyWith(publicKey)
            .build()
            .parseSignedClaims(token)
            .payload

        assertThat(claims.audience).containsExactlyInAnyOrder("task-service", "matching-service")
    }
}
