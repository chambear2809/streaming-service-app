package io.github.marianciuc.streamingservice.media.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ControllerProfileAnnotationTest {

    @Test
    void nonDemoControllersAreDisabledForBroadcastDemoProfile() {
        assertProfile(io.github.marianciuc.streamingservice.media.controllers.DemoMediaController.class, "!broadcast-demo");
        assertProfile(VideoStreamController.class, "!broadcast-demo");
        assertProfile(RtspController.class, "!broadcast-demo");
    }

    @Test
    void broadcastDemoControllerIsScopedToBroadcastDemoProfile() {
        assertProfile(io.github.marianciuc.streamingservice.media.demo.DemoMediaController.class, "broadcast-demo");
    }

    private static void assertProfile(Class<?> controllerType, String expectedProfile) {
        Profile profile = controllerType.getAnnotation(Profile.class);
        assertNotNull(profile, () -> controllerType.getSimpleName() + " should declare a Spring profile");
        assertArrayEquals(new String[]{expectedProfile}, profile.value());
    }
}
