package com.board.async;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Kafka 비동기 처리(게시글 작성/삭제, 회원가입)의 진행 상태를 Redis에 기록/조회하는 공용 컴포넌트
 *
 * 키 네이밍: job:{jobId}
 * - status   : PROCESSING / DONE / FAILED
 * - resultId : (CREATE 한정) 생성된 게시글의 seqno - 클라이언트가 DONE 확인 후 바로 상세 페이지로 이동할 때 사용
 * - message  : FAILED일 때 실패 사유
 *
 * TTL은 짧게(5분) 설정 - 영구 보관할 필요 없는 임시 상태값이기 때문
 */
@Component
@RequiredArgsConstructor
public class JobStatusManager {

	private final StringRedisTemplate redisTemplate;

	private static final long JOB_TTL_MINUTES = 5;
	private static final String KEY_PREFIX = "job:";

	private String key(String jobId) {
		return KEY_PREFIX + jobId;
	}

	/** Producer가 이벤트 발행 직후 호출 - PROCESSING 상태로 초기화 */
	public void markProcessing(String jobId) {
		Map<String, String> data = new HashMap<>();
		data.put("status", JobStatus.PROCESSING.name());
		redisTemplate.opsForHash().putAll(key(jobId), data);
		redisTemplate.expire(key(jobId), JOB_TTL_MINUTES, TimeUnit.MINUTES);
	}

	/** Consumer가 정상 처리 완료 후 호출 */
	public void markDone(String jobId) {
		markDone(jobId, null);
	}

	/** Consumer가 정상 처리 완료 후 호출 (생성된 리소스의 id를 같이 알려줄 때, 예: 게시글 seqno) */
	public void markDone(String jobId, Long resultId) {
		Map<String, String> data = new HashMap<>();
		data.put("status", JobStatus.DONE.name());
		if (resultId != null) {
			data.put("resultId", String.valueOf(resultId));
		}
		redisTemplate.opsForHash().putAll(key(jobId), data);
		redisTemplate.expire(key(jobId), JOB_TTL_MINUTES, TimeUnit.MINUTES);
	}

	/** Consumer가 재시도를 모두 소진하고 DLT로 넘어갈 때 호출 */
	public void markFailed(String jobId, String message) {
		Map<String, String> data = new HashMap<>();
		data.put("status", JobStatus.FAILED.name());
		data.put("message", message == null ? "처리 중 오류가 발생했습니다." : message);
		redisTemplate.opsForHash().putAll(key(jobId), data);
		redisTemplate.expire(key(jobId), JOB_TTL_MINUTES, TimeUnit.MINUTES);
	}

	/** 클라이언트 폴링용 - 현재 Job 상태 조회 */
	public Optional<Map<String, String>> getStatus(String jobId) {
		Map<Object, Object> raw = redisTemplate.opsForHash().entries(key(jobId));
		if (raw == null || raw.isEmpty()) {
			return Optional.empty();
		}
		Map<String, String> result = new HashMap<>();
		raw.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
		return Optional.of(result);
	}
}