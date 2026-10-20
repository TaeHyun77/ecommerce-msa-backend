package com.park.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class PromotionServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(PromotionServiceApplication.class, args);
	}
}
