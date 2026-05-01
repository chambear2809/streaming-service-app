package io.github.marianciuc.streamingservice.user.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoUserApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DemoUserApplication.class);
        application.setAdditionalProfiles("broadcast-demo");
        application.run(args);
    }
}
