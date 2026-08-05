package com.board.config;

import org.apache.logging.log4j.util.OsgiServiceLocator;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer{
	
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		
		/*
		String os = System.getProperty("os.name").toLowerCase();
		String filePath = "";
		
		if(os.contains("win")) {
			filePath = "file:///c:/Repository/profile/";
		} else {
			filePath = "file:///home/xavier/Repository/profile/";
		}
				
		registry.addResourceHandler("/profile/**")
				.addResourceLocations(filePath);
		*/		
	}
	
}
