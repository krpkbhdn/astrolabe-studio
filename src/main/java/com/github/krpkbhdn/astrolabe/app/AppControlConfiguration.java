package com.github.krpkbhdn.astrolabe.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppControlProperties.class)
class AppControlConfiguration {}
