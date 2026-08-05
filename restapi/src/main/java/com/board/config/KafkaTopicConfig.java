package com.board.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import lombok.extern.log4j.Log4j2;

/**
 * 애플리케이션 시작 시 필요한 Kafka 토픽을 자동 생성
 * 이미 존재하는 토픽은 무시하고 넘어감 (멱등성 보장)
 *
 * 레플리카 수는 실제 브로커 수를 조회해서 자동 결정
 *   - 브로커 1개 (강의실 단일모드) → replicas=1
 *   - 브로커 3개 (집 다중모드)    → replicas=3
 * 프로파일(dev/local/prod) 구분 없이 어느 환경에서나 동작
 */
@Configuration
@Log4j2
public class KafkaTopicConfig {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    private int resolveReplicas() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            int brokerCount = adminClient.describeCluster()
                    .nodes().get().size();
            int replicas = Math.min(brokerCount, 3); // 최대 3개로 제한
            log.info("[KafkaTopicConfig] 브로커 수: {}, 레플리카 수: {}", brokerCount, replicas);
            return replicas;
        } catch (Exception e) {
            log.warn("[KafkaTopicConfig] 브로커 수 조회 실패, 기본값 1 사용: {}", e.getMessage());
            return 1;
        }
    }

    // ── 게시글 이벤트 ─────────────────────────────────────────
    @Bean
    public NewTopic boardEventsTopic() {
        return TopicBuilder.name("board-events")
                .partitions(3)
                .replicas(resolveReplicas())
                .build();
    }

    @Bean
    public NewTopic boardEventsDltTopic() {
        return TopicBuilder.name("board-events.DLT")
                .partitions(3)
                .replicas(resolveReplicas())
                .build();
    }

    // ── 회원 이벤트 ──────────────────────────────────────────
    @Bean
    public NewTopic memberEventsTopic() {
        return TopicBuilder.name("member-events")
                .partitions(3)
                .replicas(resolveReplicas())
                .build();
    }

    @Bean
    public NewTopic memberEventsDltTopic() {
        return TopicBuilder.name("member-events.DLT")
                .partitions(3)
                .replicas(resolveReplicas())
                .build();
    }
}