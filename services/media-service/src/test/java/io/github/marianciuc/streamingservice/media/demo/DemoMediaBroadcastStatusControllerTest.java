package io.github.marianciuc.streamingservice.media.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoMediaBroadcastStatusControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StubAdService adService;

    @AfterEach
    void tearDown() {
        if (adService != null) {
            adService.close();
        }
    }

    @Test
    void serializesDemoLoopBroadcastStatusFromTheRealAdStatusPath() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setCurrentResponse(adServiceCurrentPayload(
                "READY",
                "ARMED",
                "Sponsor pod A",
                "Override Sponsor",
                "Delayed stitched pod",
                baseNow.plusSeconds(60).toString(),
                baseNow.plusSeconds(75).toString(),
                "Regional sports launch pod is armed for the next sponsor pod inside the house loop.",
                "Issue summary from ad service.",
                true,
                false
        ));

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(30));

        mockMvc.perform(get("/api/v1/demo/public/broadcast/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelLabel").value("Acme Network East"))
                .andExpect(jsonPath("$.status").value("DEMO_LOOP"))
                .andExpect(jsonPath("$.title").value("Acme House Lineup"))
                .andExpect(jsonPath("$.sourceType").value("DEMO_LIBRARY"))
                .andExpect(jsonPath("$.publicPlaybackUrl").value("/api/v1/demo/public/broadcast/live/index.m3u8?v=1767225630000"))
                .andExpect(jsonPath("$.operatorPlaybackUrl").value("/api/v1/demo/media/movie.mp4"))
                .andExpect(jsonPath("$.publicPageUrl").value("/broadcast"))
                .andExpect(jsonPath("$.adStatus.state").value("ARMED"))
                .andExpect(jsonPath("$.adStatus.podLabel").value("Sponsor pod A"))
                .andExpect(jsonPath("$.adStatus.sponsorLabel").value("Override Sponsor"))
                .andExpect(jsonPath("$.adStatus.decisioningMode").value("Delayed stitched pod"))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("Issue summary from ad service.")))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("Ad decisioning delay is active for this break.")));
    }

    @Test
    void fallsBackToDemoLoopSchedulingWhenTheAdServiceCurrentRouteFails() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setCurrentStatus(503);

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(30));

        mockMvc.perform(get("/api/v1/demo/public/broadcast/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEMO_LOOP"))
                .andExpect(jsonPath("$.adStatus.state").value("ARMED"))
                .andExpect(jsonPath("$.adStatus.podLabel").value("Sponsor pod A"))
                .andExpect(jsonPath("$.adStatus.sponsorLabel").value("North Coast Sports Network"))
                .andExpect(jsonPath("$.adStatus.decisioningMode").value("Fallback stitched pod"))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("armed for the next sponsor pod inside the house loop")))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("Ad service fallback is active: Ad service returned HTTP 503")));
    }

    @Test
    void fallsBackToDemoLoopSchedulingWhenTheAdServiceCurrentRouteRedirects() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setCurrentStatus(302);

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(30));

        mockMvc.perform(get("/api/v1/demo/public/broadcast/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEMO_LOOP"))
                .andExpect(jsonPath("$.adStatus.state").value("ARMED"))
                .andExpect(jsonPath("$.adStatus.decisioningMode").value("Fallback stitched pod"))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("Ad service fallback is active: Ad service returned HTTP 302")));
    }

    @Test
    void serializesOnAirBroadcastStatusUsingAdServicePayloadWhenAvailable() throws Exception {
        adService = new StubAdService();
        adService.setCurrentResponse(adServiceCurrentPayload(
                "READY",
                "IN_BREAK",
                "Sponsor pod B",
                "Live Sponsor",
                "Live decisioning mode",
                "2026-01-01T00:03:15Z",
                "2026-01-01T00:03:30Z",
                "Live contribution sponsor pod is active right now.",
                "Issue summary from ad service.",
                false,
                false
        ));

        MockMvc mockMvc = mockMvcFor(onAirSelection(), Instant.now().minusSeconds(30));

        mockMvc.perform(get("/api/v1/demo/public/broadcast/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_AIR"))
                .andExpect(jsonPath("$.sourceType").value("RTSP_CONTRIBUTION"))
                .andExpect(jsonPath("$.adStatus.state").value("IN_BREAK"))
                .andExpect(jsonPath("$.adStatus.podLabel").value("Sponsor pod B"))
                .andExpect(jsonPath("$.adStatus.sponsorLabel").value("Live Sponsor"))
                .andExpect(jsonPath("$.adStatus.decisioningMode").value("Live decisioning mode"))
                .andExpect(jsonPath("$.adStatus.detail").value("Live contribution sponsor pod is active right now. Issue summary from ad service."));
    }

    @Test
    void fallsBackToLiveContributionSchedulingWhenTheAdServiceCurrentRouteFails() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setCurrentStatus(503);

        MockMvc mockMvc = mockMvcFor(onAirSelection(), baseNow.minusSeconds(30));

        mockMvc.perform(get("/api/v1/demo/public/broadcast/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_AIR"))
                .andExpect(jsonPath("$.adStatus.state").value("ARMED"))
                .andExpect(jsonPath("$.adStatus.podLabel").value("Sponsor pod A"))
                .andExpect(jsonPath("$.adStatus.decisioningMode").value("Fallback live splice"))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("live contribution feed")))
                .andExpect(jsonPath("$.adStatus.detail").value(org.hamcrest.Matchers.containsString("Ad service fallback is active: Ad service returned HTTP 503")));
    }

    @Test
    void updateDemoMonkeyUsesTheAdServiceBreakEndForNextBreakOnlyAutoClear() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        String breakEndAt = baseNow.plusSeconds(45).toString();
        adService.setCurrentResponse(adServiceCurrentPayload(
                "READY",
                "ARMED",
                "Sponsor pod A",
                "North Coast Sports Network",
                "Server-side stitched pod",
                baseNow.plusSeconds(30).toString(),
                breakEndAt,
                "Regional sports launch pod is armed for the next sponsor pod inside the house loop.",
                "",
                false,
                false
        ));

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "slow decisioning",
                                  "slowAdEnabled": true,
                                  "nextBreakOnlyEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("slow-decisioning"))
                .andExpect(jsonPath("$.slowAdEnabled").value(true))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(true))
                .andExpect(jsonPath("$.autoClearAt").value(breakEndAt));

        JsonNode issueRequest = latestIssueRequest();
        assertEquals(true, issueRequest.get("enabled").asBoolean());
        assertEquals("slow-decisioning", issueRequest.get("preset").asText());
        assertEquals(3000, issueRequest.get("responseDelayMs").asInt());
        assertEquals(false, issueRequest.get("adLoadFailureEnabled").asBoolean());
    }

    @Test
    void updateDemoMonkeyFallsBackToTheLocalScheduleWhenTheAdServiceCurrentRouteFails() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setCurrentStatus(503);
        Instant cycleOrigin = baseNow.minusSeconds(10);
        Instant expectedBreakEnd = cycleOrigin.plusSeconds(105);

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), cycleOrigin);

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "custom",
                                  "nextBreakOnlyEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(true))
                .andExpect(jsonPath("$.autoClearAt").value(expectedBreakEnd.toString()))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("auto-clears after the next sponsor pod")));

        JsonNode issueRequest = latestIssueRequest();
        assertFalse(issueRequest.get("enabled").asBoolean());
        assertEquals("custom", issueRequest.get("preset").asText());
        assertEquals(0, issueRequest.get("responseDelayMs").asInt());
        assertEquals(false, issueRequest.get("adLoadFailureEnabled").asBoolean());
    }

    @Test
    void updateDemoMonkeyPersistsNormalizedFaultConfigurationAndSummary() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "Chaos Mode_v2",
                                  "startupDelayMs": 1200,
                                  "throttleKbps": 256,
                                  "disconnectAfterKb": 512,
                                  "packetLossPercent": 25,
                                  "playbackFailureEnabled": true,
                                  "traceMapFailureEnabled": true,
                                  "dependencyTimeoutEnabled": true,
                                  "dependencyTimeoutService": " Billing-Service ",
                                  "dependencyFailureEnabled": true,
                                  "dependencyFailureService": "Ad-Service-Demo",
                                  "frontendExceptionEnabled": true,
                                  "slowAdEnabled": true,
                                  "adLoadFailureEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("chaos-mode-v2"))
                .andExpect(jsonPath("$.startupDelayMs").value(1200))
                .andExpect(jsonPath("$.throttleKbps").value(256))
                .andExpect(jsonPath("$.disconnectAfterKb").value(512))
                .andExpect(jsonPath("$.packetLossPercent").value(25))
                .andExpect(jsonPath("$.playbackFailureEnabled").value(true))
                .andExpect(jsonPath("$.traceMapFailureEnabled").value(true))
                .andExpect(jsonPath("$.dependencyTimeoutEnabled").value(true))
                .andExpect(jsonPath("$.dependencyTimeoutService").value("billing-service"))
                .andExpect(jsonPath("$.dependencyFailureEnabled").value(true))
                .andExpect(jsonPath("$.dependencyFailureService").value("ad-service-demo"))
                .andExpect(jsonPath("$.frontendExceptionEnabled").value(true))
                .andExpect(jsonPath("$.slowAdEnabled").value(true))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(true))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(false))
                .andExpect(jsonPath("$.autoClearAt").doesNotExist())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("1200 ms startup lag")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("256 kbps bandwidth clamp")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("connection reset after 512 KiB")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("25% of playback transfers drop before completion")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("playback responses return HTTP 503")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("trace pivot returns HTTP 503")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("Billing service times out in the trace pivot")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("Ad service returns HTTP 503 in the trace pivot")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("browser exception fires on page load")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("ad decisioning is delayed")))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("ad loads fail before the sponsor clip plays")))
                .andExpect(jsonPath("$.affectedPaths").isArray());

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("chaos-mode-v2"))
                .andExpect(jsonPath("$.dependencyTimeoutService").value("billing-service"))
                .andExpect(jsonPath("$.dependencyFailureService").value("ad-service-demo"));

        JsonNode issueRequest = latestIssueRequest();
        assertEquals(true, issueRequest.get("enabled").asBoolean());
        assertEquals("chaos-mode-v2", issueRequest.get("preset").asText());
        assertEquals(3000, issueRequest.get("responseDelayMs").asInt());
        assertEquals(true, issueRequest.get("adLoadFailureEnabled").asBoolean());
    }

    @Test
    void updateDemoMonkeyReportsArmedStateWhenNoFaultsAreConfigured() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("custom"))
                .andExpect(jsonPath("$.summary").value("Demo Monkey is armed, but no faults are currently configured."));

        JsonNode issueRequest = latestIssueRequest();
        assertFalse(issueRequest.get("enabled").asBoolean());
        assertEquals("custom", issueRequest.get("preset").asText());
    }

    @Test
    void updateDemoMonkeyClearsDependentFieldsWhenDisabled() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": false,
                                  "startupDelayMs": 1200,
                                  "throttleKbps": 256,
                                  "disconnectAfterKb": 512,
                                  "packetLossPercent": 25,
                                  "playbackFailureEnabled": true,
                                  "traceMapFailureEnabled": true,
                                  "dependencyTimeoutEnabled": true,
                                  "dependencyTimeoutService": "billing-service",
                                  "dependencyFailureEnabled": true,
                                  "dependencyFailureService": "ad-service-demo",
                                  "frontendExceptionEnabled": true,
                                  "slowAdEnabled": true,
                                  "adLoadFailureEnabled": true,
                                  "nextBreakOnlyEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"))
                .andExpect(jsonPath("$.startupDelayMs").value(0))
                .andExpect(jsonPath("$.throttleKbps").value(0))
                .andExpect(jsonPath("$.disconnectAfterKb").value(0))
                .andExpect(jsonPath("$.packetLossPercent").value(0))
                .andExpect(jsonPath("$.playbackFailureEnabled").value(false))
                .andExpect(jsonPath("$.traceMapFailureEnabled").value(false))
                .andExpect(jsonPath("$.dependencyTimeoutEnabled").value(false))
                .andExpect(jsonPath("$.dependencyTimeoutService").value(""))
                .andExpect(jsonPath("$.dependencyFailureEnabled").value(false))
                .andExpect(jsonPath("$.dependencyFailureService").value(""))
                .andExpect(jsonPath("$.frontendExceptionEnabled").value(false))
                .andExpect(jsonPath("$.slowAdEnabled").value(false))
                .andExpect(jsonPath("$.adLoadFailureEnabled").value(false))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(false))
                .andExpect(jsonPath("$.autoClearAt").doesNotExist())
                .andExpect(jsonPath("$.summary").value("Demo Monkey is bypassed. Playback, ad insertion, and broadcast traffic are flowing normally."));

        JsonNode issueRequest = latestIssueRequest();
        assertFalse(issueRequest.get("enabled").asBoolean());
        assertEquals("clear", issueRequest.get("preset").asText());
        assertEquals(0, issueRequest.get("responseDelayMs").asInt());
        assertFalse(issueRequest.get("adLoadFailureEnabled").asBoolean());
    }

    @Test
    void demoMonkeyAutoClearsExpiredNextBreakOnlyStateAndSynchronizesTheClearedAdFlags() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        String expiredBreakEndAt = baseNow.minusSeconds(1).toString();
        adService.setCurrentResponse(adServiceCurrentPayload(
                "READY",
                "ARMED",
                "Sponsor pod A",
                "North Coast Sports Network",
                "Server-side stitched pod",
                baseNow.minusSeconds(16).toString(),
                expiredBreakEndAt,
                "Regional sports launch pod is armed for the next sponsor pod inside the house loop.",
                "",
                false,
                false
        ));

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "slow decisioning",
                                  "slowAdEnabled": true,
                                  "nextBreakOnlyEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.autoClearAt").value(expiredBreakEndAt));

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(false))
                .andExpect(jsonPath("$.summary").value("Demo Monkey is bypassed. Playback, ad insertion, and broadcast traffic are flowing normally."));

        assertEquals(2, adService.issueRequests().size());
        JsonNode clearedIssueRequest = objectMapper.readTree(adService.issueRequests().get(1));
        assertFalse(clearedIssueRequest.get("enabled").asBoolean());
        assertEquals("clear", clearedIssueRequest.get("preset").asText());
        assertEquals(0, clearedIssueRequest.get("responseDelayMs").asInt());
        assertFalse(clearedIssueRequest.get("adLoadFailureEnabled").asBoolean());
    }

    @Test
    void demoMonkeyAutoClearRetriesUntilTheClearedAdFlagsSynchronize() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        String expiredBreakEndAt = baseNow.minusSeconds(1).toString();
        adService.setCurrentResponse(adServiceCurrentPayload(
                "READY",
                "ARMED",
                "Sponsor pod A",
                "North Coast Sports Network",
                "Server-side stitched pod",
                baseNow.minusSeconds(16).toString(),
                expiredBreakEndAt,
                "Regional sports launch pod is armed for the next sponsor pod inside the house loop.",
                "",
                false,
                false
        ));

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "slow decisioning",
                                  "slowAdEnabled": true,
                                  "nextBreakOnlyEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.autoClearAt").value(expiredBreakEndAt));

        adService.setIssueStatus(503);

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.preset").value("slow-decisioning"))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(true))
                .andExpect(jsonPath("$.autoClearAt").value(expiredBreakEndAt));

        adService.setIssueStatus(200);

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"))
                .andExpect(jsonPath("$.nextBreakOnlyEnabled").value(false));

        assertEquals(3, adService.issueRequests().size());
        JsonNode retryClearRequest = objectMapper.readTree(adService.issueRequests().get(1));
        assertFalse(retryClearRequest.get("enabled").asBoolean());
        assertEquals("clear", retryClearRequest.get("preset").asText());
        JsonNode successfulClearRequest = objectMapper.readTree(adService.issueRequests().get(2));
        assertFalse(successfulClearRequest.get("enabled").asBoolean());
        assertEquals("clear", successfulClearRequest.get("preset").asText());
    }

    @Test
    void updateDemoMonkeyReturnsBadGatewayWhenAdIssueSyncFails() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setIssueStatus(503);

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "slow decisioning",
                                  "slowAdEnabled": true
                                }
                                """))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"));
    }

    @Test
    void updateDemoMonkeyReturnsBadGatewayWhenAdIssueSyncRedirects() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        adService.setIssueStatus(302);

        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "preset": "slow decisioning",
                                  "slowAdEnabled": true
                                }
                                """))
                .andExpect(status().isBadGateway());

        mockMvc.perform(get("/api/v1/demo/media/demo-monkey"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.preset").value("clear"));
    }

    @Test
    void updateDemoMonkeyRejectsOutOfRangeNumericValues() throws Exception {
        Instant baseNow = baseNow();
        adService = new StubAdService();
        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow.minusSeconds(10));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "startupDelayMs": 16000
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "packetLossPercent": 101
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateDemoMonkeyRejectsUnknownDependencyServices() throws Exception {
        adService = new StubAdService();
        MockMvc mockMvc = mockMvcFor(demoLoopSelection(), baseNow().minusSeconds(30));

        mockMvc.perform(put("/api/v1/demo/media/demo-monkey")
                        .contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "dependencyTimeoutEnabled": true,
                                  "dependencyTimeoutService": "search-service-demo"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private MockMvc mockMvcFor(DemoMediaController.BroadcastSelection selection, Instant cycleOrigin) {
        TestDemoMediaController controller = new TestDemoMediaController(new InMemoryDemoMediaStateRepository(), selection);
        ReflectionTestUtils.setField(controller, "adServiceCurrentUrl", adService.currentUrl());
        ReflectionTestUtils.setField(controller, "adServiceIssueUrl", adService.issueUrl());
        currentCycleOrigin(controller).set(cycleOrigin);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @SuppressWarnings("unchecked")
    private AtomicReference<Instant> currentCycleOrigin(DemoMediaController controller) {
        AtomicReference<Instant> cycleOrigin = (AtomicReference<Instant>) ReflectionTestUtils.getField(controller, "demoAdCycleOrigin");
        assertNotNull(cycleOrigin);
        return cycleOrigin;
    }

    private JsonNode latestIssueRequest() throws IOException {
        List<String> requests = adService.issueRequests();
        return objectMapper.readTree(requests.get(requests.size() - 1));
    }

    private Instant baseNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private String adServiceCurrentPayload(
            String serviceState,
            String state,
            String podLabel,
            String sponsorLabel,
            String decisioningMode,
            String breakStartAt,
            String breakEndAt,
            String detail,
            String issueSummary,
            boolean slowAdEnabled,
            boolean adLoadFailureEnabled
    ) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("serviceState", serviceState);
        payload.put("state", state);
        payload.put("podLabel", podLabel);
        payload.put("sponsorLabel", sponsorLabel);
        payload.put("decisioningMode", decisioningMode);
        payload.put("breakStartAt", breakStartAt);
        payload.put("breakEndAt", breakEndAt);
        payload.put("detail", detail);
        payload.put("issueSummary", issueSummary);
        payload.put("insertAd", true);
        payload.put("slowAdEnabled", slowAdEnabled);
        payload.put("adLoadFailureEnabled", adLoadFailureEnabled);
        return objectMapper.writeValueAsString(payload);
    }

    private DemoMediaController.BroadcastSelection demoLoopSelection() {
        return new DemoMediaController.BroadcastSelection(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Acme House Lineup",
                "DEMO_LOOP",
                "DEMO_LIBRARY",
                Instant.parse("2026-01-01T00:00:30Z"),
                Path.of("/tmp/demo.mp4"),
                Path.of("/tmp/demo-playlist.txt"),
                null,
                "/api/v1/demo/media/movie.mp4",
                DemoBroadcastAdSchedule.DEFAULT_BROADCAST_DETAIL
        );
    }

    private DemoMediaController.BroadcastSelection onAirSelection() {
        return new DemoMediaController.BroadcastSelection(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "Downtown Remote Feed",
                "ON_AIR",
                "RTSP_CONTRIBUTION",
                Instant.parse("2026-01-01T00:05:00Z"),
                null,
                Path.of("/tmp/live/index.m3u8"),
                "rtsp://demo.acmebroadcasting.local/live",
                "/api/v1/demo/media/rtsp/jobs/22222222-2222-2222-2222-222222222222/playback.mp4",
                "Live contribution feed is active on the external channel."
        );
    }

    private static final class TestDemoMediaController extends DemoMediaController {

        private final BroadcastSelection selection;

        private TestDemoMediaController(DemoMediaStateRepository stateRepository, BroadcastSelection selection) {
            super(stateRepository);
            this.selection = selection;
        }

        @Override
        BroadcastSelection resolveBroadcastSelection() {
            return selection;
        }

        @Override
        synchronized void ensureBroadcastRelayConfigured(BroadcastSelection selection) {
        }
    }

    private static final class InMemoryDemoMediaStateRepository extends DemoMediaStateRepository {

        private final Map<String, String> state = new LinkedHashMap<>();

        private InMemoryDemoMediaStateRepository() {
            super(null);
        }

        @Override
        List<PersistedRtspJob> findAllJobs() {
            return List.of();
        }

        @Override
        Optional<String> loadStateJson(String key) {
            return Optional.ofNullable(state.get(key));
        }

        @Override
        void saveStateJson(String key, String json) {
            state.put(key, json);
        }
    }

    private static final class StubAdService implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<String> currentBody = new AtomicReference<>("{}");
        private final AtomicReference<String> issueBody = new AtomicReference<>("{\"status\":\"ok\"}");
        private final CopyOnWriteArrayList<String> issueRequests = new CopyOnWriteArrayList<>();

        private volatile int currentStatus = 200;
        private volatile int issueStatus = 200;

        private StubAdService() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/demo/ads/current", this::handleCurrent);
            server.createContext("/api/v1/demo/ads/issues", this::handleIssues);
            server.start();
        }

        private void handleCurrent(HttpExchange exchange) throws IOException {
            respond(exchange, currentStatus, currentBody.get());
        }

        private void handleIssues(HttpExchange exchange) throws IOException {
            issueRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, issueStatus, issueBody.get());
        }

        private void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        }

        private void setCurrentResponse(String body) {
            currentStatus = 200;
            currentBody.set(body);
        }

        private void setCurrentStatus(int status) {
            currentStatus = status;
        }

        private void setIssueStatus(int status) {
            issueStatus = status;
        }

        private String currentUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/demo/ads/current";
        }

        private String issueUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/demo/ads/issues";
        }

        private CopyOnWriteArrayList<String> issueRequests() {
            return issueRequests;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
