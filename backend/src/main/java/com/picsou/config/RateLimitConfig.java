package com.picsou.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitConfig {

    private final Map<String, Bucket> loginBucketsMap = new ConcurrentHashMap<>();
    private final Map<String, Bucket> syncBucketsMap = new ConcurrentHashMap<>();
    private final Map<String, Bucket> trAuthBucketsMap = new ConcurrentHashMap<>();

    /**
     * Per-IP login rate limiter: 5 attempts per 15 minutes.
     * Uses a ConcurrentHashMap of Bucket4j buckets keyed by IP address.
     */
    @Bean("loginBuckets")
    public Map<String, Bucket> loginBuckets() {
        return loginBucketsMap;
    }

    /**
     * Per-IP sync rate limiter: 10 requests per minute.
     */
    @Bean("syncBuckets")
    public Map<String, Bucket> syncBuckets() {
        return syncBucketsMap;
    }

    /**
     * Per-IP TR auth rate limiter: 3 attempts per 10 minutes.
     * Strict because each attempt sends an SMS.
     */
    @Bean("trAuthBuckets")
    public Map<String, Bucket> trAuthBuckets() {
        return trAuthBucketsMap;
    }

    /** Evict all rate-limit buckets every hour to prevent unbounded memory growth. */
    @Scheduled(fixedRate = 3600000)
    public void evictStaleBuckets() {
        loginBucketsMap.clear();
        syncBucketsMap.clear();
        trAuthBucketsMap.clear();
    }

    public static Bucket createLoginBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(5)
                .refillIntervally(5, Duration.ofMinutes(15))
                .build())
            .build();
    }

    public static Bucket createSyncBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(10)
                .refillIntervally(10, Duration.ofMinutes(1))
                .build())
            .build();
    }

    public static Bucket createTrAuthBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(3)
                .refillIntervally(3, Duration.ofMinutes(10))
                .build())
            .build();
    }
}
