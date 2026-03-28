package io.github.marianciuc.streamingservice.media.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoMediaApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DemoMediaApplication.class);
        application.setAdditionalProfiles("broadcast-demo");
        application.run(args);
    }
}
