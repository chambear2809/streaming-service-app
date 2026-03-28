package io.github.marianciuc.streamingservice.ad.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoAdApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DemoAdApplication.class);
        application.setAdditionalProfiles("broadcast-demo");
        application.run(args);
    }
}
