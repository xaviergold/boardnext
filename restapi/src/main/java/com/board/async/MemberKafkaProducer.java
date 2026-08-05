package com.board.async;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.board.dto.event.MemberEventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * 회원가입 이벤트를 Kafka로 발행하는 컴포넌트
 * 게시글 관련 이벤트는 BoardKafkaProducer에서 담당
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class MemberKafkaProducer {

	public static final String MEMBER_TOPIC = "member-events";

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final JobStatusManager jobStatusManager;

	/**
	 * 회원가입 이벤트 발행
	 * password는 호출 전에 이미 BCrypt로 암호화된 상태여야 함
	 * @return 클라이언트 폴링용 jobId
	 */
	public String publishMemberSignup(MemberEventDTO memberEvent) {
		String jobId = UUID.randomUUID().toString().replaceAll("-", "");

		memberEvent.setEventType(MemberEventDTO.SIGNUP);
		memberEvent.setJobId(jobId);
		memberEvent.setEventTime(LocalDateTime.now());

		jobStatusManager.markProcessing(jobId);

		// 파티션 키는 email - 같은 사용자에 대한 이벤트 순서 보장
		kafkaTemplate.send(MEMBER_TOPIC, memberEvent.getEmail(), memberEvent)
				.whenComplete((result, ex) -> {
					if (ex != null) {
						log.error("[Kafka] 회원가입 이벤트 발행 실패 - jobId: {}, email: {}", jobId, memberEvent.getEmail(), ex);
						jobStatusManager.markFailed(jobId, "이벤트 발행에 실패했습니다.");
					} else {
						log.info("[Kafka] 회원가입 이벤트 발행 성공 - jobId: {}, email: {}", jobId, memberEvent.getEmail());
					}
				});

		return jobId;
	}
}