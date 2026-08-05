package com.board.config; // 프로젝트 환경에 맞게 패키지 경로를 확인해 주세요.

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

// @Configuration : 이 클래스가 스프링의 설정 클래스임을 선언
// 스프링 컨테이너가 시작될 때 이 클래스를 읽어 Bean을 등록함
@Configuration

// @EnableCaching : 스프링의 캐시 기능을 활성화
// 이 어노테이션이 있어야 @Cacheable, @CacheEvict 등의 캐시 어노테이션이 동작함
@EnableCaching

// CachingConfigurer : 캐시 관련 설정을 커스터마이징할 수 있는 인터페이스
// Spring Boot 3.x부터 CachingConfigurerSupport가 삭제되어
// CachingConfigurer 인터페이스를 직접 구현하는 방식으로 변경됨
public class RedisConfig implements CachingConfigurer {

    // CacheErrorHandler : Redis 캐시 작업(조회/저장/삭제) 중 발생하는 예외를 처리하는 인터페이스
    // 기본 설정은 예외 발생 시 애플리케이션 전체가 에러를 반환함
    // 아래와 같이 오버라이드하면 Redis가 꺼져 있어도 에러 없이 DB에서 직접 조회함
    @Override
    public CacheErrorHandler errorHandler() {

        // SimpleCacheErrorHandler : CacheErrorHandler의 기본 구현체
        // 각 메서드를 오버라이드하여 Redis 장애 시 동작 방식을 커스터마이징함
        return new SimpleCacheErrorHandler() {

            // handleCacheGetError : Redis에서 캐시 값을 조회할 때 예외가 발생하면 호출됨
            // 예) Redis 서버가 꺼져 있을 때 @Cacheable 메서드 호출 시
            // 예외를 무시하고 로그만 출력하면 스프링이 자동으로 DB에서 직접 조회함
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                System.out.println("====== Redis 조회 실패 - DB에서 직접 조회합니다: " + e.getMessage() + " ======");
            }

            // handleCachePutError : Redis에 캐시 값을 저장할 때 예외가 발생하면 호출됨
            // 예) DB 조회 후 결과를 Redis에 저장하려는데 Redis가 꺼져 있을 때
            // 예외를 무시하고 로그만 출력하면 DB 조회 결과를 그대로 반환함 (캐시 저장만 건너뜀)
            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                System.out.println("====== Redis 저장 실패 - 캐시 저장을 건너뜁니다: " + e.getMessage() + " ======");
            }

            // handleCacheEvictError : Redis에서 캐시 값을 삭제할 때 예외가 발생하면 호출됨
            // 예) @CacheEvict 메서드 실행 시 Redis가 꺼져 있을 때
            // 예외를 무시하고 로그만 출력하면 DB 작업(수정/삭제)은 정상적으로 완료됨
            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                System.out.println("====== Redis 삭제 실패 - 캐시 삭제를 건너뜁니다: " + e.getMessage() + " ======");
            }

            // handleCacheClearError : Redis의 캐시 전체를 비울 때 예외가 발생하면 호출됨
            // 예) @CacheEvict(allEntries = true) 실행 시 Redis가 꺼져 있을 때
            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                System.out.println("====== Redis 전체 삭제 실패 - 캐시 삭제를 건너뜁니다: " + e.getMessage() + " ======");
            }
        };
    }

    // @Bean : 스프링 컨테이너에 RedisCacheManager를 Bean으로 등록
    // RedisCacheManager : 스프링이 Redis를 캐시 저장소로 사용할 수 있도록 관리하는 핵심 클래스
    // RedisConnectionFactory : application.properties에 설정된 Redis 서버 접속 정보
    //    (host, port 등)를 기반으로 스프링이 자동 생성하여 주입해 주는 객체
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // ObjectMapper : Java 객체 ↔ JSON 변환을 담당하는 Jackson 라이브러리의 핵심 클래스
        // Redis에 데이터를 저장할 때 Java 객체를 JSON 문자열로 직렬화(Serialize)하고,
        // Redis에서 데이터를 읽을 때 JSON 문자열을 Java 객체로 역직렬화(Deserialize)함
        ObjectMapper objectMapper = new ObjectMapper();

        // JavaTimeModule : Java 8의 날짜/시간 타입(LocalDateTime, LocalDate 등)을
        // Jackson이 올바르게 직렬화/역직렬화할 수 있도록 지원하는 모듈
        // 이 모듈이 없으면 LocalDateTime을 JSON으로 변환할 때 오류가 발생함
        objectMapper.registerModule(new JavaTimeModule());

        // WRITE_DATES_AS_TIMESTAMPS 비활성화
        // 기본값이 활성화 상태이면 날짜를 숫자 배열([2024, 6, 8, 10, 30, 0])로 저장함
        // 비활성화하면 "2024-06-08T10:30:00" 형태의 ISO 문자열로 저장되어 가독성이 높아짐
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // FAIL_ON_EMPTY_BEANS 비활성화
        // 직렬화할 때 빈 객체(필드가 없는 객체)가 있어도 예외를 발생시키지 않고 {}로 처리함
        // JPA Entity나 프록시 객체 직렬화 시 발생할 수 있는 오류를 방지함
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        // FAIL_ON_UNKNOWN_PROPERTIES 비활성화
        // 역직렬화할 때 JSON에 Java 클래스에 없는 필드가 있어도 예외를 발생시키지 않고 무시함
        // Redis에 저장된 JSON과 현재 DTO 클래스의 필드가 다를 경우 오류를 방지함
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // BasicPolymorphicTypeValidator : 역직렬화 시 허용할 타입의 범위를 지정하는 보안 설정
        // allowIfSubType(Object.class) : Object의 모든 하위 클래스(즉, 모든 클래스)를 허용
        // 이 설정이 없으면 activateDefaultTyping 사용 시 보안 경고가 발생함
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();

        // activateDefaultTyping : 직렬화 시 JSON에 타입 정보(@class)를 자동으로 포함시키는 설정
        // 이 설정이 없으면 Redis에서 데이터를 읽을 때 타입 정보가 없어
        // BoardDTO 대신 LinkedHashMap으로 역직렬화되는 ClassCastException이 발생함
        //
        // 저장되는 JSON 예시:
        //    {"@class":"com.board.dto.BoardDTO","seqno":341,"title":"제목",...}
        //
        // DefaultTyping.NON_FINAL : final이 아닌 모든 클래스에 타입 정보를 포함
        // JsonTypeInfo.As.PROPERTY : 타입 정보를 JSON의 프로퍼티(@class)로 포함
        objectMapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        // Jackson2JsonRedisSerializer : Redis에 JSON 형태로 직렬화/역직렬화하는 클래스
        // Object.class를 지정하여 BoardDTO, MemberDTO 등 모든 객체를 하나의 설정으로 처리
        // activateDefaultTyping 설정 덕분에 @class 타입 정보가 JSON에 포함되어
        // 역직렬화 시 올바른 클래스(BoardDTO 등)로 정확하게 복원됨
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // RedisCacheConfiguration : Redis 캐시의 동작 방식을 설정하는 클래스
        RedisCacheConfiguration configuration = RedisCacheConfiguration

                // defaultCacheConfig() : 기본 캐시 설정을 불러옴
                .defaultCacheConfig()

                // entryTtl : 캐시의 만료 시간(TTL, Time To Live) 설정
                // Duration.ofMinutes(10) : 캐시 저장 후 10분이 지나면 자동으로 삭제됨
                .entryTtl(Duration.ofMinutes(10))

                // disableCachingNullValues : null 값은 캐시에 저장하지 않도록 설정
                // null이 캐시되면 DB 조회 결과가 없는 경우에도 null이 반환되어 문제가 생길 수 있음
                .disableCachingNullValues()

                // serializeValuesWith : 캐시 값(Value)을 직렬화하는 방식을 설정
                // 위에서 생성한 Jackson2JsonRedisSerializer를 사용하여 JSON으로 저장
                // ※ 키(Key)는 별도 설정이 없으면 기본적으로 문자열로 저장됨
                //    예) "board::341" (캐시 이름::키값)
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer)
                );

        // RedisCacheManager 생성 및 반환
        // cacheDefaults() : 위에서 설정한 RedisCacheConfiguration을 기본 설정으로 적용
        // 모든 @Cacheable 어노테이션에 이 설정이 공통으로 적용됨
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .build();
    }

    /* ==========================================================================
       ★ 추가된 코드: 챗봇 세션 저장을 위한 RedisTemplate 빈(Bean) 설정
       ========================================================================== */
    /**
     * ChatbotSessionManager에서 주입받아 사용할 RedisTemplate 빈입니다.
     * 원 버전의 캐시 매니저(cacheManager)와 완벽한 통일성을 유지하기 위해
     * 동일하게 커스텀 구성된 ObjectMapper 및 역직렬화 방식을 매핑하여 연동했습니다.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 상단의 cacheManager와 동일한 규칙의 ObjectMapper 생성 및 모듈 주입
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        
        // 원 버전의 중요 옵션 정책 유지
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // 다형성 타입 검증 보안 및 @class 자동 메타데이터 태그 추가 로직 반영
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();

        objectMapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        // 모든 객체를 유연하게 JSON 직렬화할 수 있도록 매퍼 결합형 시리얼라이저 정의
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        // 키(Key) 직렬화 방식: 가독성 높은 String 타입 직렬화 도구 주입
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // 값(Value) 직렬화 방식: ChatbotSession 등 복합 도메인 구조를 JSON 형태로 바인딩
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}