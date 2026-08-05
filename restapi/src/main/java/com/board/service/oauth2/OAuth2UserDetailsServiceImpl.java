package com.board.service.oauth2;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.board.dto.board.MemberOAuth2DTO;
import com.board.entity.MemberEntity;
import com.board.entity.repository.MemberRepository;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@AllArgsConstructor
@Log4j2
public class OAuth2UserDetailsServiceImpl extends DefaultOAuth2UserService{
	
	private final PasswordEncoder pwdEncoder;
	private final MemberRepository memberRepository;
		
	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
		
		OAuth2User oAuth2User = super.loadUser(userRequest);
		
		String provider = userRequest.getClientRegistration().getRegistrationId();
		String providerId = oAuth2User.getAttribute("sub");
		String email = null;
		
		//구글이나 네이버에서 보내 주는 값--> email을 가져 오는 부
		if(provider.equals("naver")) {
			Map<String,Object> response = oAuth2User.getAttribute("response");
			email = (String) response.get("email");
		} else if(provider.contains("google")){
			email = oAuth2User.getAttribute("email");
		}	
		
		log.info("------------------------ OAuth2 로그인 단계 : Provider 정보 확보({})  ------------------------",provider);
		log.info("------------------------ OAuth2 로그인 단계 : ProviderID 정보 확보({})  ------------------------",providerId);
		log.info("------------------------ OAuth2 로그인 단계 : email 정보 확보({})  ------------------------",email);
				
		oAuth2User.getAttributes().forEach((k,v)-> { 
			log.info(k + ":" + v);
		});
		
		//회원 정보를 읽어 들임
		MemberEntity member = saveSocialMember(email,provider);
		
		//Role 값 읽어 들임...
		List<SimpleGrantedAuthority> grantedAuthorities = new ArrayList<>();
		SimpleGrantedAuthority grantedAuthority = new SimpleGrantedAuthority(member.getRole());
		grantedAuthorities.add(grantedAuthority);
		
		log.info("------------------------ OAuth2 로그인 단계 : 인증정보 등록 ------------------------");
		log.info("------------------------ OAuth2 로그인 단계 : username : {} ------------------------", member.getUsername());
		
		MemberOAuth2DTO memberOAuth2DTO = new MemberOAuth2DTO();
		//attributes, authorities, name을 MemberOAuth2DTO에 넣어 줌. 
		memberOAuth2DTO.setAttribute(oAuth2User.getAttributes());
		memberOAuth2DTO.setAuthorities(grantedAuthorities);
		memberOAuth2DTO.setName(email);		
		return memberOAuth2DTO;				
	}
	
	private MemberEntity saveSocialMember(String email,String provider) {
		
		//구글 회원 계정으로 로그인 한 회원의 경우 사이트 운영에 필요한 최소한의 정보를 
		//가공해서 tbl_member에 입력해야 함.
		
		//기존 회원이 SNS 로그인을 할 경우 기존 회원 정보를 리턴함
		Optional<MemberEntity> result = memberRepository.findById(email);
		if(result.isPresent()) {
			return result.get();
		}
		
		MemberEntity member = MemberEntity.builder()
										.email(email)
										.username(provider.toUpperCase() + "회원")
										.password(pwdEncoder.encode("12345"))
										.role("USER")
										.regdate(LocalDateTime.now())
										.lastpwdate(LocalDateTime.now())
										.lastpwcheckdate(LocalDateTime.now())
										.FromSocial("Y")
										.filesize(0L)
										.build();
		memberRepository.save(member);
		
		return member;
	}

}
