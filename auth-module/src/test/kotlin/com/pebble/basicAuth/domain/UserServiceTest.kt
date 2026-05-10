package com.pebble.basicAuth.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.given
import org.mockito.kotlin.verify
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.*

@ExtendWith(MockitoExtension::class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @Mock
    private lateinit var userRepository: UserRepository

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    @InjectMocks
    private lateinit var userService: UserService

    @Test
    @DisplayName("회원가입 성공")
    fun signUp_NewUser_ReturnsSavedUser() {
        // given
        val username = "testuser"
        val password = "password123"
        val encodedPassword = "encodedPassword123"
        val user = User(username = username, password = encodedPassword)

        given(userRepository.existsByUsernameAndDeletedAtIsNull(username)).willReturn(false)
        given(passwordEncoder.encode(password)).willReturn(encodedPassword)
        given(userRepository.save(any())).willReturn(user)

        // when
        val savedUser = userService.signUp(username, null, password)

        // then
        assertThat(savedUser).isNotNull
        assertThat(savedUser.username).isEqualTo(username)
        assertThat(savedUser.password).isEqualTo(encodedPassword)
        verify(userRepository).save(any())
    }

    @Test
    @DisplayName("회원가입 실패_중복 사용자")
    fun signUp_DuplicateUsername_ThrowsUserException() {
        // given
        val username = "existingUser"
        val password = "password123"

        given(userRepository.existsByUsernameAndDeletedAtIsNull(username)).willReturn(true)

        // when & then
        assertThatThrownBy { userService.signUp(username, null, password) }
            .isInstanceOf(UserException::class.java)
            .hasMessage("이미 존재하는 사용자명입니다.")
    }

    @Test
    @DisplayName("사용자조회 성공")
    fun findByUsername_ExistingUser_ReturnsUser() {
        // given
        val username = "testuser"
        val user = User(username = username, password = "password")
        given(userRepository.findByUsernameAndDeletedAtIsNull(username)).willReturn(Optional.of(user))

        // when
        val foundUser = userService.findByUsername(username)

        // then
        assertThat(foundUser).isNotNull
        assertThat(foundUser.username).isEqualTo(username)
    }

    @Test
    @DisplayName("사용자조회 실패_존재하지 않는 사용자")
    fun findByUsername_NonExistingUser_ThrowsUserException() {
        // given
        val username = "nonExistent"
        given(userRepository.findByUsernameAndDeletedAtIsNull(username)).willReturn(Optional.empty())

        // when & then
        assertThatThrownBy { userService.findByUsername(username) }
            .isInstanceOf(UserException::class.java)
            .hasMessageContaining("사용자를 찾을 수 없습니다")
    }

    @Test
    @DisplayName("비밀번호 변경 성공")
    fun changePassword_Success() {
        // given
        val username = "testuser"
        val oldPassword = "oldPassword"
        val newPassword = "newPassword"
        val encodedOldPassword = "encodedOldPassword"
        val encodedNewPassword = "encodedNewPassword"
        val user = User(username = username, password = encodedOldPassword)

        given(userRepository.findByUsernameAndDeletedAtIsNull(username)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(oldPassword, encodedOldPassword)).willReturn(true)
        given(passwordEncoder.encode(newPassword)).willReturn(encodedNewPassword)

        // when
        userService.changePassword(username, oldPassword, newPassword)

        // then
        verify(userRepository).save(any())
    }

    @Test
    @DisplayName("비밀번호 변경 실패_기존 비밀번호 불일치")
    fun changePassword_WrongOldPassword_ThrowsUserException() {
        // given
        val username = "testuser"
        val oldPassword = "wrongPassword"
        val newPassword = "newPassword"
        val encodedOldPassword = "encodedOldPassword"
        val user = User(username = username, password = encodedOldPassword)

        given(userRepository.findByUsernameAndDeletedAtIsNull(username)).willReturn(Optional.of(user))
        given(passwordEncoder.matches(oldPassword, encodedOldPassword)).willReturn(false)

        // when & then
        assertThatThrownBy { userService.changePassword(username, oldPassword, newPassword) }
            .isInstanceOf(UserException::class.java)
            .hasMessage("기존 비밀번호가 일치하지 않습니다.")
    }
}
