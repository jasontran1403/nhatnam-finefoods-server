package com.nhatnam.server.config;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class TransactionLockManager {
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(Long userId) {
        return locks.computeIfAbsent(userId, k -> new ReentrantLock(true));
    }

    public void cleanupIfUnused(Long userId) {
        ReentrantLock lock = locks.get(userId);
        if (lock != null && !lock.isLocked()) {
            locks.remove(userId);
        }
    }
}