package com.board.config;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate; // 🌟 추가
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import com.board.service.security.UserDetailsServiceImpl;
import com.board.util.JWTUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    
    private final UserDetailsServiceImpl userDetailsServiceImpl;
    private final JWTUtil jwtUtil;
    private final StringRedisTemplate redisTemplate; 
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        
        //if (path.startsWith("/actuator") || path.startsWith("/api/actuator")) return true;
        if (path.contains("/actuator")) return true;
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) return true;
        if (path.equals("/api/member/logout")) return true;
        if (path.startsWith("/oauth2") || path.startsWith("/api/oauth2")) return true;        
        if (path.startsWith("/login/oauth2") || path.startsWith("/api/login/oauth2")) return true; 
        
        return false;
    }
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
    	
        String authorizationHeader = request.getHeader("Authorization");
        
        if(authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {            
            String token = authorizationHeader.substring(7);            
            
            if(jwtUtil.validateToken(token).equals("VALID_JWT")) {
                try {
                    // 1. 토큰 payload에서 데이터 추출
                    Map<String, Object> tokenData = jwtUtil.getDataFromToken(token);
                    String email = (String) tokenData.get("email");
                    String sessionUuid = (String) tokenData.get("sessionUuid"); 
                    
                    if (email != null && sessionUuid != null) {
                        
                        // 2. Redis 화이트리스트 실시간 검증
                        String atKey = "AT:" + email + ":" + sessionUuid;
                        Boolean hasKey = redisTemplate.hasKey(atKey);
                        
                        // 강제 로그아웃 등의 사유로 Redis에 Access Token 키가 없다면 인증 거부
                        if (hasKey == null || !hasKey) {
                            log.warn("인증 실패 - 만료되거나 강제 로그아웃 처리된 장치 세션 요청 차단: {}", email);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401 에러 설정
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"message\":\"EXPIRED_OR_KICKED_OUT\"}");
                            return; // 필터 체인을 중단하고 즉시 반환하여 요청을 차단.
                        }
                        
                        // 3. 마지막 활성화 시간(Hash) 실시간 갱신
                        String deviceInfoKey = "DEVICE_INFO:" + email + ":" + sessionUuid;
                        if (Boolean.TRUE.equals(redisTemplate.hasKey(deviceInfoKey))) {
                        	String formattedTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                            redisTemplate.opsForHash().put(deviceInfoKey, "lastActiveTime", formattedTime);
                        }

                        // 4. 시큐리티 컨텍스트 등록
                        UserDetails userDetails = userDetailsServiceImpl.loadUserByUsername(email);
                        if(userDetails != null) {                    
                            UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken = 
                                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                            SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
                        }
                    }
                } catch(Exception e) {
                    log.error("필터 내 토큰 검증 오류: {}", e.getMessage());
                }
            }
        }
        filterChain.doFilter(request, response);        
    }
}