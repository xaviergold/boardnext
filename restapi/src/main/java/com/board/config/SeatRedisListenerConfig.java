package com.board.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.board.listener.SeatHoldExpirationListener;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class SeatRedisListenerConfig {

	private final RedisConnectionFactory redisConnectionFactory;
	private final SeatHoldExpirationListener seatHoldExpirationListener;

	@Bean
	public RedisMessageListenerContainer seatKeyExpirationListenerContainer() {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		// 기본 Redis DB(0번) 기준. application.yml 에서 spring.redis.database 값을 바꿨다면 숫자도 맞춰야 함.
		container.addMessageListener(seatHoldExpirationListener, new PatternTopic("__keyevent@0__:expired"));
		return container;
	}
}
