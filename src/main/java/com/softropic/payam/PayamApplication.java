package com.softropic.payam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class PayamApplication {

	public static void main(String[] args) {
		SpringApplication.run(PayamApplication.class, args);
	}

}
