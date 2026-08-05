package com.board.service.board;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.board.dto.board.MemberDTO;
import com.board.entity.AddressEntity;
import com.board.entity.MemberEntity;
import com.board.entity.MemberLogEntity;
import com.board.entity.repository.AddressRepository;
import com.board.entity.repository.MemberLogRepository;
import com.board.entity.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService{

	private final MemberRepository memberRepository;
	private final MemberLogRepository memberLogRepository;
	private final AddressRepository addressRepository;
	private final BCryptPasswordEncoder pwdEncoder; 
	private final JavaMailSender mailSender;
	private final StringRedisTemplate redisTemplate;
	
	private static final String REDIS_KEY_PREFIX = "emailVerify:";
    private static final long EXPIRE_MINUTES = 5;
	
	//아이디 중복 체크. 카운터가 0이면 아이디 사용 가능, 1이면 기존 사용 중인 아이디
	@Override
	public int idCheck(String email) {
		return memberRepository.findById(email).isEmpty()?0:1;
	}
	
	//사용자 정보 가져 오기
	@Override
	public MemberDTO memberInfo(String email) {
		return memberRepository.findById(email)
				.map(member-> new MemberDTO(member))
				.orElseThrow(()->new RuntimeException("사용자가 없습니다."));
	}

	//사용자등록
	@Override
	public void signup(MemberDTO member) {
		member.setRegdate(LocalDateTime.now());
		member.setLastpwdate(LocalDateTime.now());
		member.setLastpwcheckdate(LocalDateTime.now());
		member.setRole("USER");
		memberRepository.save(member.dtoToEntity(member));	
	}
	
	//회원 가입 시 인증 번호 전송
	@Override
	public void sendVerifyCode(String email) {
		if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("이메일을 입력하세요.");
        }
		
		//개발 모드에선 고정 이메일 사용
		email = "khw8017@gmail.com"; 		
		
		//6자리 랜덤 코드 생성
		String verifyCode = String.valueOf(new SecureRandom().nextInt(900000) + 100000);
		
		//Redis에 emailVerify:email 필드 생성
		redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + email,
                verifyCode,
                Duration.ofMinutes(EXPIRE_MINUTES) 
        );
		
		SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("[회원가입] 이메일 인증번호 안내");
        message.setText("요청하신 인증번호는 [" + verifyCode + "] 입니다.\n"
                + EXPIRE_MINUTES + "분 이내에 입력해 주세요.");

        mailSender.send(message);
		
	}
	
	//인증 번호 확인
	@Override
	public boolean confirmVerifyCode(String email, String verifyCode) {
		
		//개발 모드에선 고정 이메일 사용
        email = "khw8017@gmail.com";
		
        String savedCode = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + email);

        if (savedCode == null) {
            return false;
        }

        boolean matched = savedCode.equals(verifyCode);
        
        if (matched) {
            redisTemplate.delete(REDIS_KEY_PREFIX + email);
        }

        return matched;
    }
	//사용자 기본 정보 수정
	public void modifyMemberInfo(MemberDTO member) {
		MemberEntity memberEntity = memberRepository.findById(member.getEmail()).get();
		memberEntity.setUsername(member.getUsername());
		memberEntity.setGender(member.getGender());
		memberEntity.setHobby(member.getHobby());
		memberEntity.setJob(member.getJob());
		memberEntity.setZipcode(member.getZipcode());
		memberEntity.setAddress(member.getAddress());
		memberEntity.setTelno(member.getTelno());
		memberEntity.setNickname(member.getNickname());
		memberEntity.setDescription(member.getDescription());
		memberEntity.setOrg_filename(member.getOrg_filename());
		memberEntity.setStored_filename(member.getStored_filename());
		memberEntity.setFilesize(member.getFilesize());
		memberEntity.setFromSocial(member.getFromSocial());
		memberRepository.save(memberEntity);
	}
	
	//사용자 패스워드 수정
	@Override
	public void modifyMemberPassword(String email,String Password) {
		MemberEntity memberEntity = memberRepository.findById(email).get();
		memberEntity.setPassword(pwdEncoder.encode(Password));
		memberEntity.setPwcheck(0);
		memberRepository.save(memberEntity);
	}
	
	//마지막 로그인/로그아웃/패스워드 변경일 등록 하기
	@Override
	public void lastdateUpdate(String email,String status) {
		//MemberEntity memberEntity = memberRepository.findById(email).get();
		Optional<MemberEntity> memberOpt = memberRepository.findById(email);
		
   
	    // 그래도 없으면 예외 발생
	    MemberEntity memberEntity = memberOpt
	        .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + email));
		
		switch(status) {
			case "login" : memberEntity.setLastlogindate(LocalDateTime.now());
					 	   break;
			case "logout" : memberEntity.setLastlogoutdate(LocalDateTime.now());
			                break;
			case "password" : memberEntity.setLastpwdate(LocalDateTime.now());
			                  break;
			case "pwcheck" : memberEntity.setLastpwcheckdate(LocalDateTime.now());
            				  break;                  
		}		
		memberRepository.save(memberEntity);
	}
	
	//회원 로그인/로그아웃 로그 등록
	@Override
	public void memberLogRegistry(String email, String status) {
		
		MemberEntity memberEntity = memberRepository.findById(email).get();
		
		MemberLogEntity memberLogEntity = MemberLogEntity.builder()
														 .email(memberEntity)
														 .inouttime(LocalDateTime.now())
														 .status(status)
														 .build();		
		memberLogRepository.save(memberLogEntity);
	}
	
	//사용자 자동 로그인을 위한 authkey 등록
	@Override
	public void authkeyUpdate(MemberDTO member) {
		MemberEntity memberEntity = memberRepository.findById(member.getEmail()).get();
		memberEntity.setAuthkey(member.getAuthkey());
		memberRepository.save(memberEntity);
	}
	
	//사용자 자동 로그인을 위한 authkey로 사용자 정보 가져 오기 
	@Override
	public MemberEntity memberInfoByAuthkey(String authkey) {
		return memberRepository.findByAuthkey(authkey);
	}
	
	//로그인 시 패스워드 변경 기한 30일 이후로 연기
	@Override
	public void modifyPasswordAfter30(String email) {
		memberRepository.modifyPasswordAfter30(email);
	}
	
	//아이디 찾기
	@Override
	public String SearchID(MemberDTO member) {
		return memberRepository.findByUsernameAndTelno(member.getUsername(),
				member.getTelno()).map(m-> m.getEmail()).orElse("ID_NOT_FOUND");	 
	}	

	//주소 검색
	@Override
	public Page<AddressEntity> addrSearch(int pageNum, int postNum, String addrSearch){
		PageRequest pageRequest = PageRequest.of(pageNum-1, postNum,Sort.by(Direction.ASC,"zipcode"));		
		return addressRepository.findByRoadContainingOrBuildingContaining(addrSearch, addrSearch, pageRequest);
	}
	
}
