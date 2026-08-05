package com.board.config;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import com.board.dto.event.BoardEventDTO;
import com.board.dto.event.MemberEventDTO;

import lombok.extern.log4j.Log4j2;

@Configuration
@EnableKafka
@Log4j2
public class KafkaConsumerConfig {

    // ── 공통: DLT 라우터 ─────────────────────────────────────────
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaOperations,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1)
        );
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxElapsedTime(7000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn("[Kafka Retry] topic: {}, key: {}, 시도: {}, 사유: {}",
                        record.topic(), record.key(), attempt, ex.getMessage())
        );
        return errorHandler;
    }

    // ── board-events 전용 ConsumerFactory ───────────────────────
    // BoardEventDTO 타입을 명시적으로 고정 - 토픽명 기반 추론 없이 항상 이 타입으로 역직렬화
    @Bean
    public ConsumerFactory<String, BoardEventDTO> boardEventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BoardEventDTO.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.board.dto.event");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BoardEventDTO> boardEventListenerContainerFactory(
            ConsumerFactory<String, BoardEventDTO> boardEventConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, BoardEventDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(boardEventConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // ── member-events 전용 ConsumerFactory ──────────────────────
    @Bean
    public ConsumerFactory<String, MemberEventDTO> memberEventConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS,
                org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, MemberEventDTO.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.board.dto.event");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MemberEventDTO> memberEventListenerContainerFactory(
            ConsumerFactory<String, MemberEventDTO> memberEventConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, MemberEventDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(memberEventConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // ── DLT 전용 ContainerFactory (재시도 없음) ──────────────────
    // DLT는 board/member 이벤트 모두 동일한 처리(로깅+Job상태변경)이므로 공용으로 사용
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BoardEventDTO> boardDlqListenerContainerFactory(
            ConsumerFactory<String, BoardEventDTO> boardEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, BoardEventDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(boardEventConsumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, ex) -> log.error("[DLT 처리 실패] topic: {}, key: {}, 사유: {}",
                        record.topic(), record.key(), ex.getMessage()),
                new FixedBackOff(0L, 0L)
        ));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MemberEventDTO> memberDlqListenerContainerFactory(
            ConsumerFactory<String, MemberEventDTO> memberEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, MemberEventDTO> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(memberEventConsumerFactory);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, ex) -> log.error("[DLT 처리 실패] topic: {}, key: {}, 사유: {}",
                        record.topic(), record.key(), ex.getMessage()),
                new FixedBackOff(0L, 0L)
        ));
        return factory;
    }
}