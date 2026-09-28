package com.tripplanner.auth.service;

import com.tripplanner.auth.dto.request.RegisterRequest;
import com.tripplanner.auth.dto.request.UpdatePasswordRequest;
import com.tripplanner.auth.dto.request.UpdateProfileRequest;
import com.tripplanner.auth.dto.response.UserProfileResponse;
import com.tripplanner.auth.entity.User;
import com.tripplanner.auth.exception.AuthException;
import com.tripplanner.auth.repository.UserRepository;
import com.tripplanner.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 用户核心业务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserPreferenceService userPreferenceService;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在: " + email);
        }
        return buildUserPrincipal(user);
    }

    /**
     * 用户注册
     */
    public User register(RegisterRequest request) {
        if (!request.isPasswordMatch()) {
            throw AuthException.passwordMismatch();
        }

        if (userRepository.findByEmail(request.getEmail()) != null) {
            throw AuthException.emailAlreadyExists();
        }

        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setName(request.getName());
        user.setStatus("active");
        user.setPreferences("{}");

        userRepository.insert(user);

        // 初始化默认偏好
        userPreferenceService.initDefaultPreference(user.getId());

        log.info("用户注册成功: {}", user.getEmail());
        return user;
    }

    /**
     * 用户登录验证
     */
    public User authenticate(String email, String password) {
        User user = userRepository.findByEmail(email);
        if (user == null) {
            throw AuthException.invalidCredentials();
        }

        if (!"active".equals(user.getStatus())) {
            throw AuthException.accountDisabled();
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw AuthException.invalidCredentials();
        }

        // 更新最后登录时间
        userRepository.updateLastLoginAt(user.getId(), LocalDateTime.now());

        return user;
    }

    /**
     * 根据 ID 查找用户
     */
    public User findById(String userId) {
        User user = userRepository.selectById(userId);
        if (user == null || "deleted".equals(user.getStatus())) {
            throw AuthException.accountNotFound();
        }
        return user;
    }

    /**
     * 获取用户档案
     */
    public UserProfileResponse getProfile(String userId) {
        User user = findById(userId);
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * 更新个人信息
     */
    public UserProfileResponse updateProfile(String userId, com.tripplanner.auth.dto.request.UpdateProfileRequest request) {
        User user = findById(userId);
        if (request.getName() != null) {
            user.setName(request.getName());
        }
        if (request.getAvatarUrl() != null) {
            user.setAvatarUrl(request.getAvatarUrl());
        }
        userRepository.updateById(user);
        return UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * 修改密码
     */
    public void updatePassword(String userId, com.tripplanner.auth.dto.request.UpdatePasswordRequest request) {
        User user = findById(userId);
        if (!request.isPasswordMatch()) {
            throw AuthException.passwordMismatch();
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw AuthException.currentPasswordIncorrect();
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.updateById(user);
    }

    private UserPrincipal buildUserPrincipal(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPasswordHash(),
                user.getName(),
                user.getStatus(),
                List.of("USER")
        );
    }
}