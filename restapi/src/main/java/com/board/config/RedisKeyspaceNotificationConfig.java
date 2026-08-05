package com.board.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import lombok.RequiredArgsConstructor;

/**
 * Redis Keyspace Notification("notify-keyspace-events Ex") 을 애플리케이션 시작 시 프로그래밍 방식으로 활성화.
 * redis.conf 를 직접 수정하기 어려운 환경(Kubernetes ConfigMap 미적용 등)에서도 동작하도록 코드에서 강제 설정한다.
 * 이미 설정되어 있어도 재설정 시 부작용 없음(멱등).
 *
 * 이 설정이 있어야 hold:{seatId} 키가 TTL 로 만료될 때 SeatHoldExpirationListener 가 이벤트를 받을 수 있다.
 */
@Configuration
@RequiredArgsConstructor
public class RedisKeyspaceNotificationConfig implements InitializingBean {

	private final RedisConnectionFactory redisConnectionFactory;

	@Override
	public void afterPropertiesSet() {
		try (var connection = redisConnectionFactory.getConnection()) {
			connection.setConfig("notify-keyspace-events", "Ex");
		} catch (Exception e) {
			// Redis 가 관리형 서비스(예: 일부 클라우드 Redis)라 CONFIG SET 이 막혀 있는 경우 여기서 실패할 수 있음.
			// 그런 환경이라면 redis.conf 또는 클라우드 콘솔에서 notify-keyspace-events=Ex 를 직접 설정해야 함.
			throw new IllegalStateException(
					"Redis notify-keyspace-events 설정 실패. Redis 서버에서 직접 'notify-keyspace-events Ex' 를 설정해주세요.", e);
		}
	}
}
