package com.gamestock.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class GameStockBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(GameStockBackendApplication.class, args);
    }
}
