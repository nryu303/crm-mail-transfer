package com.crm.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sliding-window rate limiter for the public reply page
 * ({@code POST /reply/{token}/send}). Two layers:
 *
 *   1. Per (token, IP) — the historic check. Stops a single client from spamming
 *      the same token (accidental double-submits, replay, basic flooding).
 *   2. Per IP global — caps the total number of submissions any one IP can do per
 *      window across ALL tokens. Prevents an attacker who has harvested multiple
 *      tokens (e.g. by intercepting a broadcast) from rotating through them to
 *      stay under the per-token limit.
 *
 * Single-instance map; replace with Redis if/when we scale out.
 */
@Service
public class ReplyRateLimitService {

    /** Per (token, IP) cap — typical user double-tap protection. */
    public static final int MAX_SUBMITS = 5;
    /** Per IP across all tokens — abuse cap. */
    public static final int IP_GLOBAL_LIMIT_PER_MIN = 60;
    public static final long WINDOW_MS = 60_000L; // 1 minute

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * @deprecated retained for backward compatibility; prefer {@link #tryAcquire(String, String)}.
     */
    @Deprecated
    public boolean tryAcquire(String token) {
        return tryAcquire(token, null);
    }

    /** Returns true if this submission is permitted. {@code ip} may be {@code null}. */
    public boolean tryAcquire(String token, String ip) {
        if (token == null || token.isEmpty()) return false;
        if (ip == null || ip.isEmpty()) ip = "unknown";

        // Per-IP global cap first — a "noisy" IP shouldn't even be checked against the per-token bucket.
        if (!checkLimit("ip:" + ip, IP_GLOBAL_LIMIT_PER_MIN)) return false;
        if (!checkLimit("tok:" + token + ":" + ip, MAX_SUBMITS)) return false;
        return true;
    }

    private boolean checkLimit(String key, int limit) {
        long now = System.currentTimeMillis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(now));
        synchronized (b) {
            if (now - b.windowStart > WINDOW_MS) {
                b.windowStart = now;
                b.count.set(0);
            }
            if (b.count.get() >= limit) return false;
            b.count.incrementAndGet();
            return true;
        }
    }

    private static final class Bucket {
        long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        Bucket(long now) { this.windowStart = now; }
    }
}
