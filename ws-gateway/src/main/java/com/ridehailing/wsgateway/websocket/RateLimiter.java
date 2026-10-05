package com.ridehailing.wsgateway.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimiter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxPerSecond;

    public RateLimiter() {
        this.maxPerSecond = 5;
    }

    public boolean allow(String userId) {
        Bucket bucket = buckets.computeIfAbsent(userId, k -> new Bucket(maxPerSecond));
        return bucket.tryConsume();
    }

    public void cleanup(String userId) {
        buckets.remove(userId);
    }

    private static class Bucket {
        private final int capacity;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        Bucket(int capacity) {
            this.capacity = capacity;
        }

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - windowStart;

            if (elapsed >= 1000) {
                synchronized (this) {
                    if (now - windowStart >= 1000) {
                        count.set(0);
                        windowStart = now;
                    }
                }
            }

            return count.incrementAndGet() <= capacity;
        }
    }
}
