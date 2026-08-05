package com.board.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.board.service.oauth2.OAuth2FailureHandler;
import com.board.service.oauth2.OAuth2SuccessHandler;
import com.board.service.security.UserDetailsServiceImpl;
import com.board.util.JWTUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Configuration
@EnableWebSecurity
@AllArgsConstructor
@Log4j2
public class WebSecurityConfig {

    private final UserDetailsServiceImpl userDetailsServiceImpl;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final JWTUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;

    // Spring Security가 application.yml에 등록된 OAuth2 클라이언트 정보를
    // 읽어서 자동으로 생성하는 Bean.
    // google-secretary 전용 파라미터를 추가할 때 클라이언트 등록 정보가 필요하므로 주입받음.
    private final ClientRegistrationRepository clientRegistrationRepository;

    // 스프링시큐리티 암호화 스프링빈 등록
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /*
     * 스프링 시큐리티는 보안을 위해 StrictHttpFirewall을 기본적으로 작동 시킴.
     * 이 방화벽은 HTTP 요청 헤더(Cookie, Authorization 등)를 검사할 때,
     * 정해진 아스키(ASCII) 문자 범위를 벗어나거나 정의되지 않은 특수문자가 포함되어 있으면
     * 해킹 시도(HTTP Response Splitting 등)로 간주하고
     * 요청을 강제로 거부(RequestRejectedException)함.
     * 따라서, 헤드에 한글을 넣는 경우 firewall.setAllowedHeaderValues(pattern -> true)를
     * 통해 HTTP 헤더 값 검증 규칙을 완전히 해제하여, 어떤 문자열이 들어오더라도 검사를 통과시켜야 함
     */
    @Bean
    public HttpFirewall allowUnicodeHeaderFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowedHeaderValues(pattern -> true);
        return firewall;
    }

    // 접근 권한 설정
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        // CSRF 비활성화 (JWT 토큰 방식 사용)
        http.csrf((csrf) -> csrf.disable());
        // CORS는 사용자가 규정한 규칙을 따른다.
        http.cors(Customizer.withDefaults());

        // FormLogin, BasicHttp 비활성화
        http.formLogin((formLogin) -> formLogin.disable());
        http.httpBasic((auth) -> auth.disable());

        // JWT Filter 설정
        http.addFilterBefore(new JwtAuthFilter(userDetailsServiceImpl, jwtUtil, redisTemplate),
            UsernamePasswordAuthenticationFilter.class);

        // OAuth2 로그인 설정
        // google-secretary 로그인 시 access_type=offline, prompt=consent 파라미터 추가
        // → Google로부터 Refresh Token을 받아 Oracle + Redis에 저장
        // → 이후 Access Token 만료 시 자동 갱신 (재인증 불필요)
        // 일반 google, naver 로그인에는 영향을 주지 않음
        http.oauth2Login((login) -> login
            .authorizationEndpoint(authorization -> authorization
                .authorizationRequestResolver(
                    new OAuth2AuthorizationRequestResolver() {
                        private final DefaultOAuth2AuthorizationRequestResolver defaultResolver =
                            new DefaultOAuth2AuthorizationRequestResolver(
                                clientRegistrationRepository, "/oauth2/authorization");

                        @Override
                        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                            return customize(defaultResolver.resolve(request));
                        }

                        @Override
                        public OAuth2AuthorizationRequest resolve(HttpServletRequest request,
                                String clientRegistrationId) {
                            return customize(defaultResolver.resolve(request, clientRegistrationId));
                        }

                        // gmail scope가 포함된 요청 → google-secretary 요청으로 판단
                        // access_type=offline : Refresh Token 발급 요청
                        // prompt=consent      : 동의 화면 강제 표시 → Refresh Token 재발급
                        private OAuth2AuthorizationRequest customize(OAuth2AuthorizationRequest req) {
                            if (req == null) return null;
                            if (req.getScopes().contains("https://mail.google.com/")) {
                                log.info("google-secretary 감지 - access_type=offline, prompt=consent 추가");
                                return OAuth2AuthorizationRequest.from(req)
                                    .additionalParameters(params -> {
                                        params.put("access_type", "offline");
                                        params.put("prompt", "consent");
                                    })
                                    .build();
                            }
                            return req;
                        }
                    }
                )
            )
            .successHandler(oAuth2SuccessHandler)
            .failureHandler(oAuth2FailureHandler));

        // 세션 관리
        /*
         OAuth2LoginAuthenticationFilter가 Google 로그인 콜백(/login/oauth2/code/google-secretary)을
         처리할 때 세션이 필요함. STATELESS로 하면 OAuth2 로그인 중간에 state 파라미터 검증이 실패해서 
         "Authorization Request not found" 에러가 발생. 
         그래서 OAuth2 로그인 흐름을 위해 IF_REQUIRED로 설정해야함.          
         */
        http.sessionManagement((session) -> session
            .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        // 접근 권한 설정
        http.authorizeHttpRequests((authz) -> authz
            .requestMatchers("/actuator/**").permitAll()
            .requestMatchers("/login/oauth2/**", "/api/login/oauth2/**").permitAll()
            .requestMatchers("/oauth2/authorization/**").permitAll()
            .requestMatchers("/api/member/**").permitAll()
            .requestMatchers("/apitest/**").permitAll()
            .requestMatchers("/api/chat/**").permitAll()
            .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/board/**").hasAnyAuthority("USER", "MASTER")
            .requestMatchers("/api/rag/**").hasAnyAuthority("MASTER")
            .requestMatchers("/api/master/**").hasAnyAuthority("MASTER")
            .requestMatchers("/mcp/**").hasAnyAuthority("USER","MASTER")
            .anyRequest().authenticated());

        http.exceptionHandling((exceptions) -> exceptions
            .authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\": \"Unauthorized\", \"message\": \"인증이 필요합니다.\"}");
            })
        );

        log.info("=============== 스프링 시큐리티 필터 체인 설정 완료 ===============");
        return http.build();
    }

    // react와 연동을 위한 cors 설정
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
            "http://localhost:3000", "http://www.boardreact.com", "https://www.boardreact.com",
            "http://www.boardnext.com", "https://www.boardnext.com", "http://www.boardnuxt.com"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}