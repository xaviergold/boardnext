package com.board.entity.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.board.entity.MemberEntity;

public interface AdminStatsRepository extends JpaRepository<MemberEntity, String>{

	//1. 유저별 활동 종합 지수
    @Query(value = "SELECT m.email, m.username, COALESCE(b.board_cnt, 0) AS board_cnt, COALESCE(r.reply_cnt, 0) AS reply_cnt, " +
            "(COALESCE(b.board_cnt, 0) + COALESCE(r.reply_cnt, 0)) AS real_score, " +
            "CASE WHEN MAX(COALESCE(b.board_cnt, 0) + COALESCE(r.reply_cnt, 0)) OVER() = 0 THEN 0 " +
            "ELSE ROUND(((COALESCE(b.board_cnt, 0) + COALESCE(r.reply_cnt, 0)) / MAX(COALESCE(b.board_cnt, 0) + COALESCE(r.reply_cnt, 0)) OVER()) * 100, 1) END AS converted_score " +
            "FROM jpa_member m " +
            "LEFT JOIN (SELECT email, COUNT(*) AS board_cnt FROM jpa_board GROUP BY email) b ON m.email = b.email " +
            "LEFT JOIN (SELECT email, COUNT(*) AS reply_cnt FROM jpa_reply GROUP BY email) r ON m.email = r.email " +
            "ORDER BY converted_score DESC, m.username ASC FETCH FIRST 5 ROWS ONLY", nativeQuery = true)
    List<Object[]> getUserActivityRaw();

    //2. 시간대별 게시물 작성개수
    @Query(value = "SELECT TO_CHAR(regdate, 'HH24') AS hour, COUNT(*) AS board_cnt " +
            "FROM jpa_board " +
            "GROUP BY TO_CHAR(regdate, 'HH24') " +
            "ORDER BY hour ASC", nativeQuery = true)
    List<Object[]> getHourlyStatsRaw();

    //3. 게시물 명예의 전당 TOP 5
    @Query(value = "SELECT seqno, title, writer, likecnt, hitno " +
            "FROM jpa_board " +
            "ORDER BY likecnt DESC, hitno DESC " +
            "FETCH FIRST 5 ROWS ONLY", nativeQuery = true)
    List<Object[]> getTopBoardsRaw();

    //4. 회원 명예의 전당
    @Query(value = "SELECT m.email, m.username, COALESCE(SUM(b.likecnt), 0) AS total_likes, " +
            "RANK() OVER (ORDER BY COALESCE(SUM(b.likecnt), 0) DESC) AS rank_num " +
            "FROM jpa_member m " +
            "LEFT JOIN jpa_board b ON m.email = b.email " +
            "GROUP BY m.email, m.username " +
            "ORDER BY total_likes DESC, m.username ASC", nativeQuery = true)
    List<Object[]> getTopMembersRaw();
	
}
