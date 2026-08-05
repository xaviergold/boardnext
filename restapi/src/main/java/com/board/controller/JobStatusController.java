package com.board.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.board.async.JobStatusManager;

import lombok.RequiredArgsConstructor;

/**
 * 클라이언트 폴링용 Job 상태 조회 API
 *
 * 게시글 작성/삭제, 회원가입처럼 Kafka 비동기로 처리되는 작업의 진행 상태를
 * 클라이언트가 주기적으로 확인하기 위한 단일 엔드포인트
 *
 * 응답 예시:
 * - 처리중: {"status":"PROCESSING"}
 * - 완료:   {"status":"DONE", "resultId":"101"}  ← 게시글 작성 완료 시 seqno 포함
 * - 실패:   {"status":"FAILED", "message":"처리에 실패했습니다."}
 * - 만료:   404 (TTL 5분 경과 후 Redis 키 소멸)
 */
@RestController
@RequiredArgsConstructor
public class JobStatusController {

	private final JobStatusManager jobStatusManager;

	@GetMapping("/api/job/status/{jobId}")
	public ResponseEntity<Map<String, String>> getJobStatus(
			@PathVariable(name = "jobId") String jobId) {

		return jobStatusManager.getStatus(jobId)
				.map(ResponseEntity::ok)
				.orElse(ResponseEntity.notFound().build());
	}
}