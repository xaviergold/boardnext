package com.board.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GeoLocationUtils {
	//요청을 보낸 클라이언트의 IP 추출 로직 (프록시/AWS 환경 대응)
    public static String getClientIp(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 로컬 테스트 환경(IPv6) 예외 처리
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            ip = "127.0.0.1";
        }
        
        if(ip.contains(",")) ip = ip.split(",")[0].trim(); // 여러 IP가 섞여 들어올 경우 첫 번째 가독 IP 선택
        
        if(ip.equals("127.0.0.1") || ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.16.")){
        	String ipUrl = "http://checkip.amazonaws.com"; 
        	HttpClient ipClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest ipRequest = HttpRequest.newBuilder().uri(URI.create(ipUrl)).build();
            HttpResponse<String> ipResponse = ipClient.send(ipRequest, HttpResponse.BodyHandlers.ofString());            
            // 공인 IP 주소 세팅 (뒤에 붙는 개행문자(\n) 제거)
            ip = ipResponse.body().trim();
        }
        	 
        return ip;
    }

    //IP 기반 위치(국가/도시) 조회 메서드
    public static String getRegionInfo(String ip) {

        try {
            //무료 GeoIP 오픈 API 활용 (JSON 반환 형식을 한글 언어셋으로 지정)
            String url = "http://ip-api.com/json/" + ip;
        	//외부 API 지연으로 로그인 프로세스가 멈추는 것을 막기 위해 타임아웃(3초)을 명시.            
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 응답 포맷 파싱 (Jackson ObjectMapper나 간단한 String 파싱으로 대체 가능)
            // 아래는 기본적인 문자열 슬라이싱 예시 (별도 JSON 라이브러리 조차 안 쓰는 방식)
            String body = response.body();
         // ip-api.com의 영문 키값 구조에 맞춰 정확하게 파싱
            if (body != null && body.contains("\"status\":\"success\"")) {
                String country = parseJsonKey(body, "country");       
                String regionName = parseJsonKey(body, "regionName"); 
                String city = parseJsonKey(body, "city");             
                
                return country + ", " + regionName + ", " + city;
            }
        } catch (Exception e) {
            // API 장애 시 fallback 처리
        }
        return "위치 정보 알 수 없음";
    }
    // 별도 JSON 라이브러리 의존성 없이 안전하게 값을 추출하는 자바 기본 문자열 파서
    private static String parseJsonKey(String json, String key) {
        try {
            String target = "\"" + key + "\":\"";
            int startIdx = json.indexOf(target) + target.length();
            int endIdx = json.indexOf("\"", startIdx);
            return json.substring(startIdx, endIdx);
        } catch (Exception e) {
            return "";
        }
    }
}
