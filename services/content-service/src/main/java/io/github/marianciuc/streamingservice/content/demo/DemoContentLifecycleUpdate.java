package io.github.marianciuc.streamingservice.content.demo;

record DemoContentLifecycleUpdate(
        String lifecycleState,
        String readinessLabel,
        String signalProfile,
        String channelLabel,
        String programmingTrack
) {
}
