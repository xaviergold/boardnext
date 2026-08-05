package com.board.service.board;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.board.dto.board.AdminStatsDTO.DashboardResponse;
import com.board.dto.board.AdminStatsDTO.HourlyStats;
import com.board.dto.board.AdminStatsDTO.TopBoard;
import com.board.dto.board.AdminStatsDTO.TopMember;
import com.board.dto.board.AdminStatsDTO.UserActivity;
import com.board.entity.repository.AdminStatsRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminStatsServiceImpl {
	
	private final AdminStatsRepository adminStatsRepository;
	
	public DashboardResponse getDashboardStats() {
		
		// 2. 유저별 활동 데이터 매핑
        List<UserActivity> userActivities = adminStatsRepository.getUserActivityRaw().stream()
                .map(row -> new UserActivity(
                        (String) row[0], (String) row[1],
                        ((Number) row[2]).longValue(), ((Number) row[3]).longValue(),
                        ((Number) row[4]).longValue(), ((Number) row[5]).doubleValue()
                )).collect(Collectors.toList());

        // 3. 시간대별 데이터 매핑
        List<HourlyStats> hourlyStats = adminStatsRepository.getHourlyStatsRaw().stream()
                .map(row -> new HourlyStats((String) row[0], ((Number) row[1]).longValue()))
                .collect(Collectors.toList());

        // 4. 인기 게시글 데이터 매핑
        List<TopBoard> topBoards = adminStatsRepository.getTopBoardsRaw().stream()
                .map(row -> new TopBoard(
                        ((Number) row[0]).longValue(), (String) row[1], (String) row[2],
                        ((Number) row[3]).intValue(), ((Number) row[4]).intValue()
                )).collect(Collectors.toList());

        // 5. 우수 회원 데이터 매핑
        List<TopMember> topMembers = adminStatsRepository.getTopMembersRaw().stream()
                .map(row -> new TopMember(
                        (String) row[0], (String) row[1],
                        ((Number) row[2]).longValue(), ((Number) row[3]).longValue()
                )).collect(Collectors.toList());

        return new DashboardResponse(userActivities, hourlyStats, topBoards, topMembers);
	}

}
