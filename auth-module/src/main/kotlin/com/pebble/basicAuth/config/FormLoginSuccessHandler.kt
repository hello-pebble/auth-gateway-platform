package com.pebble.basicAuth.config

import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component

@Component
class FormLoginSuccessHandler(
    private val jwtProvider: JwtProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    @Value("\${auth.cookie.secure:true}") private val secureCookie: Boolean,
    @Value("\${auth.gateway-url}") private val gatewayUrl: String,
    @Value("\${auth.redirect-path}") private val redirectPath: String
) : SimpleUrlAuthenticationSuccessHandler("/") {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val username = authentication.name
        val role = authentication.authorities.firstOrNull()?.authority ?: "ROLE_USER"

        val accessToken  = jwtProvider.createAccessToken(username, role)
        val refreshToken = jwtProvider.createRefreshToken(username)

        refreshTokenRepository.save(username, refreshToken, jwtProvider.refreshExpiration)

        addCookie(response, "accessToken",  accessToken,  (jwtProvider.accessExpiration  / 1000).toInt())
        addCookie(response, "refreshToken", refreshToken, (jwtProvider.refreshExpiration / 1000).toInt())

        redirectStrategy.sendRedirect(request, response, "$gatewayUrl$redirectPath")
    }



    private fun addCookie(response: HttpServletResponse, name: String, value: String, maxAge: Int) {
        val cookie = Cookie(name, value).apply {
            path     = "/"
            isHttpOnly = true
            secure   = secureCookie
            this.maxAge = maxAge
        }
        response.addCookie(cookie)
    }
}
