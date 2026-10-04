package com.progresstracker.progressworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProgressWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProgressWorkerApplication.class, args);
    }
}
