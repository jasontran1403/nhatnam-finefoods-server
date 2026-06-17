package com.nhatnam.server.service;

import com.nhatnam.server.entity.User;

import java.util.Optional;

public interface UserService {
    void saveUser(User user);
    Optional<User> findById(Long id);
    Optional<User> findByUsername(String username);
}
