package com.pebble.basicAuth.config

import com.pebble.basicAuth.config.oauth2.CustomOAuth2UserService
import com.pebble.basicAuth.config.oauth2.OAuth2SuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.util.matcher.AntPathRequestMatcher

@Configuration
@EnableMethodSecurity
class SecurityConfig(
    private val authenticationHandler: CustomAuthenticationHandler,
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val oAuth2SuccessHandler: OAuth2SuccessHandler,
    private val formLoginSuccessHandler: FormLoginSuccessHandler,
    private val formLoginFailureHandler: FormLoginFailureHandler
) {

    @Bean
    @Throws(Exception::class)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // 테스트 편의를 위해 CSRF 비활성화
            
            // 1. 세션 정책: 인증 서버는 로그인 과정을 위해 세션이 필요함 (IF_REQUIRED)
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            }

            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/", "/index.html", "/static/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                    .requestMatchers("/login", "/signup", "/login.html", "/signup.html", "/api/v1/users/signup", "/api/v1/login", "/api/v1/refresh").permitAll()
                    .requestMatchers("/api/tasks/access-info").permitAll()
                    .requestMatchers("/h2-console/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            }

            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

            // 2. 커스텀 로그인 폼 활성화
            .formLogin { form ->
                form.loginPage("/login.html")
                    .loginProcessingUrl("/login")
                    .successHandler(formLoginSuccessHandler)
                    .failureHandler(formLoginFailureHandler)
                    .permitAll()
            }

            .oauth2Login { oauth2 ->
                oauth2.loginPage("/login.html")
                    .userInfoEndpoint { userInfo -> userInfo.userService(customOAuth2UserService) }
                    .successHandler(oAuth2SuccessHandler)
            }

            // 3. 예외 처리: API 요청에 대해서만 커스텀 핸들러(JSON) 적용
            .exceptionHandling { exception ->
                exception.defaultAuthenticationEntryPointFor(
                    authenticationHandler,
                    AntPathRequestMatcher("/api/**")
                )
            }
            
            .logout { logout ->
                logout.logoutUrl("/api/v1/logout")
                    .deleteCookies("accessToken", "refreshToken")
                    .logoutSuccessHandler(authenticationHandler)
            }
            .headers { headers -> headers.frameOptions { it.disable() } }

        return http.build()
    }

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration): AuthenticationManager {
        return authenticationConfiguration.authenticationManager
    }
}
