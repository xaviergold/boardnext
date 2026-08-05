package com.board.config;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
//application.yml 사용시 주석 처리
//Spring Boot는 src/main/resources/application.yml 파일을 자동으로 로드
//@PropertySource("classpath:/application.properties")
public class HikariCPConfig {
	
	@Bean	
	@ConfigurationProperties(prefix="spring.datasource.hikari")
	HikariConfig hikariConfig() {
		return new HikariConfig();
	}
	
	@Bean
	@Primary 
	//현재 DataSource를 Oracle과 PostGreSQL/PGVector가 같이 사용 중임.  
	//@Primary로 Oracle에게 우선 순위를 줌
	DataSource dataSource() {
		return new HikariDataSource(hikariConfig());
	}

}
