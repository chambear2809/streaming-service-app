package io.github.marianciuc.streamingservice.content.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoContentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DemoContentApplication.class);
        application.setAdditionalProfiles("broadcast-demo");
        application.run(args);
    }
}
