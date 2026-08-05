package com.board.service.security;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.*;

import com.board.dto.board.MemberDTO;
import com.board.entity.MemberEntity;
import com.board.entity.repository.MemberRepository;
import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService{

	private final MemberRepository memberRepository;
	
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		
		//username은 스프링 시큐리티가 필터로 작동하면서 로그인 요청에서 가로채온 userid임.
		MemberEntity memberEntity = memberRepository.findById(username)
						.orElseThrow(()-> new UsernameNotFoundException("아이디가 존재하지 않습니다."));

		return new UserDetailsImpl(memberEntity);
	}

}
