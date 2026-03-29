#!/usr/bin/env node

const fs = require("fs");

const TEXT_DECODER = new TextDecoder("utf-8");
const SIGNALFLOW_CHANNEL = "R0";
const SIGNALFLOW_RESOLUTION_MS = 60000;
const SIGNALFLOW_TIMEOUT_MS = 20000;

function readInput() {
  const raw = fs.readFileSync(0, "utf8").trim();
  if (!raw) {
    throw new Error("No validation payload was provided on stdin.");
  }
  return JSON.parse(raw);
}

function signalflowProgram(metric, accountGroupId, testId) {
  return (
    `data('${metric}', filter=filter('thousandeyes.account.id', '${accountGroupId}') ` +
    `and filter('thousandeyes.test.id', '${testId}')).publish(label='dashboard_validation')`
  );
}

function binaryFrameMetadata(buffer) {
  const view = new DataView(buffer);
  const leadingVersion = view.getUint8(0);
  if (leadingVersion === 1) {
    return {
      version: 1,
      typeCode: view.getUint8(1),
      flags: view.getUint8(2),
    };
  }
  return {
    version: view.getUint16(0),
    typeCode: view.getUint8(2),
    flags: view.getUint8(3),
  };
}

function parseBinaryDataCount(buffer) {
  const header = binaryFrameMetadata(buffer);
  if (header.flags & 1) {
    throw new Error("Compressed SignalFlow frames are not supported by the local validator.");
  }
  if (header.typeCode !== 5) {
    return null;
  }
  const view = new DataView(buffer);
  const countOffset = header.version === 1 ? 28 : header.version === 512 ? 36 : null;
  if (countOffset === null) {
    throw new Error(`Unsupported SignalFlow data frame version ${header.version}.`);
  }
  return view.getUint32(countOffset);
}

function authFailure(reason) {
  const error = new Error(reason);
  error.code = "AUTH_ERROR";
  return error;
}

function validateOne(token, realm, accountGroupId, validationWindowHours, validation) {
  return new Promise((resolve, reject) => {
    const endMs = Date.now();
    const startMs = endMs - (validationWindowHours * 60 * 60 * 1000);
    const ws = new WebSocket(`wss://stream.${realm}.signalfx.com/v2/signalflow/connect`);
    ws.binaryType = "arraybuffer";

    let finished = false;
    let authenticated = false;
    let hasData = false;
    let seriesCount = 0;
    let noTs = false;

    const timeout = setTimeout(() => {
      finishError(new Error(`Timed out waiting for SignalFlow data for ${validation.metric} (${validation.testId}).`));
    }, SIGNALFLOW_TIMEOUT_MS);

    function cleanup() {
      clearTimeout(timeout);
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
        ws.close();
      }
    }

    function finish(result) {
      if (finished) {
        return;
      }
      finished = true;
      cleanup();
      resolve(result);
    }

    function finishError(error) {
      if (finished) {
        return;
      }
      finished = true;
      cleanup();
      reject(error);
    }

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "authenticate", token }));
    };

    ws.onmessage = (event) => {
      if (typeof event.data === "string") {
        let message;
        try {
          message = JSON.parse(event.data);
        } catch (error) {
          finishError(new Error(`Unable to parse SignalFlow text frame: ${error.message}`));
          return;
        }

        if (message.type === "authenticated") {
          authenticated = true;
          ws.send(
            JSON.stringify({
              type: "execute",
              channel: SIGNALFLOW_CHANNEL,
              program: signalflowProgram(validation.metric, accountGroupId, validation.testId),
              start: startMs,
              stop: endMs,
              resolution: SIGNALFLOW_RESOLUTION_MS,
              immediate: true,
              compress: false,
            })
          );
          return;
        }

        if (message.type === "error") {
          const reason = message.message || message.error || JSON.stringify(message);
          if (String(reason).toLowerCase().includes("authentication")) {
            finishError(authFailure(reason));
          } else {
            finishError(new Error(`SignalFlow error for ${validation.metric} (${validation.testId}): ${reason}`));
          }
          return;
        }

        if (message.type === "message" && message.message) {
          const code = message.message.messageCode;
          if (code === "FIND_MATCHED_NO_TIMESERIES") {
            noTs = true;
          }
          if ((code === "FETCH_NUM_TIMESERIES" || code === "ID_NUM_TIMESERIES") && typeof message.message.numInputTimeSeries === "number") {
            seriesCount = Math.max(seriesCount, message.message.numInputTimeSeries);
          }
          return;
        }

        if (message.type === "data" && Array.isArray(message.data) && message.data.length > 0) {
          hasData = true;
          seriesCount = Math.max(seriesCount, message.data.length);
          return;
        }

        if (message.type === "control-message" && message.event === "END_OF_CHANNEL") {
          finish({
            metric: validation.metric,
            testName: validation.testName,
            testId: validation.testId,
            hasData,
            noTs,
            seriesCount,
          });
        }
        return;
      }

      if (!(event.data instanceof ArrayBuffer)) {
        finishError(new Error(`Unsupported SignalFlow frame type ${typeof event.data}.`));
        return;
      }

      try {
        const count = parseBinaryDataCount(event.data);
        if (typeof count === "number" && count > 0) {
          hasData = true;
          seriesCount = Math.max(seriesCount, count);
        }
      } catch (error) {
        finishError(error);
      }
    };

    ws.onerror = () => {
      // The close event carries the useful auth details.
    };

    ws.onclose = (event) => {
      if (finished) {
        return;
      }
      const reason = event.reason || `SignalFlow websocket closed with code ${event.code}.`;
      if (event.code === 4401 || reason.toLowerCase().includes("authentication")) {
        finishError(authFailure(reason));
        return;
      }
      if (!authenticated) {
        finishError(authFailure(reason));
        return;
      }
      finishError(new Error(reason));
    };
  });
}

async function main() {
  const payload = readInput();
  const validations = Array.isArray(payload.validations) ? payload.validations : [];
  const results = [];
  for (const validation of validations) {
    results.push(
      await validateOne(
        payload.token,
        payload.realm,
        payload.accountGroupId,
        Number(payload.validationWindowHours || 24),
        validation
      )
    );
  }
  process.stdout.write(`${JSON.stringify(results)}\n`);
}

main().catch((error) => {
  if (error && error.code === "AUTH_ERROR") {
    process.stderr.write(`AUTH_ERROR:${error.message}\n`);
    process.exit(2);
  }
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
