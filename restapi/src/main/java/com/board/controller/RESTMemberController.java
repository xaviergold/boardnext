package com.board.controller;

import java.io.File;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.board.async.MemberKafkaProducer;
import com.board.dto.board.MemberDTO;
import com.board.dto.event.MemberEventDTO;
import com.board.entity.AddressEntity;
import com.board.entity.repository.AddressRepository;
import com.board.entity.repository.MemberRepository;
import com.board.service.agent.GoogleTokenService;
import com.board.service.board.MemberService;
import com.board.util.DeviceUtils;
import com.board.util.GeoLocationUtils;
import com.board.util.JWTUtil;
import com.board.util.PageUtil;
import com.board.util.PasswordMaker;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

//@CrossOrigin(originPatterns = "http://www.boardreact.com")
//@CrossOrigin(originPatterns = "http://localhost:3000")
@RestController
@AllArgsConstructor
@Tag(name="회원 관리 API", description="회원 관리 API를 모아 놓은 회원관리 클래스")
@Log4j2
public class RESTMemberController {
	
	private final MemberService service;
	private final MemberRepository memberRepository;
	private final AddressRepository addressRepository;
	private final BCryptPasswordEncoder pwdEncoder;
	private final JWTUtil jwtUtil;	
	private final StringRedisTemplate redisTemplate;
	private final GoogleTokenService googleTokenService;
	private final MemberKafkaProducer kafkaProducer; // 회원가입 비동기 이벤트 발행
	
	private static final DateTimeFormatter DECORATED_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	
	//토큰 생성, Redis 저장, 로그 기록, JSON 문자열 작성을 담당하는 공통 메서드
	private String generateLoginResponse(MemberDTO member, String message, HttpServletRequest request, HttpServletResponse response) throws Exception {
	    Map<String, Object> token = new HashMap<>();
	    token.put("email", member.getEmail());
	    
	    // 1. 현재 로그인 시도하는 세션의 고유 UUID를 정의하여 Redis에 저장 --> 한명의 사용자 여러번 여러 장치에서 로그인 시도할때 마다 고유한 세션 생성 
	    String sessionUuid = UUID.randomUUID().toString().replaceAll("-", "");
	    token.put("sessionUuid", sessionUuid); 
	    
	    //2. accessToken, refreshToken 생성
	    String accessToken = jwtUtil.generateToken(token, 1); 
	    String refreshToken = jwtUtil.generateToken(token, 8); 
	    
	    //3. 실제 클라이언트의 IP, 지역정보, 접속 시도하는 장비 종류를 알아냄
	    String clientIp = GeoLocationUtils.getClientIp(request);
	    String regionInfo = GeoLocationUtils.getRegionInfo(clientIp); 
	    String deviceTypeName = DeviceUtils.getDeviceName(request); 
	    String finalDeviceName = deviceTypeName + " (" + regionInfo + ")";
	    
	    try { 
	    	//4. 사용자 email과 앞서 만든 sessionUiid를 가지고 Redis에 저장할 여러 키에 대한 값들을 생성
	        String email = member.getEmail();
	        String atKey = "AT:" + email + ":" + sessionUuid; //sessionUuid를 조합하여 로그인 건별로 고유한 AT 키값을 생성 
	        String rtKey = "RT:" + email + ":" + sessionUuid; //sessionUuid를 조합하여 로그인 건별로 고유한 RT 키값을 생성 
	        String userSetKey = "USER_TOKENS:" + email; //이 값을 이용하여 사용자가 가진 여러개의 session들을 추출할 수 있음.
	        String deviceInfoKey = "DEVICE_INFO:" + email + ":" + sessionUuid; //sessionUuid를 조합하여 로그인 건별로 접속한 장비를 분류할 수 있음
	        
	        //중복 세션 삭제 --> 이걸 안하면 과거에 동일 기기에 접속했던 과거 세션 기록이 다 나옴. 로그 아웃에서 필요한 건 현재 접속한 세션 기록임. 
	        /////////////// 중복 세션 삭제 명령 구간 시작 /////////////////////////////////////////////
	        Set<String> existingSessions = redisTemplate.opsForSet().members(userSetKey);
	        if (existingSessions != null) {
	            for (String oldUuid : existingSessions) {
	                
	                //새로 생성한 현재 세션 UUID와 같다면 지우지 않고 패스!
	                if (sessionUuid.equals(oldUuid)) {
	                    continue;
	                }
	                
	                String oldDeviceInfoKey = "DEVICE_INFO:" + email + ":" + oldUuid;
	                Object savedNameObj = redisTemplate.opsForHash().get(oldDeviceInfoKey, "deviceName");
	                
	                if (savedNameObj != null) {
	                    String oldDeviceName = String.valueOf(savedNameObj).trim();
	                    
	                    // 동일 기기명이 발견되면 구형 세션 AT, RT, DEVICE_INFO 삭제
	                    if (finalDeviceName.trim().equals(oldDeviceName)) {
	                        redisTemplate.delete("AT:" + email + ":" + oldUuid);
	                        redisTemplate.delete("RT:" + email + ":" + oldUuid);
	                        redisTemplate.delete("DEVICE_INFO:" + email + ":" + oldUuid);
	                        redisTemplate.opsForSet().remove(userSetKey, oldUuid);
	                        log.info("과거 중복 장치 세션 파기 완료: {}", oldUuid);
	                    }
	                } else {
	                    //만약 DEVICE_INFO는 유실되었는데 USER_TOKENS 세션 껍데기만 남아있는 더미 데이터 처리
	                    redisTemplate.opsForSet().remove(userSetKey, oldUuid);
	                }
	            }
	        }
	        /////////////// 중복 세션 삭제 명령 구간 끝 /////////////////////////////////////////////
	        
	        
	        // 현재 신규 세션 정보 적재 진행
	        redisTemplate.opsForValue().set(atKey, accessToken, 1, TimeUnit.HOURS);
	        redisTemplate.opsForValue().set(rtKey, refreshToken, 8, TimeUnit.HOURS);
	        redisTemplate.opsForSet().add(userSetKey, sessionUuid);
	        redisTemplate.expire(userSetKey, 8, TimeUnit.HOURS);
	        
	        Map<String, String> deviceInfo = new HashMap<>();
	        deviceInfo.put("deviceName", finalDeviceName);
	        deviceInfo.put("lastActiveTime", LocalDateTime.now().format(DECORATED_TIME_FORMATTER)); 
	        deviceInfo.put("clientIp", clientIp);                             
	        deviceInfo.put("regionInfo", regionInfo);
	        
	        redisTemplate.opsForHash().putAll(deviceInfoKey, deviceInfo);
	        redisTemplate.expire(deviceInfoKey, 8, TimeUnit.HOURS);

	        log.info("Redis에 신규 토큰 및 디바이스 관제 정보 적재 완료 -> 장치명: {}", finalDeviceName);
	        
	    } catch(Exception e) {
	    	log.error("====== Redis 처리 중 에러 발생: {} ======", e.getMessage());
	        e.printStackTrace(); // 풀 스택트레이스 출력
	        throw new RuntimeException("Redis 저장 실패", e);
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

	    service.lastdateUpdate(member.getEmail(), "login");
	    service.memberLogRegistry(member.getEmail(), "login");
	
	    return "{\"message\":\"" + message + "\","
	    		+ "\"email\":\"" + member.getEmail() + "\","
	            + "\"authkey\":\"" + member.getAuthkey() + "\","
	            //+ "\"accessToken\":\"" + accessToken + "\","
	            //+ "\"refreshToken\":\"" + refreshToken + "\","
	            + "\"sessionUuid\":\"" + sessionUuid + "\","
	            + "\"username\":\"" + URLEncoder.encode(member.getUsername(), "UTF-8") + "\","
	            + "\"secretary\":\"" + member.getSecretary() + "\","
	            + "\"role\":\"" + member.getRole() + "\"}";
	}
	
	//로그인
	@PostMapping("/api/member/loginCheck")
	public ResponseEntity<String> postLogIn(MemberDTO loginData,HttpSession session, 
			@RequestParam("autoLogin") String autoLogin, HttpServletRequest request, HttpServletResponse response) throws Exception {
		
		String authkey = "";
		
		//패스워드 확인 후 마지막 패스워드 변경일이 30일이 경과 되었을 경우 
		//마지막 패스워드 확인 날짜(lastpwcheckDate)에 30일을 더함		
		int addedDate = 30;
		LocalDateTime today = LocalDateTime.now();
				
		//[자동 로그인 처리] authkey가 클라이언트에 쿠키로 존재할 경우 로그인 과정 없이 세션 생성 후 게시판 목록 페이지로 이동  
		if(autoLogin.equals("PASS")) {	
			//패스워드 변경 후 30일 경과 여부 확인 
			MemberDTO currentMember = new MemberDTO(memberRepository.findByAuthkey(loginData.getAuthkey()));
			LocalDateTime lastpwcheckDate = currentMember.getLastpwcheckdate();
			LocalDateTime reDate = lastpwcheckDate.plusDays(addedDate);			
			if(currentMember != null) {
				if(reDate.compareTo(today) < 0)  {//패스워드 변경일이 지났으면...
					log.info("자동 로그인 실패 (패스워드 변경 기한 만료)...");
					return ResponseEntity.ok().body(generateLoginResponse(currentMember, "expired", request, response));
				} else {					
					log.info("자동 로그인 성공 ...");
					log.info("email = {}", currentMember.getEmail());
					return ResponseEntity.ok().body(generateLoginResponse(currentMember, "good", request, response));
				}
			} 			
		}
				
		//아이디 존재 여부 확인
		if(service.idCheck(loginData.getEmail()) == 0) {
			log.info("아이디가 존재 하지 않음");
			return ResponseEntity.ok().body("{\"message\":\"ID_NOT_FOUND\"}");
		}			
		
		//아이디가 존재하면 읽어온 email로 로그인 정보 가져 오기
		MemberDTO member = service.memberInfo(loginData.getEmail());
		
		//패스워드 확인
		if(!pwdEncoder.matches(loginData.getPassword(),member.getPassword())) {
			log.info("패스워드가 틀렸음");
			return ResponseEntity.ok().body("{\"message\":\"PASSWORD_NOT_FOUND\"}");
		}
		
		//Form에서 읽은 email, 패스워드 값으로 로그인
		if(autoLogin.equals("NEW")) {			
			//authkey 생성 및 DB 저장 
			authkey = UUID.randomUUID().toString().replaceAll("-", ""); 
			member.setAuthkey(authkey);
			service.authkeyUpdate(member);
			//패스워드 변경 후 30일 경과 여부 확인 
			LocalDateTime lastpwcheckDate = member.getLastpwcheckdate();
			LocalDateTime reDate = lastpwcheckDate.plusDays(addedDate);	
			if(reDate.compareTo(today) < 0) {  //패스워드 변경일이 지났으면...
				log.info("아이디/패스워드 로그인 실패 (패스워드 변경 기한 만료)...");
                return ResponseEntity.ok().body(generateLoginResponse(member, "expired", request, response));
			}	
		} 
		//패스워드 기한이 안 지났으면 정상 로그인 응답 전달
		log.info("아이디/패스워드 정상 로그인 ...");
		log.info("email = {}", member.getEmail());
		GeoLocationUtils ipLocation = new GeoLocationUtils();
		DeviceUtils deviceUtils = new DeviceUtils();
		log.info("======================  Device 정보 : {} ======================", deviceUtils.getDeviceName(request));
		log.info("======================  IP 정보 : {} ======================", ipLocation.getClientIp(request));
		log.info("======================  IP 위치 정보 : {} ======================", ipLocation.getRegionInfo(ipLocation.getClientIp(request)));
		return ResponseEntity.ok().body(generateLoginResponse(member, "good", request, response));

	}
	
	// RESTMemberController.java

	//로그아웃
	@PostMapping("/api/member/logout")
	public ResponseEntity<?> logoutByDevice(
	        @RequestParam("email") String email,
	        @RequestParam("type") String type,
	        @RequestParam(value = "sessionUuid", required = false) String sessionUuid,
	        HttpServletRequest request,
	        HttpServletResponse response
			) throws Exception {
	    
	    if (email == null || type == null) {
	        return ResponseEntity.badRequest().body("{\"message\":\"MISSING_REQUIRED_PARAMS\"}");
	    }
	    
	    String userSetKey = "USER_TOKENS:" + email;
	    
	    // 모든 장치 로그아웃 (ALL)
	    if ("ALL".equalsIgnoreCase(type)) {
	        Set<String> keysToDelete = new HashSet<>();
	        Set<String> atKeys = redisTemplate.keys("AT:" + email + ":*");
	        Set<String> rtKeys = redisTemplate.keys("RT:" + email + ":*");
	        Set<String> deviceKeys = redisTemplate.keys("DEVICE_INFO:" + email + ":*");
	        
	        if (atKeys != null) keysToDelete.addAll(atKeys);
	        if (rtKeys != null) keysToDelete.addAll(rtKeys);
	        if (deviceKeys != null) keysToDelete.addAll(deviceKeys);
	        
	        if (!keysToDelete.isEmpty()) {
	            redisTemplate.delete(keysToDelete);
	        }
	        redisTemplate.delete(userSetKey);
	        
	        service.lastdateUpdate(email, "logout");
	        service.memberLogRegistry(email, "logout");
	        return ResponseEntity.ok().body("{\"status\":\"good\"}");
	    } 
	    
	    // 현재 기기(CURRENT) 또는 선택 기기 원격 강제 로그아웃 (TARGET)
	    if ("CURRENT".equalsIgnoreCase(type) || "TARGET".equalsIgnoreCase(type)) {
	        if (sessionUuid == null || sessionUuid.isEmpty() || "undefined".equals(sessionUuid)) {
	            return ResponseEntity.badRequest().body("{\"message\":\"MISSING_SESSION_UUID\"}");
	        }
	        
	        redisTemplate.delete("AT:" + email + ":" + sessionUuid);
	        redisTemplate.delete("RT:" + email + ":" + sessionUuid);
	        redisTemplate.delete("DEVICE_INFO:" + email + ":" + sessionUuid);
	        
	        redisTemplate.opsForSet().remove(userSetKey, sessionUuid);
	        log.info("대상 UUID 기기에서 로그 아웃 완료: {}", sessionUuid);
	        
	        service.lastdateUpdate(email, "logout");
	        service.memberLogRegistry(email, "logout");
	        
	        // CURRENT(본인이 지금 쓰는 세션을 로그아웃)일 때만 "이 요청을 보낸 브라우저"의 쿠키를 삭제.
	        // TARGET(다른 세션을 원격으로 강제 로그아웃)은 대상 세션의 Redis 데이터만 지우면 충분함.
	        // 쿠키는 요청을 실제로 보낸 브라우저(로그아웃을 실행시킨 본인)의 것이므로,
	        // TARGET 처리 중에 지우면 엉뚱하게 본인이 로그아웃되어 버림.
	        if ("CURRENT".equalsIgnoreCase(type)) {
	            //accessToken 쿠키 삭제 (동일한 옵션으로 만들고 maxAge=0)
	            Cookie atCookie = new Cookie("accessToken", null);
	            atCookie.setHttpOnly(true);
	            atCookie.setSecure(true);
	            atCookie.setPath("/");
	            atCookie.setMaxAge(0);
	            response.addCookie(atCookie);

	            //refreshToken 쿠키 삭제
	            Cookie rtCookie = new Cookie("refreshToken", null);
	            rtCookie.setHttpOnly(true);
	            rtCookie.setSecure(true);
	            rtCookie.setPath("/");
	            rtCookie.setMaxAge(0);
	            response.addCookie(rtCookie);
	        }
	        
	        return ResponseEntity.ok().body("{\"status\":\"good\"}");
	    }

	    return ResponseEntity.badRequest().body("{\"message\":\"INVALID_LOGOUT_TYPE\"}");
	}
	
	//토큰 유효성 검사
	@GetMapping("/api/member/validateToken")
	public String getValidate(HttpServletRequest request) throws Exception {
		String token = jwtUtil.getTokenFromAuthorization(request);
		if(token.equals("INVALID_HEADER")) //Authorization Header에 Bearer가 존재하지 않음 
			return "{\"message\":\"bad\"}";
		String jwtCheck = jwtUtil.validateToken(token);
		
		switch(jwtCheck) { //Bearer 내의 JWT의 상태			
			case "VALID_JWT" : return "{\"message\":\"VALID_JWT\", \"email\":\"" + (String)jwtUtil.getDataFromToken(token).get("email") + "\"}";			
			case "EXPIRED_JWT" : return "{\"message\":\"EXPIRED_JWT\"}";
			case "INVALID_JWT":
			case "UNSUPPORTED_JWT":
			case "EMPTY_JWT": return "{\"message\":\"INVALID_JWT\"}";													
		}
		return null;
	}
	
	//Access Token 만료 시 Refresh Token을 확인하고 새 토큰을 발행
	@PostMapping("/api/member/refreshToken")
	public ResponseEntity<String> refreshAccessToken(
	        @RequestParam("email") String email,
	        @RequestParam("refreshToken") String clientRefreshToken,
	        @RequestParam("sessionUuid") String sessionUuid,
	        HttpServletResponse response ) throws Exception { 

	    String savedRefreshToken = null;
	    
	    // Redis 키 사전 정의
	    String rtKey         = "RT:" + email + ":" + sessionUuid;
	    String atKey         = "AT:" + email + ":" + sessionUuid;
	    String deviceInfoKey = "DEVICE_INFO:" + email + ":" + sessionUuid;
	    String userSetKey    = "USER_TOKENS:" + email;
	    
	    //Redis 내에 저장된 refreshToken의 유효성 검사
	    try {
	    	//Redis에 저장할 refreshToken을 저장할 키 생성 규칙에 따라 키를 만듬
	        //위에서 만든 키에 저장된 키값. 즉, refreshToken을 값을 꺼내 옴
	        savedRefreshToken = redisTemplate.opsForValue().get(rtKey);

	        //Redis에서 꺼내온 refreshToken이 null이거나 인자로 넘겨 받은 refreshToken과 Redis에서 꺼내온 refreshToken이 같지 않으면...  
	        if (savedRefreshToken == null || !savedRefreshToken.equals(clientRefreshToken)) {
	            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
	                .body("{\"message\":\"INVALID_REFRESH_TOKEN\"}");
	        }
	    } catch (Exception e) {
	        log.error("Redis 장애: {}", e.getMessage());
	        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
	            .body("{\"message\":\"SERVER_CACHE_ERROR\"}");
	    }

	    //인자로 넘겨 받은 refreshToken의 유효성 검사
	    String jwtCheck = jwtUtil.validateToken(clientRefreshToken);
	    if (!jwtCheck.equals("VALID_JWT")) {
	        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
	            .body("{\"message\":\"EXPIRED_OR_INVALID_REFRESH_TOKEN\"}");
	    }

	    //=========== 지금까지 잘 넘어 왔으면 refreshToken은 정상임... ===========   
	    
	    //토큰에 Payload에 집어 넣을 값을 만듬 
	    Map<String, Object> tokenData = new HashMap<>();
	    tokenData.put("email", email);	    
	    tokenData.put("sessionUuid", sessionUuid); 
	    
	    //신규 accessToken을 만듬
	    String newAccessToken = jwtUtil.generateToken(tokenData, 1);

	    // RT 남은 TTL 확인
	    // Redis에 저장된 RT 키의 남은 만료 시간을 분 단위로 조회
	    // RT가 아직 충분히 남아 있으면 굳이 새로 만들 필요가 없으므로
	    // 남은 시간이 10분 이하일 때만 새 RT를 발급하여 불필요한 토큰 생성을 방지
	    Long rtTtl = redisTemplate.getExpire(rtKey, TimeUnit.MINUTES);
	    String newRefreshToken = null;

	    if (rtTtl != null && rtTtl <= 10) {
	        // RT 잔여 시간이 10분 이하 → 새 RT 발급 후 Redis 갱신
	        // 기존 RT 키에 새로 발급한 RT를 덮어쓰고 TTL을 3시간으로 리셋 (Sliding Expiry)
	        newRefreshToken = jwtUtil.generateToken(tokenData, 8);
	        redisTemplate.opsForValue().set(rtKey, newRefreshToken, 8, TimeUnit.HOURS);
	        log.info("RT 잔여 {}분 → 새 RT 재발급 완료 ({}, session: {})", rtTtl, email, sessionUuid);
	    } else {
	        // [추가] RT 잔여 시간이 충분 → 기존 RT 그대로 유지
	        log.info("RT 잔여 {}분 → 기존 RT 유지 ({}, session: {})", rtTtl, email, sessionUuid);
	    }

	    //새 AT로 Redis 갱신
	    redisTemplate.opsForValue().set(atKey, newAccessToken, 1, TimeUnit.HOURS);

	    // DEVICE_INFO, USER_TOKENS TTL 슬라이딩 갱신
	    // AT 재발급 시 관련 키들의 TTL도 함께 연장하여
	    // 디바이스 정보와 세션 목록이 활동 중에 만료되지 않도록 방지
	    redisTemplate.expire(deviceInfoKey, 8, TimeUnit.HOURS);
	    redisTemplate.expire(userSetKey,    8, TimeUnit.HOURS);

	    log.info("새 Access Token 재발급 성공 ({}, session: {})", email, sessionUuid);

	    // ================== httpOnly 쿠키 설정 구간 추가 ==================
	    // Access Token 쿠키 생성 (만료시간 1시간 = 3600초)
	    Cookie atCookie = new Cookie("accessToken", newAccessToken);
	    atCookie.setHttpOnly(true);
	    atCookie.setSecure(true); // HTTPS 운영 환경 대응 (로컬 HTTP 테스트 시 필요하면 잠시 false 혹은 주석 처리)
	    atCookie.setPath("/");
	    atCookie.setMaxAge(60 * 60); 
	    response.addCookie(atCookie);

	    // Refresh Token 쿠키 생성 (새로 발급된 경우에만)
	    if (newRefreshToken != null) {
	        Cookie rtCookie = new Cookie("refreshToken", newRefreshToken);
	        rtCookie.setHttpOnly(true);
	        rtCookie.setSecure(true);
	        rtCookie.setPath("/");
	        rtCookie.setMaxAge(8 * 60 * 60);
	        response.addCookie(rtCookie);
	    }

	    // RT가 새로 발급된 경우에만 응답 body에 포함
	    String refreshTokenJsonPart = (newRefreshToken != null)
	            ? "\"refreshToken\":\"" + newRefreshToken + "\","
	            : "";

	    return ResponseEntity.ok()
	            .contentType(MediaType.APPLICATION_JSON)
	            .body("{"
	                    + "\"accessToken\":\"" + newAccessToken + "\","
	                    + refreshTokenJsonPart
	                    + "\"message\":\"good\""
	                  + "}");
	}
	
	@PostMapping("/api/member/current-tokens")
    public ResponseEntity<String> getCookiesToClient(HttpServletRequest request) throws Exception {
        
        // 1. 요청 헤더에 담긴 전체 쿠키 배열을 가져옴
        Cookie[] cookies = request.getCookies();
        
        String accessToken = "";
        String refreshToken = "";

        // 2. 쿠키 배열이 비어있지 않다면 순회하면서 원하는 토큰 값을 추출
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                // accessToken 키를 가진 쿠키 값 추출
                if ("accessToken".equals(cookie.getName())) {
                    accessToken = cookie.getValue();
                }
                // refreshToken 키를 가진 쿠키 값 추출
                if ("refreshToken".equals(cookie.getName())) {
                    refreshToken = cookie.getValue();
                }
            }
        }

        // 3. Next.js 프런트엔드에서 파싱하여 바로 사용할 수 있도록 JSON 형태의 스트링으로 조립하여 리턴
        return ResponseEntity.ok()
        		.contentType(MediaType.APPLICATION_JSON)
        		.body("{\"accessToken\":\"" + accessToken + "\","
        				+ "\"refreshToken\":\"" + refreshToken + "\""
        				+ "}");
    }
	
	//Redis에 저장된 디바이스 정보 가져 오기
	@GetMapping("/api/member/devices")
	public ResponseEntity<?> getActiveDevices(@RequestParam("email") String email, HttpServletRequest request) {
	    try {
	        List<Map<String, Object>> deviceList = new ArrayList<>();
	        
	        // 레디스에 살아있는 실물 DEVICE_INFO 키들을 패턴 검색
	        Set<String> deviceKeys = redisTemplate.keys("DEVICE_INFO:" + email + ":*");
	        
	        if (deviceKeys != null) {
	            for (String deviceKey : deviceKeys) {
	                Map<Object, Object> entries = redisTemplate.opsForHash().entries(deviceKey);
	                
	                if (entries != null && !entries.isEmpty()) {
	                    // 키 구조에서 sessionUuid 잘라내기
	                    String uuid = deviceKey.substring(deviceKey.lastIndexOf(":") + 1);
	                    
	                    Map<String, Object> deviceInfo = new HashMap<>();
	                    deviceInfo.put("sessionUuid", uuid); 
	                    deviceInfo.put("deviceName", entries.get("deviceName"));
	                    deviceInfo.put("lastActiveTime", entries.get("lastActiveTime"));
	                    deviceInfo.put("clientIp", entries.get("clientIp"));
	                    deviceInfo.put("regionInfo", entries.get("regionInfo"));
	                    
	                    deviceList.add(deviceInfo);
	                }
	            }
	        }
	        
	        deviceList.sort((d1, d2) -> ((String) d2.get("lastActiveTime")).compareTo((String) d1.get("lastActiveTime")));
	        return ResponseEntity.ok().body(deviceList);
	        
	    } catch (Exception e) {
	        log.error("디바이스 목록 조회 중 에러: {}", e.getMessage());
	        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"message\":\"SERVER_ERROR\"}");
	    }
	}

	
	//아이디 중복 확인
	@PostMapping("/api/member/idCheck")
	public ResponseEntity<String> getIdCheck(@RequestParam("email") String email) {	
		String json = service.idCheck(email) == 0 ? "{\"status\":\"good\"}":"{\"status\":\"bad\"}";
		return ResponseEntity.ok().body(json);
	}
	
	//회원 등록 및 기본 정보 수정
	@PostMapping("/api/member/signup")
	public ResponseEntity<Map<String,String>> postSignup(MemberDTO member, @RequestParam("kind") String kind,
			@RequestParam(name="imgProfile",required=false) MultipartFile mpr) throws Exception {
		
		//운영체제에 따라 이미지가 저장될 디렉토리 구조 설정 시작
		String os = System.getProperty("os.name").toLowerCase();
		String path;
		if(os.contains("win"))
			path = "c:\\Repository\\profile\\";
		else 
			path = "/var/opt/Repository/profile/";
		
		//디렉토리가 존재하는지 체크해서 없다면 생성
		File p = new File(path);
		if(!p.exists()) p.mkdir();
		//운영체제에 따라 이미지가 저장될 디렉토리 구조 설정 종료
		
		File targetFile = null;
		String org_filename = "";
		String org_fileExtension = "";
		String stored_filename = "";
		
		if(mpr != null) {
			
			org_filename = mpr.getOriginalFilename();
			org_fileExtension = org_filename.substring(org_filename.lastIndexOf("."));			
			stored_filename = UUID.randomUUID().toString().replaceAll("-", "") + org_fileExtension;
			
			try {
				targetFile = new File(path + stored_filename);				
				mpr.transferTo(targetFile);
				
				member.setOrg_filename(org_filename);
				member.setStored_filename(stored_filename);
				member.setFilesize(mpr.getSize());				
				
			}catch(Exception e) {
				e.printStackTrace();
			}
		}
		
		//회원 등록시 - Kafka 비동기 처리
		String signupJobId = null; // 비동기 가입 시에만 채워짐 - 클라이언트 폴링용
		if(kind.equals("I")) {
			
			// 동시 가입 시도 방어용 Redis 락
			// idCheck()로 중복 체크를 통과했더라도, 이벤트 발행 후 Consumer가 실제 INSERT 하기 전까지
			// 같은 이메일로 또 가입 요청이 들어올 수 있는 동시성 창이 생기므로 짧은 TTL의 락으로 방어
			String signupLockKey = "SIGNUP_LOCK:" + member.getEmail();
			Boolean lockAcquired = redisTemplate.opsForValue()
					.setIfAbsent(signupLockKey, "1", 1, TimeUnit.MINUTES);
			
			if (Boolean.FALSE.equals(lockAcquired)) {
				Map<String,String> conflictData = new HashMap<>();
				conflictData.put("status", "DUPLICATE_REQUEST");
				return ResponseEntity.status(HttpStatus.CONFLICT).body(conflictData);
			}
			
			//패스워드 암호화 - Kafka에는 절대 평문으로 실어 보내지 않음
			member.setPassword(pwdEncoder.encode(member.getPassword()));
			
			MemberEventDTO memberEvent = MemberEventDTO.builder()
					.email(member.getEmail())
					.username(member.getUsername())
					.password(member.getPassword())
					.gender(member.getGender())
					.hobby(member.getHobby())
					.job(member.getJob())
					.description(member.getDescription())
					.zipcode(member.getZipcode())
					.address(member.getAddress())
					.telno(member.getTelno())
					.nickname(member.getNickname())
					.org_filename(member.getOrg_filename())
					.stored_filename(member.getStored_filename())
					.filesize(member.getFilesize())
					.FromSocial(member.getFromSocial())
					.secretary(member.getSecretary())
					.build();
			
			signupJobId = kafkaProducer.publishMemberSignup(memberEvent);
			
			// 락은 짧게만 유지하면 됨 - Consumer가 처리를 마치면 회원이 DB에 존재하게 되므로
			// idCheck()가 자연스럽게 다음 중복을 막아줌. TTL이 곧 만료되므로 별도 해제 불필요
		}
		
		Map<String,String> data = new HashMap<>();
		data.put("status", "good");
		data.put("username", URLEncoder.encode(member.getUsername(),"UTF-8"));
		if (signupJobId != null) {
			data.put("jobId", signupJobId);
			data.put("status", "PROCESSING");
		}
		
		//회원 수정시
		if(kind.equals("U")) {
			
			//프로필 이미지 변경 시에 기존 프로필 이미지 삭제 
			if(mpr != null) {
				MemberDTO m = service.memberInfo(member.getEmail());
				File file = new File(path + m.getStored_filename());//수정되기 전의 stored_file
				file.delete();
			}		

			//회원 수정
			service.modifyMemberInfo(member);
			
			// secretary=Y --> Google 권한 요청
		    if("Y".equals(member.getSecretary())) {
		        data.put("redirect", "/oauth2/authorization/google-secretary");
		    }
		    
		    // secretary=N --> Google 토큰 삭제
		    if("N".equals(member.getSecretary())) {
		        googleTokenService.revokeToken(member.getEmail());
		    }

		}
		
		//OAuth2로 가입한 회원의 정식 회원으로 전환
		if(kind.equals("OI")) {
			//기존에 OAuth2 로그인 시 저장했던 회원정보를 수정. 기본 정보와 패스워드 변경 
			service.modifyMemberInfo(member); 
			service.modifyMemberPassword(member.getEmail(), member.getPassword());
			service.lastdateUpdate(member.getEmail(), "password");
			service.lastdateUpdate(member.getEmail(), "pwcheck");
			
			// TODO: secretary=N 체크 해제 시 토큰 삭제
		    // GoogleTokenService 구현 후 추가
		    // if("N".equals(member.getSecretary())) {
		    //     googleTokenService.revokeToken(member.getEmail());
		    // }
			
		}				

		//개인비서 서비스를 위한 구글 인증 등록
		if("Y".equals(member.getSecretary()) && kind.equals("I")) {
	        data.put("redirect", "/oauth2/authorization/google-secretary");
	    }
		
		return ResponseEntity.ok().body(data);
		
	}
	
	//회원 가입 시 이메일 인증 번호 전송
	@PostMapping("/api/member/sendVerifyCode")
	public ResponseEntity<Map<String, String>> sendVerifyCode(@RequestParam("email") String email) {
        Map<String, String> result = new HashMap<>();

        try {
            service.sendVerifyCode(email);
            result.put("status", "good");
        } catch (IllegalArgumentException e) {
            result.put("status", "fail");
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "인증번호 전송 중 오류가 발생했습니다.");
        }

        return ResponseEntity.ok(result);
    }
	
	//회원 가입 시 이메일 인증 번호 검증
	@PostMapping("/api/member/confirmVerifyCode")
    public ResponseEntity<Map<String, String>> confirmVerifyCode(
            @RequestParam("email") String email,
            @RequestParam("verifyCode") String verifyCode) {

        Map<String, String> result = new HashMap<>();
        boolean isValid = service.confirmVerifyCode(email, verifyCode);

        result.put("status", isValid ? "good" : "fail");
        if (!isValid) {
            result.put("message", "인증번호가 일치하지 않거나 만료되었습니다.");
        }

        return ResponseEntity.ok(result);
    }
	
	//사용자 정보 보기
	@GetMapping("/api/board/memberInfo")
	public ResponseEntity<MemberDTO> getMemberInfo(@RequestParam("email") String email) {		
		return ResponseEntity.ok().body(service.memberInfo(email));
	}

	//회원 프로필 이미지 보기
	@GetMapping("/api/member/viewProfile/{email}")
	public ResponseEntity<byte[]> filedownload(
	        @PathVariable(name = "email") String email) throws Exception {

	    String os = System.getProperty("os.name").toLowerCase();
	    String path = os.contains("win")
	            ? "c:\\Repository\\profile\\"
	            : "/var/opt/Repository/profile/";

	    MemberDTO member = service.memberInfo(email);

	    if (member == null || member.getStored_filename() == null || member.getStored_filename().isBlank()) {
	        return ResponseEntity.notFound().build();
	    }

	    File file = new File(path + member.getStored_filename());
	    if (!file.exists()) {
	        return ResponseEntity.notFound().build();
	    }

	    byte[] fileByte = Files.readAllBytes(file.toPath());

	    String filename = member.getStored_filename().toLowerCase();
	    String contentType = "image/jpeg";
	    if (filename.endsWith(".png"))  contentType = "image/png";
	    if (filename.endsWith(".gif"))  contentType = "image/gif";
	    if (filename.endsWith(".webp")) contentType = "image/webp";

	    return ResponseEntity.ok()
	            .header(HttpHeaders.CONTENT_TYPE, contentType)
	            .header(HttpHeaders.CONTENT_DISPOSITION,
	                    "inline; filename=\""
	                    + URLEncoder.encode(member.getOrg_filename(), "UTF-8") + "\"")
	            .body(fileByte);
	}
	
	//회원 패스워드 변경
	@PostMapping("/api/member/modifyMemberPassword")
	public ResponseEntity<String> postMemberPasswordModify(@RequestParam("old_password") String old_password, 
				@RequestParam("new_password") String new_password, @RequestParam("email") String email) throws Exception { 
		
		String json = "";
		//이전 패스워드가 제대로 된 패스워드인지 확인
		if(!pwdEncoder.matches(old_password, service.memberInfo(email).getPassword())) {
			json = "{\"message\":\"PASSWORD_NOT_FOUND\"}";
			return ResponseEntity.ok().body(json); 
		}
		
		//신규 패스워드로 수정
		service.modifyMemberPassword(email,new_password);
		
		//마지막 패스워드 변경일 등록
		service.lastdateUpdate(email, "password");
		service.lastdateUpdate(email, "pwcheck");
		json = "{\"message\":\"good\"}";
		return ResponseEntity.ok().body(json);
	}
	
	//아이디 찾기
	@PostMapping("/api/member/searchID")
	public ResponseEntity<String> postSearchID(MemberDTO member) {
		
		String email = service.SearchID(member) == null?"ID_NOT_FOUND":service.SearchID(member);	
		String json = "{\"message\":\"" + email + "\"}";
		return ResponseEntity.ok().body(json);
	}
	
	//패스워드 임시 발급
	@PostMapping("/api/member/searchPassword")
	public ResponseEntity<String> postSearchPassword(MemberDTO member) throws Exception{
		//아이디 존재 여부 확인
		String json = "";
		if(service.idCheck(member.getEmail()) == 0) {
			json = "{\"status\":\"ID_NOT_FOUND\"}";
			return ResponseEntity.ok().body(json);
		}
		//TELNO 확인
		if(!service.memberInfo(member.getEmail()).getTelno().equals(member.getTelno())) {
			json = "{\"status\":\"TELNO_NOT_FOUND\"}";
			return ResponseEntity.ok().body(json);
		}					
		//임시 패스워드 생성	
		PasswordMaker pMaker = new PasswordMaker();
		String rawTempPW = pMaker.tempPasswordMaker();
		//임시 패스워드로 패스워드 수정
		service.modifyMemberPassword(member.getEmail(),rawTempPW);
		//마지막 패스워드 변경일 등록
		service.lastdateUpdate(member.getEmail(), "password");
		json = "{\"status\":\"good\",\"password\":\"" + rawTempPW + "\"}";
		return ResponseEntity.ok().body(json);
	}

	//로그인 시 패스워드 변경 기한 30일 이후로 연기
	@GetMapping("/api/board/modifyPasswordAfter30")
	public ResponseEntity<?> getModifyPasswordAfter30(@RequestParam("email") String email) throws Exception {		
		service.modifyPasswordAfter30(email);
		service.lastdateUpdate(email, "pwcheck");
		return ResponseEntity.ok().build();
	}
	
	//주소 검색 리스트
	@GetMapping("/api/member/searchAddress")
	public ResponseEntity<Page<AddressEntity>> getAddrSearch(@RequestParam("page") int pageNum,  
			@RequestParam("addrSearch") String addrSearch) {
				
		int postNum = 5; //한 화면에 보여지는 게시물 행의 갯수
		return ResponseEntity.ok().body(service.addrSearch(pageNum, postNum, addrSearch));	
	}
	
	//주소 페이지 리스트 보기
	@GetMapping("/api/member/addressPagelist")
	public ResponseEntity<String> getPageList(@RequestParam("page") int pageNum, @RequestParam("addrSearch") String addrSearch) throws Exception {
		int postNum = 5; //한 화면에 보여지는 게시물 행의 갯수
		int pageListCount = 5; //화면 하단에 보여지는 페이지리스트 내의 페이지 갯수
		
		PageRequest pageRequest = PageRequest.of(pageNum-1, postNum,Sort.by(Direction.ASC,"zipcode"));
		Page<AddressEntity> list = addressRepository.findByRoadContainingOrBuildingContaining(addrSearch, addrSearch, pageRequest);		
		PageUtil page = new PageUtil();
		int totalCount = (int)list.getTotalElements();
		String result = "{\"addressPagelist\":\"" + page.getPageAddress(pageNum, postNum, pageListCount, totalCount, addrSearch) 
			+ "\", \"totalCount\":\"" + totalCount + "\"}";
		return ResponseEntity.ok().body(result);
	}
		
}