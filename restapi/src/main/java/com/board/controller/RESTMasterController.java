package com.board.controller;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.board.dto.board.AdminStatsDTO.DashboardResponse;
import com.board.entity.FileEntity;
import com.board.service.board.AdminStatsServiceImpl;
import com.board.service.board.MasterService;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Controller
@AllArgsConstructor 
@Log4j2
public class RESTMasterController {
	
	private final MasterService service;
	private final StringRedisTemplate redisTemplate;
	private final AdminStatsServiceImpl adminStatsService;
	
	//삭제할 파일갯수 가져 오기
	@GetMapping("/api/master/filedeleteCount")
	public ResponseEntity<Long> getFilemanage() {
		return ResponseEntity.ok().body(service.filedeleteCount());
	}
	
	//파일 삭제(디스크 + DBMS)
	@GetMapping("/api/master/fileDelete")
	public ResponseEntity<List<Map<String,String>>> getFileDelete() throws Exception {
		
		//운영체제에 따라 이미지가 저장될 디렉토리 구조 설정 시작
		String os = System.getProperty("os.name").toLowerCase();
		String path;
		if(os.contains("win"))
			path = "c:\\Repository\\file\\";
		else 
			//path = "/home/xavier/Repository/file";
			path = "/var/opt/Repository/file";
		
		int count = 0;
		
		List<FileEntity> filedeleteList = service.filedeleteList();		
		
		List<Map<String,String>> data = new ArrayList<>();
		
		for(FileEntity f:filedeleteList) {

			//웹브라우저에 삭제할 파일 정보 전송을 위해 파일 정보 저장
			Map<String,String> result = new HashMap<>();
			result.put("count",Integer.toString(count));
			result.put("org_filename", f.getOrg_filename());
			data.add(result);
			count ++;
			
			//파일 삭제
			File file = new File(path + f.getStored_filename());
			file.delete();
			
			//jpa_file 내 파일 정보 삭제
			service.deleteFile(f.getFileseqno());
			
		}
		return ResponseEntity.ok().body(data);
	
	}
	
	//모든 접속 계정의 디바이스 정보 가져 오기
	@GetMapping("/api/master/getAllActiveDevices")
    public ResponseEntity<?> getAllActiveDevices() {
        try {
            List<Map<String, Object>> allDeviceList = new ArrayList<>();
            
            //시스템에 존재하는 모든 회원의 실물 DEVICE_INFO 키를 패턴 검색 (DEVICE_INFO:이메일:UUID)
            Set<String> allDeviceKeys = redisTemplate.keys("DEVICE_INFO:*:*");
            
            if (allDeviceKeys != null) {
                for (String deviceKey : allDeviceKeys) {
                    Map<Object, Object> entries = redisTemplate.opsForHash().entries(deviceKey);
                    
                    if (entries != null && !entries.isEmpty()) {
                        // 키 파싱: "DEVICE_INFO:khw8017@gmail.com:32자리UUID"
                        String[] keyParts = deviceKey.split(":");
                        String email = keyParts[1];
                        String uuid = keyParts[2];
                        
                        Map<String, Object> deviceInfo = new HashMap<>();
                        deviceInfo.put("email", email);          //관리용 이메일 식별자 추가
                        deviceInfo.put("sessionUuid", uuid); 
                        deviceInfo.put("deviceName", entries.get("deviceName"));
                        deviceInfo.put("lastActiveTime", entries.get("lastActiveTime"));
                        deviceInfo.put("clientIp", entries.get("clientIp"));
                        deviceInfo.put("regionInfo", entries.get("regionInfo"));
                        
                        allDeviceList.add(deviceInfo);
                    }
                }
            }
            
            // 최근 활성 시간 기준 최신순 정렬
            allDeviceList.sort((d1, d2) -> ((String) d2.get("lastActiveTime")).compareTo((String) d1.get("lastActiveTime")));
            return ResponseEntity.ok().body(allDeviceList);
            
        } catch (Exception e) {
            log.error("관리자 - 전체 디바이스 목록 조회 중 에러: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"message\":\"SERVER_ERROR\"}");
        }
    }

    //관리자 권한 강제 원격 추방 (핀포인트 소각)
    @PostMapping("/api/master/logout")
    public ResponseEntity<?> adminForceLogout(@RequestParam Map<String, String> params) {
        String email = params.get("email");
        String sessionUuid = params.get("sessionUuid");
        
        if (email == null || sessionUuid == null || sessionUuid.isEmpty() || "undefined".equals(sessionUuid)) {
            return ResponseEntity.badRequest().body("{\"message\":\"MISSING_REQUIRED_PARAMS\"}");
        }
        
        try {
            String userSetKey = "USER_TOKENS:" + email;
            
            //관리자가 선택한 특정 사용자의 토큰 3종 즉시 강제 소각
            redisTemplate.delete("AT:" + email + ":" + sessionUuid);
            redisTemplate.delete("RT:" + email + ":" + sessionUuid);
            redisTemplate.delete("DEVICE_INFO:" + email + ":" + sessionUuid);
            
            //유저 세션 Set 관리 대장에서도 제외
            redisTemplate.opsForSet().remove(userSetKey, sessionUuid);
            
            log.info("[관리자 강제 추방] 사용자: {}, 세션 UUID: {}", email, sessionUuid);
            return ResponseEntity.ok().body("{\"status\":\"good\"}");
            
        } catch (Exception e) {
            log.error("관리자 - 강제 로그아웃 처리 중 에러: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"message\":\"SERVER_ERROR\"}");
        }
    }
    
    //사용자 통계 보기
    @GetMapping("/api/master/userStat")
    public ResponseEntity<DashboardResponse> getDashboardStats() {
        return ResponseEntity.ok(adminStatsService.getDashboardStats());
    }
}
