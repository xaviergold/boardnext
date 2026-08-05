package com.board.util;

import jakarta.servlet.http.HttpServletRequest;

public class DeviceUtils {
	public static String getDeviceName(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        
        // 헤더가 비어있는 경우 예외 처리
        if (userAgent == null || userAgent.isEmpty()) {
            return "기타 장치";
        }
        
        String uaLower = userAgent.toLowerCase();

        // 1. 모바일 및 태블릿 디바이스 판별
        if (uaLower.contains("android")) {
            if (uaLower.contains("mobile")) {
                return "Android 스마트폰";
            } else {
                return "Android 태블릿";
            }
        } else if (uaLower.contains("iphone")) {
            return "iPhone";
        } else if (uaLower.contains("ipad")) {
            return "iPad";
        }
        
        // 2. 스마트 TV 및 셋톱박스 판별 (스마트 TV 브라우저나 앱 환경 대응)
        if (uaLower.contains("smart-tv") || uaLower.contains("smarttv") || uaLower.contains("tizen") || uaLower.contains("webos")) {
            return "스마트 TV";
        }

        // 3. PC 웹 브라우저 판별 (User-Agent 해석 순서가 중요합니다)
        if (uaLower.contains("edg/")) {
            return "Edge - 웹 브라우저";
        } else if (uaLower.contains("whale/")) {
            return "Whale - 웹 브라우저"; // 네이버 웨일 브라우저 대응
        } else if (uaLower.contains("chrome/") && !uaLower.contains("chromium")) {
            return "Chrome - 웹 브라우저";
        } else if (uaLower.contains("safari/") && !uaLower.contains("chrome")) {
            return "Safari - 웹 브라우저";
        } else if (uaLower.contains("firefox/")) {
            return "Firefox - 웹 브라우저";
        }

        // 4. 매칭되는 게 없을 경우 기본 PC 브라우저로 처리
        return "일반 웹 브라우저";
    }
}
