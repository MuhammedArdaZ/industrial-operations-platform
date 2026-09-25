package com.industrialoperations.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IndustrialOperationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndustrialOperationsApplication.class, args);
    }
}
