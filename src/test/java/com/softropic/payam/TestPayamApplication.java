package com.softropic.payam;

import com.softropic.payam.config.TestConfig;

import org.springframework.boot.SpringApplication;

public class TestPayamApplication {

	public static void main(String[] args) {
		SpringApplication.from(PayamApplication::main).with(TestConfig.class).run(args);
	}

}
