import assert from "node:assert/strict";
import test from "node:test";

import {
    applySessionReplayFanout,
    buildRumBeaconUrl,
    buildSessionReplayBeaconUrl,
    buildSessionReplayEndpoint,
    resolveRumDestinations
} from "../splunk-rum-destinations.mjs";

test("resolveRumDestinations keeps primary and secondary browser RUM destinations", () => {
    const destinations = resolveRumDestinations(
        {
            realm: "us1",
            rumAccessToken: "primary-browser-token",
            applicationName: "streaming-app-frontend",
            deploymentEnvironment: "streaming-app",
            version: "demo-build",
            additionalDestinations: [
                {
                    name: "rc0",
                    realm: "rc0",
                    rumAccessToken: "secondary-browser-token"
                }
            ]
        },
        {
            environment: "fallback-env",
            buildVersion: "fallback-build"
        }
    );

    assert.equal(destinations.length, 2);
    assert.deepEqual(destinations.map((destination) => destination.name), ["primary", "rc0"]);
    assert.equal(destinations[0].realm, "us1");
    assert.equal(destinations[0].rumAccessToken, "primary-browser-token");
    assert.equal(destinations[1].realm, "rc0");
    assert.equal(destinations[1].rumAccessToken, "secondary-browser-token");
    assert.equal(destinations[1].applicationName, "streaming-app-frontend");
    assert.equal(destinations[1].deploymentEnvironment, "streaming-app");
    assert.equal(destinations[1].version, "demo-build");
});

test("resolveRumDestinations allows secondary-only browser RUM", () => {
    const destinations = resolveRumDestinations(
        {
            applicationName: "streaming-app-frontend",
            additionalDestinations: [
                {
                    realm: "rc0",
                    rumAccessToken: "secondary-browser-token"
                }
            ]
        },
        {
            environment: "streaming-app",
            buildVersion: "demo-build"
        }
    );

    assert.equal(destinations.length, 1);
    assert.equal(destinations[0].name, "destination-1");
    assert.equal(destinations[0].realm, "rc0");
    assert.equal(destinations[0].rumAccessToken, "secondary-browser-token");
    assert.equal(destinations[0].deploymentEnvironment, "streaming-app");
    assert.equal(destinations[0].version, "demo-build");
});

test("RUM and session replay URLs append auth and derive replay endpoints", () => {
    assert.equal(
        buildRumBeaconUrl({
            realm: "rc0",
            rumAccessToken: "secondary/browser token"
        }),
        "https://rum-ingest.rc0.signalfx.com/v1/rum?auth=secondary%2Fbrowser%20token"
    );
    assert.equal(
        buildRumBeaconUrl({
            beaconEndpoint: "https://external-rum.example.test/v1/rum?debug=true",
            rumAccessToken: "secondary-browser-token"
        }),
        "https://external-rum.example.test/v1/rum?debug=true&auth=secondary-browser-token"
    );
    assert.equal(
        buildSessionReplayEndpoint({
            beaconEndpoint: "https://external-rum.example.test/v1/rum?debug=true"
        }),
        "https://external-rum.example.test/v1/rumreplay?debug=true"
    );
    assert.equal(
        buildSessionReplayBeaconUrl({
            sessionReplayBeaconEndpoint: "https://external-rum.example.test/custom/replay",
            rumAccessToken: "secondary-browser-token"
        }),
        "https://external-rum.example.test/custom/replay?auth=secondary-browser-token"
    );
});

test("applySessionReplayFanout duplicates replay logs to secondary destinations without shared retry queue", async () => {
    const processorCalls = [];
    const emitted = [];
    const flushed = [];
    const recorder = {
        _getProcessorForSession(args) {
            const callIndex = processorCalls.length;
            processorCalls.push(args);

            return {
                forceFlush() {
                    flushed.push(callIndex);
                    return Promise.resolve();
                },
                onEmit(log) {
                    emitted.push({ callIndex, log });
                }
            };
        }
    };

    applySessionReplayFanout(
        recorder,
        [
            {
                realm: "rc0",
                rumAccessToken: "secondary-browser-token"
            }
        ],
        "__TEST_SESSION_REPLAY_FANOUT__"
    );

    const processor = recorder._getProcessorForSession({
        attributes: { app: "streaming-app-frontend" },
        exportQueuedLogs: true,
        exportUrl: "https://rum-ingest.us1.signalfx.com/v1/rumreplay?auth=primary-browser-token",
        persistFailedReplayData: true,
        sessionId: "session-1"
    });

    processor.onEmit({ body: "rrweb-event" });
    await processor.forceFlush();

    assert.equal(processorCalls.length, 2);
    assert.equal(processorCalls[0].exportQueuedLogs, true);
    assert.equal(processorCalls[0].persistFailedReplayData, true);
    assert.equal(
        processorCalls[1].exportUrl,
        "https://rum-ingest.rc0.signalfx.com/v1/rumreplay?auth=secondary-browser-token"
    );
    assert.equal(processorCalls[1].exportQueuedLogs, false);
    assert.equal(processorCalls[1].persistFailedReplayData, false);
    assert.deepEqual(emitted.map((entry) => entry.callIndex), [0, 1]);
    assert.deepEqual(flushed, [0, 1]);
});
