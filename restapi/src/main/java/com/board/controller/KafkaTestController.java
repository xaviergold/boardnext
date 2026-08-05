package com.board.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kafka 클러스터 연결 확인용 테스트 컨트롤러
 * String 직렬화/역직렬화만 사용 - JsonDeserializer 타입 매핑 이슈 회피
 * 검증 끝나면 삭제할 것
 */
@Slf4j
@RestController
@RequestMapping("/apitest/kafka")
@RequiredArgsConstructor
public class KafkaTestController {

    private static final String TEST_TOPIC = "test-topic-plain";

    // 테스트 전용 String ProducerFactory 기반 KafkaTemplate
    // (앱 전역 KafkaTemplate이 JsonSerializer로 설정되어 있어 충돌 방지를 위해 별도 생성)
    private final ProducerFactory<String, String> producerFactory;

    /**
     * 메시지 발행 테스트
     * GET /apitest/kafka/send?message=hello
     */
    @GetMapping("/send")
    public Map<String, Object> send(@RequestParam(name = "message", defaultValue = "hello kafka") String message) {
        KafkaTemplate<String, String> stringTemplate = new KafkaTemplate<>(producerFactory);
        String key = "test-key-" + System.currentTimeMillis();
        String payload = message + " | sentAt=" + LocalDateTime.now();

        log.info("[Kafka Test] Sending message - key: {}, payload: {}", key, payload);
        CompletableFuture<?> future = stringTemplate.send(TEST_TOPIC, key, payload);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("[Kafka Test] Send SUCCESS - key: {}", key);
            } else {
                log.error("[Kafka Test] Send FAILED - key: {}", key, ex);
            }
        });

        return Map.of(
                "status", "sent",
                "topic", TEST_TOPIC,
                "key", key,
                "payload", payload
        );
    }

    /**
     * 리스너 동작 여부는 애플리케이션 로그에서 "[Kafka Test] Received" 로그로 확인
     * properties로 이 리스너에 한해서만 StringDeserializer 강제 적용
     * (전역 application.yaml의 JsonDeserializer 설정과 무관하게 동작)
     */
    @KafkaListener(
            topics = TEST_TOPIC,
            groupId = "boardnext-test-group",
            properties = {
                    "value.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                    "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer"
            }
    )
    public void listen(String message) {
        log.info("[Kafka Test] Received message: {}", message);
    }
}