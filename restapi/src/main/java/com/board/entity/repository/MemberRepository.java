package com.board.entity.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.board.entity.MemberEntity;

import jakarta.transaction.Transactional;

public interface MemberRepository extends JpaRepository<MemberEntity,String>{
	public MemberEntity findByAuthkey(String authkey);
	public Optional<MemberEntity> findByUsernameAndTelno(String username, String telno);
		
	//로그인 시 패스워드 변경 기한 30일 이후로 연기
	@Transactional
	@Modifying //테이블에 DML(insert, update, delete)을 실행 시켜 변화를 주었을 경우 테이블에 반영된 내용을 엔티티 클래스에 반영 
	@Query(value="update jpa_member set pwcheck = (select nvl(pwcheck,0) from jpa_member where email= :email) + 1 where email=:email",nativeQuery = true)
	public void modifyPasswordAfter30(@Param("email") String email);
}
