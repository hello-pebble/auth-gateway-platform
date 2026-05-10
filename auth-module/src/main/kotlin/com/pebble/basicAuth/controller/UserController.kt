package com.pebble.basicAuth.controller

import com.pebble.basicAuth.config.WaitingRoomService
import com.pebble.basicAuth.config.JwtProvider
import com.pebble.basicAuth.config.CustomUserDetails
import com.pebble.basicAuth.domain.User
import com.pebble.basicAuth.domain.UserService
import com.pebble.basicAuth.persistence.RefreshTokenRepository
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
class UserController(
    private val userService: UserService,
    private val authenticationManager: AuthenticationManager,
    private val jwtProvider: JwtProvider,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val waitingRoomService: WaitingRoomService
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Value("\${jwt.refresh-expiration}")
    private var refreshExpiration: Long = 0

    @PostMapping("/api/v1/users/signup")
    @ResponseBody
    fun signUp(@Valid @RequestBody request: UserSignUpRequest): ResponseEntity<UserResponse> {
        val response = UserResponse.from(userService.signUp(request.username, request.email, request.password))
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @PostMapping("/api/v1/login")
    @ResponseBody
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<Any> {
        // 사용자 인증 처리 (SecurityContext 저장 생략 - Stateless 방식)
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(request.username, request.password)
        )

        val role = authentication.authorities.firstOrNull()?.authority ?: "ROLE_USER"
        val accessToken = jwtProvider.createAccessToken(request.username, role)
        val refreshToken = jwtProvider.createRefreshToken(request.username)

        // Refresh Token 저장 (In-memory)
        refreshTokenRepository.save(request.username, refreshToken, refreshExpiration)

        // Refresh Token을 HttpOnly 쿠키로 설정
        val refreshTokenCookie = ResponseCookie.from("refreshToken", refreshToken)
            .httpOnly(true)
            .secure(false) // HTTPS 적용 시 true 권장
            .path("/")
            .maxAge(7 * 24 * 60 * 60)
            .sameSite("Strict")
            .build()

        val customUserDetails = authentication.principal as CustomUserDetails
        val response = UserResponse.from(customUserDetails.user, accessToken)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.toString())
            .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            .body(response)
    }

    @PostMapping("/api/v1/refresh")
    @ResponseBody
    fun refresh(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<UserResponse> {
        val refreshToken = (request.cookies ?: emptyArray<Cookie>())
            .find { it.name == "refreshToken" }
            ?.value ?: throw RuntimeException("Refresh Token이 존재하지 않습니다.")

        if (!jwtProvider.validateToken(refreshToken)) {
            throw RuntimeException("유효하지 않은 Refresh Token입니다.")
        }

        val username = jwtProvider.getClaims(refreshToken).subject
        val savedToken = refreshTokenRepository.findByUsername(username)
            .orElseThrow { RuntimeException("저장된 Refresh Token이 없습니다.") }

        if (savedToken != refreshToken) {
            refreshTokenRepository.deleteByUsername(username)
            throw RuntimeException("Refresh Token이 일치하지 않습니다.")
        }

        refreshTokenRepository.deleteByUsername(username)

        val user = userService.findByUsername(username)
        val newAccessToken = jwtProvider.createAccessToken(username, user.role.name)
        val newRefreshToken = jwtProvider.createRefreshToken(username)

        refreshTokenRepository.save(username, newRefreshToken, refreshExpiration)

        val newRefreshTokenCookie = ResponseCookie.from("refreshToken", newRefreshToken)
            .httpOnly(true)
            .secure(false)
            .path("/")
            .maxAge(7 * 24 * 60 * 60)
            .sameSite("Strict")
            .build()

        val responseBody = UserResponse.from(user, newAccessToken)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, newRefreshTokenCookie.toString())
            .header(HttpHeaders.AUTHORIZATION, "Bearer $newAccessToken")
            .body(responseBody)
    }

    @GetMapping("/api/v1/users/me")
    @ResponseBody
    fun me(authentication: Authentication): ResponseEntity<UserResponse> {
        val user = userService.findByUsername(authentication.name)
        
        // 만약 헤더에 토큰이 없어서 세션으로 인증된 경우에도, 
        // 다른 서비스(task 등)에서 사용할 수 있도록 토큰을 생성해서 반환합니다.
        val token = if (authentication.credentials is String && (authentication.credentials as String).isNotEmpty()) {
            authentication.credentials as String
        } else {
            jwtProvider.createAccessToken(user.username, user.role.name)
        }
        
        val response = UserResponse.from(user, token)
        return ResponseEntity.ok(response)
    }

    @PatchMapping("/api/v1/users/password")
    @ResponseBody
    fun changePassword(
        authentication: Authentication,
        @Valid @RequestBody request: PasswordChangeRequest
    ): ResponseEntity<String> {
        userService.changePassword(authentication.name, request.oldPassword, request.newPassword)
        return ResponseEntity.ok("비밀번호가 성공적으로 변경되었습니다.")
    }

    data class UserSignUpRequest(
        @field:NotBlank(message = "사용자명은 필수입니다.")
        @field:Size(min = 4, max = 30, message = "사용자명은 4자 이상 30자 이하이어야 합니다.")
        val username: String,
        val email: String? = null,
        @field:NotBlank(message = "비밀번호는 필수입니다.")
        @field:Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다.")
        val password: String
    )

    data class LoginRequest(
        @field:NotBlank(message = "사용자명은 필수입니다.")
        val username: String = "",
        @field:NotBlank(message = "비밀번호는 필수입니다.")
        val password: String = ""
    )

    data class PasswordChangeRequest(
        @field:NotBlank(message = "기존 비밀번호는 필수입니다.")
        val oldPassword: String,
        @field:NotBlank(message = "새 비밀번호는 필수입니다.")
        @field:Size(min = 8, message = "새 비밀번호는 최소 8자 이상이어야 합니다.")
        val newPassword: String
    )
    
    data class UserResponse(
        val id: Long?, 
        val username: String, 
        val email: String?,
        val accessToken: String? = null
    ) {
        companion object {
            fun from(user: User, accessToken: String? = null): UserResponse = 
                UserResponse(user.id, user.username, user.email, accessToken)
        }
    }
}
