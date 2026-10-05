package com.ridehailing.dispatchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // Lua script: compare and delete lock only if value matches
    // KEYS[1] = lock key
    // ARGV[1] = expected value (tripId)
    // Returns: 1 if deleted, 0 if not found or value mismatch
    private static final String COMPARE_AND_DELETE_SCRIPT = """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
    """;

    // Lua script: compare and extend lock TTL only if value matches
    // KEYS[1] = lock key
    // ARGV[1] = expected value (tripId)
    // ARGV[2] = TTL in milliseconds
    // Returns: 1 if extended, 0 if not found or value mismatch
    private static final String COMPARE_AND_EXTEND_SCRIPT = """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("pexpire", KEYS[1], ARGV[2])
        else
            return 0
        end
    """;

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public DefaultRedisScript<Long> compareAndDeleteScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(COMPARE_AND_DELETE_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public DefaultRedisScript<Long> compareAndExtendScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(COMPARE_AND_EXTEND_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }
}
