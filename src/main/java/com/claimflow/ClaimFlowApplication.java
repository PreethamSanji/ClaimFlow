package com.claimflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClaimFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaimFlowApplication.class, args);
    }
}
