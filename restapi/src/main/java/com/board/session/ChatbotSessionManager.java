package com.board.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotSessionManager {

    // Spring Boot가 application.properties 설정을 기반으로 자동 생성해주는 빈 주입
    private final RedisTemplate<String, Object> redisTemplate;

    // Redis에 저장될 Key의 접두사(Prefix) 설정
    private static final String REDIS_KEY_PREFIX = "chatbot:session:";
    
    // 세션 만료 시간 설정: 12시간 (properties의 spring.session.timeout 규격 반영)
    private static final long SESSION_TIMEOUT_HOURS = 12;

    /**
     * 새 세션 생성 및 Redis 등록
     */
    public ChatbotSession createSession() {
        String sessionId = UUID.randomUUID().toString();
        ChatbotSession session = new ChatbotSession(sessionId);
        
        saveToRedis(session);
        return session;
    }

    /**
     * 기존 세션 조회. 없으면 새로 생성 후 Redis 등록.
     */
    public ChatbotSession getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession();
        }

        String key = REDIS_KEY_PREFIX + sessionId;
        ChatbotSession session = (ChatbotSession) redisTemplate.opsForValue().get(key);

        if (session == null) {
            log.info("Redis에 세션이 존재하지 않아 새로 생성합니다. sessionId: {}", sessionId);
            session = new ChatbotSession(sessionId);
            saveToRedis(session);
        } else {
            // 조회할 때마다 세션 수명 유지를 위해 TTL을 재연장(Touch) 처리합니다.
            redisTemplate.expire(key, SESSION_TIMEOUT_HOURS, TimeUnit.HOURS);
        }

        return session;
    }

    /**
     * 변경된 세션 상태를 Redis에 명시적으로 반영할 때 호출하는 메서드
     */
    public void saveToRedis(ChatbotSession session) {
        if (session == null || session.getSessionId() == null) return;
        
        String key = REDIS_KEY_PREFIX + session.getSessionId();
        
        // 데이터 저장과 동시에 12시간 TTL 만료 시간 부여
        redisTemplate.opsForValue().set(key, session, SESSION_TIMEOUT_HOURS, TimeUnit.HOURS);
        log.debug("ChatbotSession이 Redis에 저장되었습니다. key: {}", key);
    }

    /**
     * Redis에서 세션 삭제 (대화 완전 초기화 및 세션 파기 시)
     */
    public void removeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        
        String key = REDIS_KEY_PREFIX + sessionId;
        Boolean deleted = redisTemplate.delete(key);
        log.info("Redis에서 세션을 삭제했습니다. key: {}, 결과: {}", key, deleted);
    }

    /**
     * 현재 활성화된 세션 개수 파악
     * (In-Memory와 달리 Redis 전체 Key 조회는 성능 이슈가 생길 수 있어 접두사 패턴 검색을 수행합니다)
     */
    public int getActiveSessionCount() {
        try {
            var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("Redis 활성 세션 카운트 조회 중 오류 발생", e);
            return 0;
        }
    }
    
    /**
     * Redis에서 세션 조회. 없으면 null 반환 (새로 생성하지 않음)
     */
    public ChatbotSession getFromRedis(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String key = REDIS_KEY_PREFIX + sessionId;
        return (ChatbotSession) redisTemplate.opsForValue().get(key);
    }
}