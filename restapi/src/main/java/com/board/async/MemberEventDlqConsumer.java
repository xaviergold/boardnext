package com.board.async;

import java.io.File;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.board.async.MemberKafkaProducer;
import com.board.dto.event.MemberEventDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * member-events.DLT (Dead Letter Topic) 전용 Consumer
 *
 * 회원가입(SIGNUP) 이벤트가 재시도 3회 모두 실패하면 여기로 들어온다.
 * 프로필 이미지는 컨트롤러 단에서 동기로 디스크에 이미 저장된 상태인데,
 * 회원가입 자체가 실패하면 이 파일을 참조할 MemberEntity 레코드가 영원히 생기지 않으므로
 * (게시글 파일처럼 별도 테이블/seqno로 추적되는 구조가 아니라 나중에 찾아낼 방법이 없음)
 * DLT 수신 즉시 디스크에서 직접 삭제한다.
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class MemberEventDlqConsumer {

	private final JobStatusManager jobStatusManager;

	@KafkaListener(
			topics = MemberKafkaProducer.MEMBER_TOPIC + ".DLT",
			groupId = "boardnext-member-events-dlt-group",
			containerFactory = "memberDlqListenerContainerFactory"
	)
	public void consumeDlt(MemberEventDTO event,
			@Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage) {

		log.error("[DLT] member-events 최종 실패 - type: {}, jobId: {}, email: {}, 사유: {}",
				event.getEventType(), event.getJobId(), event.getEmail(), exceptionMessage);

		try {
			jobStatusManager.markFailed(event.getJobId(), "회원가입 처리에 실패했습니다. 잠시 후 다시 시도해주세요.");

			if (MemberEventDTO.SIGNUP.equals(event.getEventType())) {
				cleanupOrphanProfileImage(event);
			}

		} catch (Exception e) {
			log.error("[DLT] 후처리(프로필 이미지 정리) 중 예외 발생 - jobId: {}, email: {}", event.getJobId(), event.getEmail(), e);
		}
	}

	/** 회원가입 최종 실패 시, 이미 동기로 저장된 고아 프로필 이미지를 즉시 삭제 */
	private void cleanupOrphanProfileImage(MemberEventDTO event) {
		String storedFilename = event.getStored_filename();
		if (storedFilename == null || storedFilename.isBlank()) {
			return; // 프로필 이미지 없이 가입한 경우
		}

		String os = System.getProperty("os.name").toLowerCase();
		String path = os.contains("win") ? "c:\\Repository\\profile\\" : "/var/opt/Repository/profile/";

		File diskFile = new File(path + storedFilename);
		if (diskFile.exists()) {
			boolean deleted = diskFile.delete();
			log.info("[DLT] 고아 프로필 이미지 정리 {} - file: {}, email: {}",
					deleted ? "완료" : "실패", storedFilename, event.getEmail());
		}
	}
}