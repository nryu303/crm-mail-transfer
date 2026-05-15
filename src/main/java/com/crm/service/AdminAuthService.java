package com.crm.service;

import com.crm.entity.AdminUser;
import com.crm.repository.AdminUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AdminAuthService {

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthService(AdminUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<AdminUser> authenticate(String loginId, String password) {
        if (loginId == null || password == null || loginId.trim().isEmpty() || password.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByLoginId(loginId.trim())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> passwordEncoder.matches(password, u.getLoginPassword()));
    }

    /** Verify that {@code password} matches the BCrypt hash stored for the given admin. */
    public boolean verifyPassword(Long adminId, String password) {
        if (adminId == null || password == null || password.isEmpty()) return false;
        return repository.findById(adminId)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .map(u -> passwordEncoder.matches(password, u.getLoginPassword()))
                .orElse(false);
    }

    /** Change the logged-in admin's password. Verifies old password and requires a usable new one. */
    @org.springframework.transaction.annotation.Transactional
    public void changePassword(Long adminId, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new PasswordChangeException("新しいパスワードは8文字以上にしてください");
        }
        if (newPassword.length() > 100) {
            throw new PasswordChangeException("新しいパスワードが長すぎます");
        }
        AdminUser admin = repository.findById(adminId)
                .orElseThrow(() -> new PasswordChangeException("管理者アカウントが見つかりません"));
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, admin.getLoginPassword())) {
            throw new PasswordChangeException("現在のパスワードが正しくありません");
        }
        if (passwordEncoder.matches(newPassword, admin.getLoginPassword())) {
            throw new PasswordChangeException("新しいパスワードは現在のパスワードと異なるものにしてください");
        }
        admin.setLoginPassword(passwordEncoder.encode(newPassword));
        repository.save(admin);
    }

    public static class PasswordChangeException extends RuntimeException {
        public PasswordChangeException(String msg) { super(msg); }
    }
}
