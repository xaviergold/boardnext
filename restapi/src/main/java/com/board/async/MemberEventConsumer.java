package com.board.async;

import java.time.LocalDateTime;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.board.async.MemberKafkaProducer;
import com.board.dto.event.MemberEventDTO;
import com.board.entity.MemberEntity;
import com.board.entity.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * member-events 토픽 Consumer
 * 현재는 SIGNUP만 처리
 *
 * 에러 정책(재시도 3회 + DLT 이동)은 KafkaConsumerConfig에서 전역으로 설정
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class MemberEventConsumer {

	private final MemberRepository memberRepository;
	private final JobStatusManager jobStatusManager;

	@KafkaListener(
			topics = MemberKafkaProducer.MEMBER_TOPIC,
			groupId = "boardnext-member-events-group",
			containerFactory = "memberEventListenerContainerFactory"
	)
	public void consume(MemberEventDTO event) {
		log.info("[MemberEventConsumer] 이벤트 수신 - type: {}, jobId: {}, email: {}",
				event.getEventType(), event.getJobId(), event.getEmail());

		try {
			if (MemberEventDTO.SIGNUP.equals(event.getEventType())) {
				handleSignup(event);
			} else {
				log.warn("[MemberEventConsumer] 알 수 없는 eventType: {}", event.getEventType());
			}
		} catch (Exception e) {
			log.error("[MemberEventConsumer] 처리 실패 - jobId: {}, email: {}", event.getJobId(), event.getEmail(), e);
			throw new RuntimeException("member-event 처리 실패: " + event.getEventType(), e);
		}
	}

	private void handleSignup(MemberEventDTO event) {
		// 동시성 가드: Consumer 단에서도 한 번 더 중복 가입 체크
		// (Producer 단에서 Redis 락으로 1차 방어하지만, 이중 안전장치)
		if (memberRepository.existsById(event.getEmail())) {
			log.warn("[MemberEventConsumer] 이미 존재하는 회원 - email: {} (중복 이벤트로 추정, 스킵)", event.getEmail());
			jobStatusManager.markDone(event.getJobId());
			return;
		}

		MemberEntity memberEntity = MemberEntity.builder()
				.email(event.getEmail())
				.username(event.getUsername())
				.password(event.getPassword()) // 이미 암호화된 상태
				.gender(event.getGender())
				.hobby(event.getHobby())
				.job(event.getJob())
				.description(event.getDescription())
				.zipcode(event.getZipcode())
				.address(event.getAddress())
				.telno(event.getTelno())
				.nickname(event.getNickname())
				.role("USER")
				.org_filename(event.getOrg_filename())
				.stored_filename(event.getStored_filename())
				.filesize(event.getFilesize())
				.regdate(LocalDateTime.now())
				.lastpwdate(LocalDateTime.now())
				.lastpwcheckdate(LocalDateTime.now())
				.FromSocial(event.getFromSocial())
				.secretary(event.getSecretary())
				.build();

		memberRepository.save(memberEntity);

		log.info("[MemberEventConsumer] 회원가입 처리 완료 - email: {}", event.getEmail());
		jobStatusManager.markDone(event.getJobId());
	}
}