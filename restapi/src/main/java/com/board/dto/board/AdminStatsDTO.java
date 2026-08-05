package com.board.dto.board;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class AdminStatsDTO {
	//1. 유저별 활동 종합 지수 DTO
    @Getter 
    @AllArgsConstructor
    public static class UserActivity {
        private String email;
        private String username;
        private Long boardCount;
        private Long replyCount;
        private Long realScore;
        private Double convertedScore; // 100점 만점 환산 점수
    }

    //2. 시간대별 게시물 작성개수 DTO
    @Getter 
    @AllArgsConstructor
    public static class HourlyStats {
        private String hour;
        private Long boardCount;
    }

    //3. 게시물 명예의 전당 TOP 5 DTO
    @Getter 
    @AllArgsConstructor
    public static class TopBoard {
        private Long seqno;
        private String title;
        private String writer;
        private Integer likecnt;
        private Integer hitno;
    }

    //4. 회원 명예의 전당 DTO
    @Getter 
    @AllArgsConstructor
    public static class TopMember {
        private String email;
        private String username;
        private Long totalLikes;
        private Long rank;
    }

    //대시보드 통합 응답 DTO
    @Getter 
    @AllArgsConstructor
    public static class DashboardResponse {
        private List<UserActivity> userActivities;
        private List<HourlyStats> hourlyStats;
        private List<TopBoard> topBoards;
        private List<TopMember> topMembers;
    }
}
