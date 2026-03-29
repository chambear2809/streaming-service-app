#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";

const HELP_TEXT = `Operator, billing, and commerce demo load generator

Usage:
  node scripts/loadgen/operator-billing-loadgen.mjs [options]

This script simulates protected operator activity against the demo frontend.
It signs in through the persona shortcut, reads the live catalog and RTSP desk,
exercises the Accounts, Payments, and Commerce APIs behind the protected suite,
records billing business events, and can create and settle orders.

Options:
  --base-url <url>                 Frontend base URL.
  --persona <persona>              Demo persona. Default: operator
  --duration <time>                Total run duration. Default: 8m
  --concurrency <count>            Concurrent operator workers. Default: 3
  --pause <time>                   Delay between operator cycles. Default: 4s
  --request-timeout <time>         Per-request timeout. Default: 15s
  --customer-page-size <count>     Customer directory page size. Default: 24
  --billing-event-ratio <ratio>    Fraction of cycles that book a billing event. Default: 0.55
  --payment-ratio <ratio>          Fraction of cycles that capture payment on an open invoice. Default: 0.20
  --payment-read-ratio <ratio>     Fraction of cycles that read card-holder and transactions. Default: 0.80
  --commerce-read-ratio <ratio>    Fraction of cycles that read plans, active subscription, and orders. Default: 0.85
  --order-create-ratio <ratio>     Fraction of cycles that create an order. Default: 0.35
  --order-settle-ratio <ratio>     Fraction of cycles that move an order to PAID. Default: 0.35
  --order-complete-ratio <ratio>   Fraction of cycles that move a PAID order to COMPLETED. Default: 0.15
  --rtsp-job-ratio <ratio>         Fraction of cycles that create an RTSP job. Default: 0.35
  --take-live-ratio <ratio>        Fraction of cycles that take a job live if one is ready. Default: 0.20
  --help                           Show this help text.

Environment variable equivalents:
  LOADGEN_OPERATOR_BASE_URL
  LOADGEN_BASE_URL
  LOADGEN_OPERATOR_PERSONA
  LOADGEN_OPERATOR_DURATION
  LOADGEN_OPERATOR_CONCURRENCY
  LOADGEN_OPERATOR_PAUSE
  LOADGEN_OPERATOR_REQUEST_TIMEOUT
  LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE
  LOADGEN_OPERATOR_BILLING_EVENT_RATIO
  LOADGEN_OPERATOR_PAYMENT_RATIO
  LOADGEN_OPERATOR_PAYMENT_READ_RATIO
  LOADGEN_OPERATOR_COMMERCE_READ_RATIO
  LOADGEN_OPERATOR_ORDER_CREATE_RATIO
  LOADGEN_OPERATOR_ORDER_SETTLE_RATIO
  LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO
  LOADGEN_OPERATOR_RTSP_JOB_RATIO
  LOADGEN_OPERATOR_TAKE_LIVE_RATIO
`;

const ACTIVE_ORDER_STATUSES = new Set(["CREATED", "SCHEDULED"]);
const COMPLETABLE_ORDER_STATUSES = new Set(["PAID"]);
const ACTIVE_SUBSCRIPTION_STATUS = "ACTIVE";
const RTSP_SOURCE_URL = "rtsp://demo.acmebroadcasting.local/live";

async function main() {
    const config = parseArgs(process.argv.slice(2), process.env);
    if (config.helpRequested) {
        console.log(HELP_TEXT);
        return;
    }

    validateConfig(config);

    const metrics = createMetrics();
    const endsAt = Date.now() + config.durationMs;
    await Promise.all(
        Array.from({ length: config.concurrency }, (_, index) => runWorker(index + 1, config, metrics, endsAt))
    );

    console.log(JSON.stringify(finalizeMetrics(metrics), null, 2));
}

function createMetrics() {
    return {
        sessions: 0,
        cycles: 0,
        customerReads: 0,
        paymentWorkspaceReads: 0,
        commerceWorkspaceReads: 0,
        billingEvents: 0,
        invoicePaymentsCaptured: 0,
        rtspJobs: 0,
        broadcastsActivated: 0,
        ordersCreated: 0,
        ordersSettled: 0,
        ordersCompleted: 0,
        subscriptionActivationsVerified: 0,
        subscriptionCompletionsVerified: 0,
        failures: 0,
        requestStats: {}
    };
}

function finalizeMetrics(metrics) {
    return {
        ...metrics,
        requestStats: Object.fromEntries(
            Object.entries(metrics.requestStats).map(([label, stat]) => [
                label,
                {
                    count: stat.count,
                    failures: stat.failures,
                    avgMs: stat.count ? Number((stat.totalMs / stat.count).toFixed(1)) : 0,
                    maxMs: stat.maxMs,
                    statuses: stat.statuses
                }
            ])
        )
    };
}

async function runWorker(workerId, config, metrics, endsAt) {
    while (Date.now() < endsAt) {
        try {
            const cookie = await login(config.baseUrl, config, metrics);
            metrics.sessions += 1;
            await runCycle(workerId, config, metrics, cookie);
            metrics.cycles += 1;
        } catch (error) {
            metrics.failures += 1;
            console.warn(`[worker ${workerId}] ${error.message}`);
        }

        await sleep(config.pauseMs);
    }
}

async function runCycle(workerId, config, metrics, cookie) {
    const decisions = {
        paymentRead: shouldRun(config.paymentReadRatio),
        commerceRead: shouldRun(config.commerceReadRatio),
        createOrder: shouldRun(config.orderCreateRatio),
        settleOrder: shouldRun(config.orderSettleRatio),
        completeOrder: shouldRun(config.orderCompleteRatio)
    };
    const needsCommerce = decisions.commerceRead || decisions.createOrder || decisions.settleOrder || decisions.completeOrder;

    await requestJson(config.baseUrl, "/api/v1/demo/auth/session", config, metrics, {
        cookie,
        metricLabel: "auth.session"
    });

    const [catalog, rtspJobs, invoices, customers] = await Promise.all([
        requestJson(config.baseUrl, "/api/v1/demo/content", config, metrics, {
            cookie,
            metricLabel: "content.catalog"
        }),
        requestJson(config.baseUrl, "/api/v1/demo/media/rtsp/jobs", config, metrics, {
            cookie,
            metricLabel: "media.rtsp_jobs"
        }),
        requestJson(config.baseUrl, "/api/v1/billing/invoices", config, metrics, {
            cookie,
            metricLabel: "billing.invoices"
        }),
        fetchCustomerDirectory(config.baseUrl, cookie, config, metrics)
    ]);

    metrics.customerReads += 1;

    const customer = pickRandom(customers);
    if (!customer?.id) {
        throw new Error("Customer directory returned no usable customers.");
    }

    if (shouldRun(config.billingEventRatio)) {
        await createBillingEvent(config.baseUrl, cookie, customer.id, catalog, config, metrics);
        metrics.billingEvents += 1;
    }

    if (shouldRun(config.paymentRatio)) {
        const openInvoice = normalizeArray(invoices)
            .find((invoice) => ["OPEN", "PAST_DUE"].includes(String(invoice?.status ?? "").toUpperCase()) && Number(invoice?.balanceDue ?? 0) > 0);
        if (openInvoice) {
            await captureInvoicePayment(config.baseUrl, cookie, openInvoice, config, metrics);
            metrics.invoicePaymentsCaptured += 1;
        }
    }

    if (decisions.paymentRead) {
        await readPaymentWorkspace(config.baseUrl, cookie, customer.id, config, metrics);
        metrics.paymentWorkspaceReads += 1;
    }

    let commerceState = {
        plans: [],
        activeSubscription: null,
        orders: []
    };
    if (needsCommerce) {
        commerceState = await readCommerceWorkspace(config.baseUrl, cookie, customer.id, config, metrics);
        metrics.commerceWorkspaceReads += 1;
    }

    if (shouldRun(config.rtspJobRatio) && Array.isArray(catalog) && catalog.length) {
        await createRtspJob(config.baseUrl, cookie, pickCatalogItem(catalog), config, metrics);
        metrics.rtspJobs += 1;
    }

    if (shouldRun(config.takeLiveRatio)) {
        const activatableJob = normalizeArray(rtspJobs)
            .find((job) => ["CAPTURING", "TRANSCODING", "READY"].includes(String(job?.status ?? "").toUpperCase()));
        if (activatableJob?.jobId) {
            await requestJson(
                config.baseUrl,
                `/api/v1/demo/media/broadcast/jobs/${encodeURIComponent(activatableJob.jobId)}/activate`,
                config,
                metrics,
                {
                    cookie,
                    method: "POST",
                    payload: {},
                    metricLabel: "media.broadcast_activate"
                }
            );
            metrics.broadcastsActivated += 1;
        }
    }

    let createdOrder = null;
    if (decisions.createOrder) {
        const selectedPlan = pickSubscriptionPlan(commerceState.plans, commerceState.activeSubscription);
        if (selectedPlan?.id) {
            createdOrder = await createOrder(config.baseUrl, cookie, customer.id, selectedPlan, config, metrics);
            metrics.ordersCreated += 1;
            commerceState.orders = createdOrder
                ? [createdOrder, ...commerceState.orders.filter((order) => order?.id !== createdOrder.id)]
                : commerceState.orders;
        }
    }

    const orderToSettle = decisions.settleOrder
        ? createdOrder ?? pickOrderByStatus(commerceState.orders, ACTIVE_ORDER_STATUSES)
        : null;
    if (orderToSettle?.id) {
        await updateOrderStatus(config.baseUrl, cookie, orderToSettle.id, "PAID", config, metrics);
        metrics.ordersSettled += 1;
        commerceState.orders = updateOrderState(commerceState.orders, orderToSettle.id, "PAID");
        commerceState.activeSubscription = await fetchActiveSubscription(config.baseUrl, cookie, customer.id, config, metrics);
        if (commerceState.activeSubscription?.orderId === orderToSettle.id) {
            metrics.subscriptionActivationsVerified += 1;
        }
    }

    const orderToComplete = decisions.completeOrder
        ? pickOrderByStatus(commerceState.orders, COMPLETABLE_ORDER_STATUSES)
        : null;
    if (orderToComplete?.id) {
        const beforeCompletion = commerceState.activeSubscription?.orderId === orderToComplete.id
            ? commerceState.activeSubscription
            : await fetchActiveSubscription(config.baseUrl, cookie, customer.id, config, metrics);

        await updateOrderStatus(config.baseUrl, cookie, orderToComplete.id, "COMPLETED", config, metrics);
        metrics.ordersCompleted += 1;
        commerceState.orders = updateOrderState(commerceState.orders, orderToComplete.id, "COMPLETED");
        const afterCompletion = await fetchActiveSubscription(config.baseUrl, cookie, customer.id, config, metrics);

        if (sameSubscriptionWindow(beforeCompletion, afterCompletion, orderToComplete.id)) {
            metrics.subscriptionCompletionsVerified += 1;
        }

        commerceState.activeSubscription = afterCompletion;
    }

    await requestJson(config.baseUrl, "/api/v1/demo/public/broadcast/current", config, metrics, {
        cookie,
        metricLabel: "broadcast.current"
    });

    try {
        await requestJson(config.baseUrl, "/api/v1/demo/public/trace-map", config, metrics, {
            cookie,
            metricLabel: "broadcast.trace_map"
        });
    } catch {
        // The trace map is informative for the demo, but a miss should not fail a cycle.
    }

    console.log(
        `[worker ${workerId}] cycle complete: ${metrics.billingEvents} billing events, ${metrics.ordersCreated} orders, ` +
        `${metrics.ordersSettled} paid transitions, ${metrics.rtspJobs} rtsp jobs, ${metrics.invoicePaymentsCaptured} invoice payments`
    );
}

async function fetchCustomerDirectory(baseUrl, cookie, config, metrics) {
    const response = await requestJson(
        baseUrl,
        `/api/v1/customers?page=1&pageSize=${encodeURIComponent(String(config.customerPageSize))}`,
        config,
        metrics,
        {
            cookie,
            metricLabel: "accounts.customers"
        }
    );

    return Array.isArray(response.payload?.data) ? response.payload.data : [];
}

async function readPaymentWorkspace(baseUrl, cookie, userId, config, metrics) {
    const [cardHolderResponse, transactionsResponse] = await Promise.all([
        requestJson(
            baseUrl,
            `/api/v1/payments/card-holder?userId=${encodeURIComponent(userId)}`,
            config,
            metrics,
            {
                cookie,
                expectedStatuses: [200, 404],
                metricLabel: "payments.card_holder"
            }
        ),
        requestJson(
            baseUrl,
            `/api/v1/payments/transactions?userId=${encodeURIComponent(userId)}&page=0&size=5`,
            config,
            metrics,
            {
                cookie,
                metricLabel: "payments.transactions"
            }
        )
    ]);

    return {
        cardHolder: cardHolderResponse.status === 404 ? null : cardHolderResponse.payload,
        transactions: normalizeArray(transactionsResponse.payload)
    };
}

async function readCommerceWorkspace(baseUrl, cookie, userId, config, metrics) {
    const [plansResponse, activeSubscription, ordersResponse] = await Promise.all([
        requestJson(baseUrl, "/api/v1/subscription/all", config, metrics, {
            cookie,
            metricLabel: "commerce.plans"
        }),
        fetchActiveSubscription(baseUrl, cookie, userId, config, metrics),
        requestJson(
            baseUrl,
            `/api/v1/orders?userId=${encodeURIComponent(userId)}`,
            config,
            metrics,
            {
                cookie,
                metricLabel: "commerce.orders"
            }
        )
    ]);

    return {
        plans: normalizeArray(plansResponse.payload),
        activeSubscription,
        orders: normalizeArray(ordersResponse.payload)
    };
}

async function fetchActiveSubscription(baseUrl, cookie, userId, config, metrics) {
    const response = await requestJson(
        baseUrl,
        `/api/v1/subscription/active?id=${encodeURIComponent(userId)}`,
        config,
        metrics,
        {
            cookie,
            expectedStatuses: [200, 404],
            metricLabel: "commerce.active_subscription"
        }
    );

    return response.status === 404 ? null : response.payload;
}

async function createBillingEvent(baseUrl, cookie, accountId, catalog, config, metrics) {
    const eventType = Math.random() < 0.5 ? "ORDER_BOOKED" : "SUBSCRIPTION_RENEWED";
    const billingCycle = eventType === "SUBSCRIPTION_RENEWED" ? "MONTHLY" : "ONE_TIME";
    const title = eventType === "SUBSCRIPTION_RENEWED" ? "Renewed channel carriage" : "Special event booking";
    const item = pickCatalogItem(catalog);

    await requestJson(baseUrl, "/api/v1/billing/events", config, metrics, {
        cookie,
        method: "POST",
        metricLabel: "billing.events.create",
        payload: {
            eventId: randomUUID(),
            eventType,
            userId: accountId,
            invoiceId: null,
            orderId: billingCycle === "ONE_TIME" ? randomUUID() : null,
            subscriptionId: billingCycle === "MONTHLY" ? randomUUID() : null,
            billingCycle,
            currency: "USD",
            title,
            description: item ? `${title} for ${item.title}.` : title,
            quantity: 1,
            unitAmount: eventType === "SUBSCRIPTION_RENEWED" ? 1850 : 960,
            taxAmount: 0,
            discountAmount: 0,
            issuedDate: isoDateFromOffset(0),
            dueDate: isoDateFromOffset(eventType === "SUBSCRIPTION_RENEWED" ? 14 : 7),
            servicePeriodStart: isoDateFromOffset(-30),
            servicePeriodEnd: isoDateFromOffset(0),
            externalReference: null,
            notes: "Generated by operator-billing-loadgen."
        }
    });
}

async function captureInvoicePayment(baseUrl, cookie, invoice, config, metrics) {
    await requestJson(baseUrl, "/api/v1/billing/events", config, metrics, {
        cookie,
        method: "POST",
        metricLabel: "billing.events.capture_payment",
        payload: {
            eventId: randomUUID(),
            eventType: "PAYMENT_CAPTURED",
            userId: invoice.userId,
            invoiceId: invoice.id,
            orderId: invoice.orderId,
            subscriptionId: invoice.subscriptionId,
            billingCycle: invoice.billingCycle ?? "ONE_TIME",
            currency: invoice.currency ?? "USD",
            title: `Payment captured for ${invoice.invoiceNumber}`,
            description: invoice.notes ?? null,
            quantity: 1,
            unitAmount: Number(invoice.totalAmount ?? invoice.balanceDue ?? 0),
            taxAmount: 0,
            discountAmount: 0,
            issuedDate: invoice.issuedDate,
            dueDate: invoice.dueDate,
            servicePeriodStart: invoice.servicePeriodStart,
            servicePeriodEnd: invoice.servicePeriodEnd,
            externalReference: `LOADGEN-${Date.now()}`,
            notes: "Auto-captured by operator-billing-loadgen."
        }
    });
}

async function createRtspJob(baseUrl, cookie, item, config, metrics) {
    if (!item?.id) {
        return;
    }

    await requestJson(baseUrl, "/api/v1/demo/media/rtsp/jobs", config, metrics, {
        cookie,
        method: "POST",
        metricLabel: "media.rtsp_create",
        payload: {
            contentId: item.id,
            mediaType: item.type ?? "MOVIE",
            targetTitle: item.title ?? "Demo asset",
            sourceUrl: RTSP_SOURCE_URL,
            captureDurationSeconds: 90,
            operatorEmail: "ops@acmebroadcasting.com"
        }
    });
}

async function createOrder(baseUrl, cookie, customerId, plan, config, metrics) {
    const response = await requestJson(baseUrl, "/api/v1/orders", config, metrics, {
        cookie,
        method: "POST",
        metricLabel: "commerce.order_create",
        payload: {
            userId: customerId,
            subscriptionId: plan.id,
            price: Number(plan.price ?? 0)
        }
    });

    return response.payload;
}

async function updateOrderStatus(baseUrl, cookie, orderId, status, config, metrics) {
    await requestJson(
        baseUrl,
        `/api/v1/orders/${encodeURIComponent(orderId)}/status`,
        config,
        metrics,
        {
            cookie,
            method: "PUT",
            metricLabel: `commerce.order_${String(status).toLowerCase()}`,
            payload: {
                orderStatus: status
            }
        }
    );
}

async function login(baseUrl, config, metrics) {
    const label = "auth.persona_login";
    const startedAt = Date.now();
    let response;

    try {
        response = await fetch(resolveUrl(baseUrl, `/api/v1/demo/auth/persona/${encodeURIComponent(config.persona)}`), {
            method: "POST",
            redirect: "manual",
            signal: AbortSignal.timeout(config.requestTimeoutMs)
        });
    } catch (error) {
        recordRequest(metrics, label, Date.now() - startedAt, "ERR", false);
        throw new Error(`Persona login failed: ${error.message}`);
    }

    recordRequest(metrics, label, Date.now() - startedAt, response.status, response.ok);
    if (!response.ok) {
        throw new Error(`Persona login failed with HTTP ${response.status}.`);
    }

    const cookieHeader = response.headers.get("set-cookie");
    if (!cookieHeader) {
        throw new Error("Persona login did not return a session cookie.");
    }

    return cookieHeader.split(";")[0];
}

async function requestJson(baseUrl, path, config, metrics, options = {}) {
    const label = options.metricLabel ?? path;
    const startedAt = Date.now();
    const expectedStatuses = Array.isArray(options.expectedStatuses) && options.expectedStatuses.length
        ? options.expectedStatuses
        : [200];
    let response;

    try {
        response = await fetch(resolveUrl(baseUrl, path), {
            method: options.method ?? "GET",
            headers: buildHeaders(options.cookie, options.payload, options.headers),
            body: options.payload === undefined ? undefined : JSON.stringify(options.payload),
            signal: AbortSignal.timeout(config.requestTimeoutMs)
        });
    } catch (error) {
        recordRequest(metrics, label, Date.now() - startedAt, "ERR", false);
        throw new Error(`${label} failed: ${error.message}`);
    }

    const text = await response.text();
    const successfulStatus = expectedStatuses.includes(response.status);
    recordRequest(metrics, label, Date.now() - startedAt, response.status, successfulStatus);

    if (!successfulStatus) {
        throw new Error(`${label} returned HTTP ${response.status}: ${truncateText(text, 280)}`);
    }

    return {
        status: response.status,
        payload: parseJson(text),
        text
    };
}

function buildHeaders(cookie, payload, extraHeaders = {}) {
    const headers = { ...extraHeaders };
    if (cookie) {
        headers.cookie = cookie;
    }
    if (payload !== undefined) {
        headers["content-type"] = "application/json";
    }
    return headers;
}

function recordRequest(metrics, label, durationMs, status, successful) {
    const stat = metrics.requestStats[label] ?? {
        count: 0,
        failures: 0,
        totalMs: 0,
        maxMs: 0,
        statuses: {}
    };

    stat.count += 1;
    stat.totalMs += durationMs;
    stat.maxMs = Math.max(stat.maxMs, durationMs);
    stat.statuses[String(status)] = (stat.statuses[String(status)] ?? 0) + 1;
    if (!successful) {
        stat.failures += 1;
    }

    metrics.requestStats[label] = stat;
}

function parseJson(text) {
    if (!text) {
        return null;
    }

    try {
        return JSON.parse(text);
    } catch {
        return text;
    }
}

function pickCatalogItem(items) {
    return pickRandom(normalizeArray(items));
}

function pickSubscriptionPlan(plans, activeSubscription) {
    const activePlans = normalizeArray(plans)
        .filter((plan) => String(plan?.recordStatus ?? "").toUpperCase() === ACTIVE_SUBSCRIPTION_STATUS);
    if (!activePlans.length) {
        return null;
    }

    const activePlanId = activeSubscription?.subscription?.id ?? "";
    const alternatives = activePlanId
        ? activePlans.filter((plan) => plan?.id !== activePlanId)
        : activePlans;

    return pickRandom(alternatives.length ? alternatives : activePlans);
}

function pickOrderByStatus(orders, statuses) {
    return normalizeArray(orders)
        .find((order) => statuses.has(String(order?.orderStatus ?? "").toUpperCase()));
}

function updateOrderState(orders, orderId, nextStatus) {
    return normalizeArray(orders).map((order) => (
        order?.id === orderId
            ? { ...order, orderStatus: nextStatus }
            : order
    ));
}

function sameSubscriptionWindow(beforeCompletion, afterCompletion, orderId) {
    return Boolean(
        beforeCompletion
        && afterCompletion
        && beforeCompletion.orderId === orderId
        && afterCompletion.orderId === orderId
        && beforeCompletion.startDate === afterCompletion.startDate
        && beforeCompletion.endDate === afterCompletion.endDate
    );
}

function pickRandom(items) {
    const normalizedItems = normalizeArray(items);
    if (!normalizedItems.length) {
        return null;
    }
    return normalizedItems[Math.floor(Math.random() * normalizedItems.length)];
}

function normalizeArray(value) {
    return Array.isArray(value) ? value : [];
}

function shouldRun(ratio) {
    return Math.random() < ratio;
}

function resolveUrl(baseUrl, path) {
    return new URL(path, baseUrl).toString();
}

function isoDateFromOffset(days) {
    const date = new Date();
    date.setHours(12, 0, 0, 0);
    date.setDate(date.getDate() + days);
    return date.toISOString().slice(0, 10);
}

function truncateText(text, limit) {
    const normalized = String(text ?? "").replace(/\s+/g, " ").trim();
    if (normalized.length <= limit) {
        return normalized;
    }
    return `${normalized.slice(0, Math.max(0, limit - 3))}...`;
}

function parseArgs(argv, env) {
    const config = {
        baseUrl: env.LOADGEN_OPERATOR_BASE_URL ?? env.LOADGEN_BASE_URL ?? "",
        persona: env.LOADGEN_OPERATOR_PERSONA ?? "operator",
        durationMs: parseDuration(env.LOADGEN_OPERATOR_DURATION ?? "8m"),
        concurrency: Number.parseInt(env.LOADGEN_OPERATOR_CONCURRENCY ?? "3", 10),
        pauseMs: parseDuration(env.LOADGEN_OPERATOR_PAUSE ?? "4s"),
        requestTimeoutMs: parseDuration(env.LOADGEN_OPERATOR_REQUEST_TIMEOUT ?? "15s"),
        customerPageSize: Number.parseInt(env.LOADGEN_OPERATOR_CUSTOMER_PAGE_SIZE ?? "24", 10),
        billingEventRatio: Number.parseFloat(env.LOADGEN_OPERATOR_BILLING_EVENT_RATIO ?? "0.55"),
        paymentRatio: Number.parseFloat(env.LOADGEN_OPERATOR_PAYMENT_RATIO ?? "0.20"),
        paymentReadRatio: Number.parseFloat(env.LOADGEN_OPERATOR_PAYMENT_READ_RATIO ?? "0.80"),
        commerceReadRatio: Number.parseFloat(env.LOADGEN_OPERATOR_COMMERCE_READ_RATIO ?? "0.85"),
        orderCreateRatio: Number.parseFloat(env.LOADGEN_OPERATOR_ORDER_CREATE_RATIO ?? "0.35"),
        orderSettleRatio: Number.parseFloat(env.LOADGEN_OPERATOR_ORDER_SETTLE_RATIO ?? "0.35"),
        orderCompleteRatio: Number.parseFloat(env.LOADGEN_OPERATOR_ORDER_COMPLETE_RATIO ?? "0.15"),
        rtspJobRatio: Number.parseFloat(env.LOADGEN_OPERATOR_RTSP_JOB_RATIO ?? "0.35"),
        takeLiveRatio: Number.parseFloat(env.LOADGEN_OPERATOR_TAKE_LIVE_RATIO ?? "0.20"),
        helpRequested: false
    };

    for (let index = 0; index < argv.length; index += 1) {
        const arg = argv[index];
        const next = argv[index + 1];
        switch (arg) {
            case "--base-url":
                config.baseUrl = next;
                index += 1;
                break;
            case "--persona":
                config.persona = next;
                index += 1;
                break;
            case "--duration":
                config.durationMs = parseDuration(next);
                index += 1;
                break;
            case "--concurrency":
                config.concurrency = Number.parseInt(next, 10);
                index += 1;
                break;
            case "--pause":
                config.pauseMs = parseDuration(next);
                index += 1;
                break;
            case "--request-timeout":
                config.requestTimeoutMs = parseDuration(next);
                index += 1;
                break;
            case "--customer-page-size":
                config.customerPageSize = Number.parseInt(next, 10);
                index += 1;
                break;
            case "--billing-event-ratio":
                config.billingEventRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--payment-ratio":
                config.paymentRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--payment-read-ratio":
                config.paymentReadRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--commerce-read-ratio":
                config.commerceReadRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--order-create-ratio":
                config.orderCreateRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--order-settle-ratio":
                config.orderSettleRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--order-complete-ratio":
                config.orderCompleteRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--rtsp-job-ratio":
                config.rtspJobRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--take-live-ratio":
                config.takeLiveRatio = Number.parseFloat(next);
                index += 1;
                break;
            case "--help":
                config.helpRequested = true;
                break;
            default:
                throw new Error(`Unknown argument: ${arg}`);
        }
    }

    return config;
}

function validateConfig(config) {
    if (!config.baseUrl) {
        throw new Error("Base URL is required. Use --base-url, LOADGEN_OPERATOR_BASE_URL, or LOADGEN_BASE_URL.");
    }
    if (!Number.isInteger(config.concurrency) || config.concurrency < 1) {
        throw new Error(`Concurrency must be at least 1. Received: ${config.concurrency}`);
    }
    if (!Number.isInteger(config.customerPageSize) || config.customerPageSize < 1) {
        throw new Error(`Customer page size must be at least 1. Received: ${config.customerPageSize}`);
    }
    if (config.requestTimeoutMs < 1000) {
        throw new Error(`Request timeout must be at least 1s. Received: ${config.requestTimeoutMs}ms`);
    }

    for (const [label, ratio] of Object.entries({
        billingEventRatio: config.billingEventRatio,
        paymentRatio: config.paymentRatio,
        paymentReadRatio: config.paymentReadRatio,
        commerceReadRatio: config.commerceReadRatio,
        orderCreateRatio: config.orderCreateRatio,
        orderSettleRatio: config.orderSettleRatio,
        orderCompleteRatio: config.orderCompleteRatio,
        rtspJobRatio: config.rtspJobRatio,
        takeLiveRatio: config.takeLiveRatio
    })) {
        if (!Number.isFinite(ratio) || ratio < 0 || ratio > 1) {
            throw new Error(`${label} must be between 0 and 1. Received: ${ratio}`);
        }
    }
}

function parseDuration(value) {
    const match = String(value).trim().match(/^(\d+)(ms|s|m|h)$/i);
    if (!match) {
        throw new Error(`Invalid duration: ${value}`);
    }

    const amount = Number.parseInt(match[1], 10);
    const unit = match[2].toLowerCase();
    switch (unit) {
        case "ms":
            return amount;
        case "s":
            return amount * 1000;
        case "m":
            return amount * 60 * 1000;
        case "h":
            return amount * 60 * 60 * 1000;
        default:
            throw new Error(`Unsupported duration unit: ${unit}`);
    }
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});
