package com.nhatnam.server;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;

@SpringBootApplication
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@RequiredArgsConstructor
@EnableScheduling
@Log4j2
public class NhatNamApplication {
	public static void main(String[] args) {
		SpringApplication.run(NhatNamApplication.class, args);
	}

	@Scheduled(cron = "0 */10 * * * *")
	@Transactional
	public void handleDepositJob() {
//		log.info("[CRON_EVERY_MINUTE] Starting job at {}", LocalDateTime.now());
	}


	@Scheduled(cron = "0 0 7 * * *", zone = "GMT+7")
	@Transactional
	public void runAt7amHanoiTime() {
//		log.info("[CRON_AT_7:00_HAN] Starting job at {}", LocalDateTime.now());
	}
}
