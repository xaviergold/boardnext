package com.board.service.oauth2;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.board.dto.agent.GoogleTokenDto;
import com.board.entity.MemberEntity;
import com.board.entity.repository.MemberRepository;
import com.board.service.agent.GoogleTokenService;
import com.board.util.JWTUtil;
import com.board.util.GeoLocationUtils;
import com.board.util.DeviceUtils;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Component
@RequiredArgsConstructor
@Log4j2
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final MemberRepository memberRepository;
    private final JWTUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final GoogleTokenService googleTokenService;
    private final OAuth2AuthorizedClientService authorizedClientService;

    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        String email = authentication.getName();
        log.info("OAuth2 로그인 성공 - email: {}", email);

        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            String registrationId = oauthToken.getAuthorizedClientRegistrationId();
            log.info("registrationId: {}", registrationId);

            // google-secretary 로그인
            if ("google-secretary".equals(registrationId)) {
                log.info("google-secretary 로그인 감지 - 토큰 저장 시작");

                OAuth2AuthorizedClient client = authorizedClientService
                    .loadAuthorizedClient("google-secretary", email);

                log.info("client: {}", client);

                if (client != null) {
                    String accessToken  = client.getAccessToken().getTokenValue();
                    // Refresh Token은 최초 1회만 발급됨 → null이어도 저장
                    String refreshToken = client.getRefreshToken() != null
                        ? client.getRefreshToken().getTokenValue() : "";
                    String scope = client.getAccessToken().getScopes() != null
                        ? String.join(",", client.getAccessToken().getScopes()) : "";

                    log.info("accessToken: {}", accessToken);
                    log.info("refreshToken: {}", refreshToken);
                    log.info("scope: {}", scope);

                    googleTokenService.saveToken(email, GoogleTokenDto.builder()
                        .email(email)
                        .accessToken(accessToken)
                        .refreshToken(refreshToken)
                        .expiresAt(client.getAccessToken().getExpiresAt()
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())
                        .scope(scope)
                        .build());

                    log.info("google-secretary 토큰 저장 완료: {}", email);
                } else {
                    log.warn("OAuth2AuthorizedClient가 null입니다 - email: {}", email);
                }

                String serverName = request.getServerName();
                String frontendBaseUrl = "localhost".equals(serverName) || "127.0.0.1".equals(serverName)
                    ? "http://localhost:3000"
                    : request.getScheme() + "://" + serverName;

                response.sendRedirect(frontendBaseUrl + "/board/secretary");
                return;
            }
        }

        // 기존 로그인 로직 (변경 없음)
        log.info("------------------------ OAuth2 토큰 및 쿠키 생성 시작 ------------------------");

        MemberEntity memberEntity = memberRepository.findById(email)
        	    .orElseThrow(() -> new IllegalStateException("회원 정보를 찾을 수 없습니다: " + email));
        String sessionUuid = UUID.randomUUID().toString().replaceAll("-", "");

        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("sessionUuid", sessionUuid);

        String accessToken  = (String) jwtUtil.generateToken(payload, 1);
        String refreshToken = (String) jwtUtil.generateToken(payload, 8);

        String clientIp      = "";
        String regionInfo    = "";
        String deviceTypeName = "Web Browser";

        try {
            clientIp       = GeoLocationUtils.getClientIp(request);
            regionInfo     = GeoLocationUtils.getRegionInfo(clientIp);
            deviceTypeName = DeviceUtils.getDeviceName(request);
        } catch (Exception e) {
            log.error("====== OAuth2 디바이스 정보 수집 중 예외 가로채기: {} ======", e.getMessage());
            if (clientIp == null || clientIp.isEmpty()) {
                clientIp = request.getRemoteAddr();
            }
        }

        String finalDeviceName = deviceTypeName + " (" + regionInfo + ")";

        try {
            String atKey         = "AT:" + email + ":" + sessionUuid;
            String rtKey         = "RT:" + email + ":" + sessionUuid;
            String userSetKey    = "USER_TOKENS:" + email;
            String deviceInfoKey = "DEVICE_INFO:" + email + ":" + sessionUuid;

            Set<String> deviceKeys = redisTemplate.keys("DEVICE_INFO:" + email + ":*");
            if (deviceKeys != null) {
                for (String oldDeviceKey : deviceKeys) {
                    Object savedNameObj = redisTemplate.opsForHash().get(oldDeviceKey, "deviceName");
                    if (savedNameObj != null) {
                        String oldDeviceName = String.valueOf(savedNameObj).trim();
                        if (finalDeviceName.trim().equals(oldDeviceName)) {
                            String oldUuid = oldDeviceKey.substring(oldDeviceKey.lastIndexOf(":") + 1);
                            redisTemplate.delete("AT:" + email + ":" + oldUuid);
                            redisTemplate.delete("RT:" + email + ":" + oldUuid);
                            redisTemplate.delete(oldDeviceKey);
                            redisTemplate.opsForSet().remove(userSetKey, oldUuid);
                        }
                    }
                }
            }

            redisTemplate.opsForValue().set(atKey, accessToken, 1, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(rtKey, refreshToken, 8, TimeUnit.HOURS);
            redisTemplate.opsForSet().add(userSetKey, sessionUuid);
            redisTemplate.expire(userSetKey, 8, TimeUnit.HOURS);

            Map<String, String> deviceInfo = new HashMap<>();
            deviceInfo.put("deviceName",    deviceTypeName);
            deviceInfo.put("lastActiveTime", LocalDateTime.now().format(TIME_FORMATTER));
            deviceInfo.put("clientIp",      clientIp);
            deviceInfo.put("regionInfo",    regionInfo);

            redisTemplate.opsForHash().putAll(deviceInfoKey, deviceInfo);
            redisTemplate.expire(deviceInfoKey, 8, TimeUnit.HOURS);

        } catch (Exception e) {
            log.error("====== OAuth2 핸들러 내 Redis 연동 트랜잭션 에러: {} ======", e.getMessage());
        }

        String serverName = request.getServerName();
        String frontendBaseUrl;
        String scheme = request.getHeader("X-Forwarded-Proto");

        if (scheme != null && !scheme.isEmpty() && !"unknown".equalsIgnoreCase(scheme)) {
            scheme = scheme.toLowerCase();
        } else {
            scheme = request.getScheme().toLowerCase();
        }

        if ("localhost".equals(serverName) || "127.0.0.1".equals(serverName)) {
            frontendBaseUrl = "http://localhost:3000";
        } else {
            frontendBaseUrl = scheme + "://" + serverName;
        }

        //쿠키 생성
        String[] cookieNames  = {"username", "sessionUuid", "role", "FromSocial", "email", "secretary"};
        String[] cookieValues = { URLEncoder.encode(memberEntity.getUsername(), "UTF-8"),
                sessionUuid, memberEntity.getRole(),
                memberEntity.getFromSocial(), email,
                memberEntity.getSecretary()};
        int[] cookieSeconds   = {
			60 * 60 * 24 * 5,
			60 * 60 * 8,
			60 * 60 * 24 * 5,
			60 * 60 * 24 * 5,
			60 * 60 * 24 * 5,
			60 * 60 * 24 * 5,
			};
			
		for (int i = 0; i < cookieNames.length; i++) {
			Cookie cookie = new Cookie(cookieNames[i], cookieValues[i]);
			cookie.setPath("/");
			cookie.setSecure(true);
			cookie.setHttpOnly(false);
			cookie.setMaxAge(cookieSeconds[i]);
			response.addCookie(cookie);
		}
		
	    // ================== httpOnly 쿠키 설정 구간 추가 ==================
	    // Access Token 쿠키 생성 (만료시간 1시간 = 3600초)
	    Cookie atCookie = new Cookie("accessToken", accessToken);
	    atCookie.setHttpOnly(true);
	    atCookie.setSecure(true); // HTTPS 운영 환경 대응 (로컬 HTTP 테스트 시 필요하면 잠시 false 혹은 주석 처리)
	    atCookie.setPath("/");
	    atCookie.setMaxAge(60 * 60); 
	    response.addCookie(atCookie);

	    // Refresh Token 쿠키 생성 (만료시간 8시간 = 28,800초)
	    Cookie rtCookie = new Cookie("refreshToken", refreshToken);
	    rtCookie.setHttpOnly(true);
	    rtCookie.setSecure(true); // HTTPS 운영 환경 대응
	    rtCookie.setPath("/");
	    rtCookie.setMaxAge(8 * 60 * 60); 
	    response.addCookie(rtCookie);
	    // ============================================================

        String url = frontendBaseUrl + "/board/list?page=1";
        log.info("------------------------ OAuth2 로그인 성공 ==> 접속 사이트 : {} ------------------------",
            frontendBaseUrl);

        setDefaultTargetUrl(url);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}