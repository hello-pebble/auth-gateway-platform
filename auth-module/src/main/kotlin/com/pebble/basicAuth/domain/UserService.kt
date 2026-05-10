package com.pebble.basicAuth.domain

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    @Transactional
    fun signUp(username: String, email: String?, password: String): User {
        if (userRepository.existsByUsernameAndDeletedAtIsNull(username)) {
            throw UserException("이미 존재하는 사용자명입니다.")
        }
        
        email?.let {
            if (userRepository.existsByEmailAndDeletedAtIsNull(it)) {
                throw UserException("이미 사용 중인 이메일입니다.")
            }
        }

        val encodedPassword = passwordEncoder.encode(password)

        return userRepository.save(User(
            username = username,
            email = email,
            password = encodedPassword,
            role = UserRole.ROLE_USER
        ))
    }

    @Transactional(readOnly = true)
    fun findByUsername(username: String): User {
        return userRepository.findByUsernameAndDeletedAtIsNull(username)
            .orElseThrow { UserException("사용자를 찾을 수 없습니다: $username") }
    }

    @Transactional
    fun changePassword(username: String, oldPassword: String, newPassword: String) {
        val user = findByUsername(username)
        
        if (user.password == null || !passwordEncoder.matches(oldPassword, user.password)) {
            throw UserException("기존 비밀번호가 일치하지 않습니다.")
        }

        val encodedPassword = passwordEncoder.encode(newPassword)
        userRepository.save(user.copy(password = encodedPassword))
    }

    @Transactional
    fun saveOrUpdateSocialUser(provider: String, providerId: String, email: String?, name: String?): User {
        return userRepository.findByProviderAndProviderIdAndDeletedAtIsNull(provider, providerId)
            .orElseGet {
                val baseUsername = email?.split("@")?.get(0) ?: "${provider}_$providerId"
                val username = if (userRepository.existsByUsernameAndDeletedAtIsNull(baseUsername)) {
                    "${baseUsername}_${UUID.randomUUID().toString().take(5)}"
                } else {
                    baseUsername
                }

                userRepository.save(User.createSocialUser(
                    username = username,
                    email = email ?: "",
                    provider = provider,
                    providerId = providerId
                ))
            }
    }
}
