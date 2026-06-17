package com.nhatnam.server.config;

import com.nhatnam.server.entity.User;
import com.nhatnam.server.enumtype.Role;
import com.nhatnam.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;

/**
 * Seed 8 user ban đầu khi DB mới.
 * Password đã được hash sẵn bằng BCrypt — KHÔNG hash lại.
 * Secret MFA được generate tự động bằng SecretGenerator.
 */
@Log4j2
@Component
@Order(1)
@RequiredArgsConstructor
public class UserDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }

        userRepository.save(User.builder()
                .email("nguyenhai@gmail.com")
                .fullName("Trần Nguyên Hải")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$8ey6vBMRoXd6kt4BkNtQZ.vMOi99.KQaAK4M7/cCFa/j8Udb2C6rG")
                .phoneNumber("+84938121001")
                .role(Role.SELLER)
                .secret(secretGenerator.generate())
                .timeCreate(1769017484747L)
                .username("nguyenhai")
                .build());

        userRepository.save(User.builder()
                .email("daothixuanan@gmail.com")
                .fullName("Đào Thị Xuân An")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$sf4ZZC3AhkYHlWEFLiYiP.FDSFZ0bfbEN7JJH.bU5ETAY1whzU7m.")
                .phoneNumber("+84934513968")
                .role(Role.SELLER)
                .secret(secretGenerator.generate())
                .timeCreate(1771772100019L)
                .username("xuanan")
                .build());

        userRepository.save(User.builder()
                .email("longphan@gmail.com")
                .fullName("Phan Nguyễn Hoàng Long")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$O1Vebu0hbNCUn6t4xit8zuAsQseI41XvhzVPI3pBLcq3t607M1hKu")
                .phoneNumber("0938989101")
                .role(Role.ADMIN)
                .secret(secretGenerator.generate())
                .timeCreate(1771840031951L)
                .username("longphan")
                .build());

        userRepository.save(User.builder()
                .email("hauyen@gmail.com")
                .fullName("Trần Ngọc Hạ Uyên")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("+84938125031")
                .role(Role.ACCOUNTANT)
                .secret(secretGenerator.generate())
                .timeCreate(1772105069143L)
                .username("hauyen")
                .build());

        userRepository.save(User.builder()
                .email("thaison@gmail.com")
                .fullName("Nguyễn Thái Sơn")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("+84938125031")
                .role(Role.WAREHOUSE)
                .secret(secretGenerator.generate())
                .timeCreate(1772105069143L)
                .username("thaison")
                .build());

        userRepository.save(User.builder()
                .email("operator@nhatnam.vn")
                .fullName("Nhân Viên Nhập Liệu")
                .isLockAccount(false)
                .mfaEnabled(false)
                .password("$2a$10$bXqEUs39qTrFck24oL/cku2cAl0pQVldgIwXdCYdkddI/hOGnptVq")
                .phoneNumber("+84900000099")
                .role(Role.OPERATOR)
                .secret(secretGenerator.generate())
                .timeCreate(System.currentTimeMillis())
                .username("operator")
                .build());
    }
}