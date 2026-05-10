package com.pebble.basicAuth.config

import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.interfaces.RSAPrivateKey
import java.util.*

@Component
class JwtProvider(
    private val jwkSource: JWKSource<SecurityContext>,
    @Value("\${auth.issuer-uri:http://localhost:8080}") private val issuerUri: String
) {

    @Value("\${jwt.access-expiration}")
    val accessExpiration: Long = 0

    @Value("\${jwt.refresh-expiration}")
    val refreshExpiration: Long = 0

    private lateinit var privateKey: RSAPrivateKey

    @PostConstruct
    protected fun init() {
        // JWKSource에서 RSA 개인키를 추출하여 Jwts 서명에 사용
        val jwkSelector = JWKSelector(JWKMatcher.Builder().build())
        val jwks = jwkSource.get(jwkSelector, null)
        val rsaKey = jwks[0].toRSAKey()
        this.privateKey = rsaKey.toRSAPrivateKey()
    }

    fun createAccessToken(username: String, role: String): String {
        return createToken(username, mapOf("roles" to role), accessExpiration)
    }

    fun createRefreshToken(username: String): String {
        return createToken(username, emptyMap(), refreshExpiration)
    }

    private fun createToken(username: String, claims: Map<String, Any>, expiration: Long): String {
        val now = Date()
        val expiryDate = Date(now.time + expiration)

        return Jwts.builder()
            .issuer(issuerUri)
            .subject(username)
            .claims(claims)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(privateKey, Jwts.SIG.RS256) // RSA256 알고리즘 사용
            .compact()
    }

    fun getClaims(token: String): Claims {
        // 검증은 이제 Gateway나 Resource Server가 담당하지만, 
        // 내부 API(/me, /refresh 등)에서 여전히 필요하므로 구현 유지
        return Jwts.parser()
            .keyLocator { header -> if (header is io.jsonwebtoken.JwsHeader) privateKey else null } // 단순화를 위해 개인키로도 검증(공개키 검증이 정석)
            .build()
            .parseSignedClaims(token)
            .payload
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser()
                .keyLocator { _ -> privateKey }
                .build()
                .parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}
