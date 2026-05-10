package com.pebble.basicAuth.config

import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class CustomAuthenticationHandler(
    private val refreshTokenRepository: RefreshTokenRepository
) : AuthenticationEntryPoint, LogoutSuccessHandler {

    @Throws(IOException::class)
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException
    ) {
        val accept = request.getHeader("Accept")
        
        // 브라우저의 HTML 요청인 경우 로그인 페이지로 리다이렉트
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect("/login.html")
        } else {
            // API 요청인 경우 기존처럼 JSON 응답
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"message\":\"인증이 필요합니다.\"}")
        }
    }

    @Throws(IOException::class)
    override fun onLogoutSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication?
    ) {
        if (authentication != null && authentication.name != null) {
            refreshTokenRepository.deleteByUsername(authentication.name)
        }

        response.status = HttpServletResponse.SC_OK
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("{\"message\":\"로그아웃 되었습니다.\"}")
    }
}
