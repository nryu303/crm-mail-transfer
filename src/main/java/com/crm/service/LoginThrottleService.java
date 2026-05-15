package com.crm.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter for login attempts, keyed by client IP.
 * Default: max 5 failed attempts within 60 seconds.
 *
 * Stored in-memory (single-JVM). When horizontal scaling is needed, swap for Redis.
 * Successful logins reset the counter for that IP.
 */
@Service
public class LoginThrottleService {

    private static final int WINDOW_SECONDS = 60;
    private static final int MAX_FAILURES_PER_WINDOW = 5;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public synchronized boolean isBlocked(String ip) {
        Deque<Instant> q = failures.get(ip);
        if (q == null) return false;
        prune(q);
        return q.size() >= MAX_FAILURES_PER_WINDOW;
    }

    public synchronized void recordFailure(String ip) {
        if (ip == null) return;
        Deque<Instant> q = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        prune(q);
        q.addLast(Instant.now());
    }

    public synchronized void recordSuccess(String ip) {
        if (ip == null) return;
        failures.remove(ip);
    }

    private static void prune(Deque<Instant> q) {
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS);
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }
}
