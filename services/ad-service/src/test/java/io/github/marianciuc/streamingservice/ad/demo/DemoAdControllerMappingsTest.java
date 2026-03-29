package io.github.marianciuc.streamingservice.ad.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoAdControllerMappingsTest {

    private final DemoAdStateRepository repository = mock(DemoAdStateRepository.class);
    private Instant now;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        now = Instant.now();
        when(repository.loadIssueState()).thenReturn(new DemoAdController.AdIssueStateRecord(
                false,
                "clear",
                0,
                false,
                now
        ));
        when(repository.loadTimelineState()).thenReturn(new DemoAdController.ProgramTimelineStateRecord(
                now.minusSeconds(30),
                now
        ));

        mockMvc = mockMvcFor(
                new DemoAdController.AdIssueStateRecord(false, "clear", 0, false, now),
                new DemoAdController.ProgramTimelineStateRecord(now.minusSeconds(30), now)
        );
    }

    @Test
    void exposesCurrentAndQueueRoutesUnderExpectedDemoPaths() throws Exception {
        mockMvc.perform(get("/api/v1/demo/ads/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceState").value("READY"))
                .andExpect(jsonPath("$.state").value("ARMED"))
                .andExpect(jsonPath("$.podLabel").value("Sponsor pod A"));

        mockMvc.perform(get("/api/v1/demo/ads/program-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelLabel").value("Acme Network East"))
                .andExpect(jsonPath("$.items[0].title").value("Big Buck Bunny · Part 1"))
                .andExpect(jsonPath("$.items[1].kind").value("AD"));
    }

    @Test
    void exposesHealthIssueAndTimelineRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/demo/ads/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.summary").value("Ad service is healthy. Sponsor clips are inserted about every 90 seconds throughout the house loop without additional delay."))
                .andExpect(jsonPath("$.updatedAt").value(now.toString()));

        mockMvc.perform(get("/api/v1/demo/ads/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"))
                .andExpect(jsonPath("$.summary").value("Ad service is healthy. Sponsor clips are inserted about every 90 seconds throughout the house loop without additional delay."))
                .andExpect(jsonPath("$.affectedPaths[3]").value("/api/v1/demo/public/broadcast/current"));

        mockMvc.perform(get("/api/v1/demo/ads/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleOriginAt").value(now.minusSeconds(30).toString()))
                .andExpect(jsonPath("$.updatedAt").value(now.toString()))
                .andExpect(jsonPath("$.cycleLengthSeconds").value(3383));
    }

    @Test
    void reportsDegradedIssueStateAcrossHealthAndIssueRoutes() throws Exception {
        MockMvc degradedMvc = mockMvcFor(
                new DemoAdController.AdIssueStateRecord(true, "slow-and-failed", 5, true, now),
                new DemoAdController.ProgramTimelineStateRecord(now.minusSeconds(30), now)
        );

        degradedMvc.perform(get("/api/v1/demo/ads/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.summary").value("Ad service issue injection is active with 5 ms decisioning delay and ad clip load failures."));

        degradedMvc.perform(get("/api/v1/demo/ads/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("slow-and-failed"))
                .andExpect(jsonPath("$.responseDelayMs").value(5))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(true))
                .andExpect(jsonPath("$.summary").value("Ad service issue injection is active with 5 ms decisioning delay and ad clip load failures."));
    }

    @Test
    void updatesIssueStateAndClearsDependentFieldsWhenDisabled() throws Exception {
        mockMvc.perform(put("/api/v1/demo/ads/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": false,
                                  "preset": "slow and failed",
                                  "responseDelayMs": 8000,
                                  "adLoadFailureEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"))
                .andExpect(jsonPath("$.responseDelayMs").value(0))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(false));

        verify(repository).saveIssueState(argThat(state ->
                !state.enabled()
                        && "clear".equals(state.preset())
                        && state.responseDelayMs() == 0
                        && !state.adLoadFailureEnabled()
        ));
    }

    @Test
    void derivesIssuePresetsForEnabledRequests() throws Exception {
        mockMvc.perform(put("/api/v1/demo/ads/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "responseDelayMs": 5000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("slow-decisioning"))
                .andExpect(jsonPath("$.responseDelayMs").value(5000))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(false));

        verify(repository).saveIssueState(argThat(state ->
                state.enabled()
                        && "slow-decisioning".equals(state.preset())
                        && state.responseDelayMs() == 5000
                        && !state.adLoadFailureEnabled()
        ));
        clearInvocations(repository);

        mockMvc.perform(put("/api/v1/demo/ads/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "adLoadFailureEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("failed-ads"))
                .andExpect(jsonPath("$.responseDelayMs").value(0))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(true));

        verify(repository).saveIssueState(argThat(state ->
                state.enabled()
                        && "failed-ads".equals(state.preset())
                        && state.responseDelayMs() == 0
                        && state.adLoadFailureEnabled()
        ));
        clearInvocations(repository);

        mockMvc.perform(put("/api/v1/demo/ads/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "responseDelayMs": 5000,
                                  "adLoadFailureEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("slow-and-failed"))
                .andExpect(jsonPath("$.responseDelayMs").value(5000))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(true));

        verify(repository).saveIssueState(argThat(state ->
                state.enabled()
                        && "slow-and-failed".equals(state.preset())
                        && state.responseDelayMs() == 5000
                        && state.adLoadFailureEnabled()
        ));
    }

    @Test
    void updatesTimelineStateWithAnIsoInstant() throws Exception {
        mockMvc.perform(put("/api/v1/demo/ads/timeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cycleOriginAt": "2026-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleOriginAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.cycleLengthSeconds").value(3383));

        verify(repository).saveTimelineState(argThat(state ->
                "2026-01-01T00:00:00Z".equals(state.cycleOriginAt().toString())
        ));
    }

    @Test
    void rejectsInvalidDelayAndTimelineRequests() throws Exception {
        mockMvc.perform(put("/api/v1/demo/ads/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "enabled": true,
                                  "responseDelayMs": 16000
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/demo/ads/timeline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cycleOriginAt": "not-an-instant"
                                }
                """))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvcFor(
            DemoAdController.AdIssueStateRecord issueState,
            DemoAdController.ProgramTimelineStateRecord timelineState
    ) {
        when(repository.loadIssueState()).thenReturn(issueState);
        when(repository.loadTimelineState()).thenReturn(timelineState);
        return MockMvcBuilders.standaloneSetup(new DemoAdController(repository)).build();
    }
}
