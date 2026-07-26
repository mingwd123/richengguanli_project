package com.dayliane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DaylianeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DaylianeApplication.class, args);
    }
}