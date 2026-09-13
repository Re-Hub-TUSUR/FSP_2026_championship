package com.example.balloon;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
/** Планировщик нужен для периодической очистки истёкших сессий входа. */
@SpringBootApplication @EnableScheduling public class BalloonApplication { public static void main(String[] args){ SpringApplication.run(BalloonApplication.class,args); } }
