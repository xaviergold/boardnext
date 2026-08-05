package com.board.service.board;

import org.springframework.data.domain.Page;

import com.board.dto.board.MemberDTO;
import com.board.entity.AddressEntity;
import com.board.entity.MemberEntity;

public interface MemberService {

	//아이디 중복 체크. 카운터가 0이면 아이디 사용 가능, 1이면 기존 사용 중인 아이디
	public int idCheck(String email);
	
	//사용자 정보 보기
	public MemberDTO memberInfo(String email);
	
	//사용자등록
	public void signup(MemberDTO member);
	
	//회원 가입 시 인증 번호 전송
	public void sendVerifyCode(String email);
	
	//인증번호 확인
	public boolean confirmVerifyCode(String email, String verifyCodeString);
	
	//사용자 기본 정보 수정
	public void modifyMemberInfo(MemberDTO member);
	
	//사용자 패스워드 수정
	public void modifyMemberPassword(String email,String Password);
	
	//마지막 로그인/로그아웃/패스워드 변경 날짜 등록 하기
	public void lastdateUpdate(String email, String status);
	
	//회원 로그인/로그아웃 로그 등록
	public void memberLogRegistry(String email, String statu);
	
	//사용자 자동 로그인을 위한 authkey 등록
	public void authkeyUpdate(MemberDTO member);
	
	//사용자 자동 로그인을 위한 authkey로 사용자 정보 가져 오기 
	public MemberEntity memberInfoByAuthkey(String authkey);
	
	//로그인 시 패스워드 변경 기한 30일 이후로 연기
	public void modifyPasswordAfter30(String email);
	
	//아이디 찾기
	public String SearchID(MemberDTO member);
	
	//주소 검색
	public Page<AddressEntity> addrSearch(int pageNum, int postNum, String addrSearch);
	
	
}
