package com.board.service.agent;

import com.board.dto.agent.GoogleTokenDto;
import com.board.entity.SecretaryGoogleToken;
import com.board.entity.repository.GoogleTokenRepository;
import com.board.service.agent.SlackNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleTokenService {

    private static final String TOKEN_KEY_PREFIX = "secretary:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(2);

    private final RedisTemplate<String, Object> redisTemplate;
    private final GoogleTokenRepository tokenRepository;
    private final SlackNotifier slack;

    @Value("${spring.security.oauth2.client.registration.google-secretary.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google-secretary.client-secret}")
    private String clientSecret;

    // 토큰 저장 (Redis + Oracle)
    public void saveToken(String email, GoogleTokenDto dto) {
        // Redis 저장
        redisTemplate.opsForValue().set(
            TOKEN_KEY_PREFIX + email, dto, TOKEN_TTL);

        // Oracle JPA upsert
        SecretaryGoogleToken entity = tokenRepository
            .findByEmail(email)
            .orElse(SecretaryGoogleToken.builder()
                .email(email)
                .build());

        entity.setAccessToken(dto.getAccessToken());
        entity.setRefreshToken(dto.getRefreshToken());
        entity.setExpiresAt(dto.getExpiresAt());
        entity.setScope(dto.getScope());

        tokenRepository.save(entity);
    }

    // 유효한 Access Token 반환
    public String getValidAccessToken(String email) {
        GoogleTokenDto token = getToken(email);

        if (token == null) {
            throw new SecretaryAuthException("Google 토큰 없음 - 재연결 필요: " + email);
        }

        // 만료 5분 전이면 자동 Refresh
        long minutesLeft = ChronoUnit.MINUTES.between(
            LocalDateTime.now(), token.getExpiresAt());

        if (minutesLeft <= 5) {
            slack.tokenExpiringSoon(email, minutesLeft);
            token = refreshToken(email, token.getRefreshToken());
        }

        return token.getAccessToken();
    }

    // 토큰 유효 여부 확인
    public boolean hasValidToken(String email) {
        try {
            return getToken(email) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // Redis 우선 조회, 없으면 Oracle에서 복구
    private GoogleTokenDto getToken(String email) {
        String key = TOKEN_KEY_PREFIX + email;
        GoogleTokenDto token = (GoogleTokenDto) redisTemplate.opsForValue().get(key);

        if (token == null) {
            log.info("Redis 토큰 미존재 - Oracle에서 복구: {}", email);
            token = tokenRepository.findByEmail(email)
                .map(e -> GoogleTokenDto.builder()
                    .email(e.getEmail())
                    .accessToken(e.getAccessToken())
                    .refreshToken(e.getRefreshToken())
                    .expiresAt(e.getExpiresAt())
                    .scope(e.getScope())
                    .build())
                .orElse(null);

            if (token != null) {
                redisTemplate.opsForValue().set(key, token, TOKEN_TTL);
            }
        }
        return token;
    }

    // Access Token 갱신
    private GoogleTokenDto refreshToken(String email, String refreshToken) {
        // Refresh Token이 없으면 재연결 필요
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new SecretaryAuthException(
                "Refresh Token 없음 - Google 재연결 필요: " + email);
        }
        try {
            RestTemplate rt = new RestTemplate();
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type",    "refresh_token");
            params.add("refresh_token", refreshToken);
            params.add("client_id",     clientId);
            params.add("client_secret", clientSecret);

            Map<String, Object> response = rt.postForObject(
            		"https://oauth2.googleapis.com/token",params, Map.class
            );

            GoogleTokenDto newToken = GoogleTokenDto.builder()
                .email(email)
                .accessToken((String) response.get("access_token"))
                .refreshToken(refreshToken)
                .expiresAt(LocalDateTime.now().plusSeconds(
                    ((Number) response.get("expires_in")).longValue()))
                .build();

            saveToken(email, newToken);
            slack.tokenRefreshed(email, true);
            return newToken;

        } catch (Exception e) {
            slack.tokenRefreshed(email, false);
            throw new SecretaryAuthException("Token Refresh 실패: " + e.getMessage());
        }
    }

    // 토큰 삭제 (secretary=N 체크 해제 시)
    public void revokeToken(String email) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + email);
        tokenRepository.deleteByEmail(email);
        log.info("Google 토큰 삭제 완료: {}", email);
    }
}