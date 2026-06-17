package com.nhatnam.server.service.serviceimpl;

import com.nhatnam.server.config.JwtService;
import com.nhatnam.server.entity.User;
import com.nhatnam.server.repository.UserRepository;
import com.nhatnam.server.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;

	@Override
	public Optional<User> findById(Long id) {
		return userRepository.findById(id);
	}

	@Override
	public Optional<User> findByUsername(String username) {
		return userRepository.findByUsername(username);
	}

	@Override
	public void saveUser(User user) {
		userRepository.save(user);
	}
}


