window.STREAMING_CONFIG = {
    environment: "Primary Operations",
    clusterLabel: "cluster/unset",
    namespace: "streaming-service-app",
    regionLabel: "Deployment Region",
    controlRoomLabel: "Acme Broadcast Center",
    defaultOperatorRole: "Programming Operations",
    distributionModel: "Protected broadcast workspace backed by catalog, identity, and media control services",
    broadcastModel: "Linear channels, sponsor pods, external distribution, review-on-demand, and RTSP intake in one operator surface",
    apiBase: "",
    authLoginUrl: "/api/v1/demo/auth/login",
    authSessionUrl: "/api/v1/demo/auth/session",
    authLogoutUrl: "/api/v1/demo/auth/logout",
    authHealthUrl: "/api/v1/demo/auth/health",
    catalogUrl: "/api/v1/demo/content",
    contentHealthUrl: "/api/v1/demo/content/health",
    adHealthUrl: "/api/v1/demo/ads/health",
    adProgramQueueUrl: "/api/v1/demo/ads/program-queue",
    adIssueUrl: "/api/v1/demo/ads/issues",
    billingInvoicesUrl: "/api/v1/billing/invoices",
    billingEventsUrl: "/api/v1/billing/events",
    billingHealthUrl: "/api/v1/billing/health",
    accountsCustomersUrl: "/api/v1/customers",
    paymentsCardHolderUrl: "/api/v1/payments/card-holder",
    paymentsTransactionsUrl: "/api/v1/payments/transactions",
    subscriptionCatalogUrl: "/api/v1/subscription/all",
    subscriptionActiveUrl: "/api/v1/subscription/active",
    subscriptionCancelUrl: "/api/v1/subscription/cancel",
    ordersUrl: "/api/v1/orders",
    rtspJobsUrl: "/api/v1/demo/media/rtsp/jobs",
    broadcastActivateUrlBase: "/api/v1/demo/media/broadcast/jobs",
    demoMonkeyUrl: "/api/v1/demo/media/demo-monkey",
    publicDemoMonkeyUrl: "/api/v1/demo/public/demo-monkey",
    authPersonaUrlBase: "/api/v1/demo/auth/persona",
    publicTraceMapUrl: "/api/v1/demo/public/trace-map",
    publicBroadcastStatusUrl: "/api/v1/demo/public/broadcast/current",
    publicBroadcastPlaybackUrl: "/api/v1/demo/public/broadcast/live/index.m3u8",
    publicBroadcastPageUrl: "/broadcast",
    publicBroadcastRtspUrl: "",
    defaultBroadcastChannelLabel: "Acme Network East",
    defaultBroadcastTitle: "Acme House Lineup",
    defaultBroadcastDetail: "Sintel, Big Buck Bunny, Elephants Dream, and Tears of Steel rotate on the external channel with sponsor pods about every 90 seconds until a contribution feed is taken live.",
    mediaHealthUrl: "/api/v1/demo/media/health",
    splunkRum: {
        enabled: true,
        sessionReplayEnabled: true,
        realm: "us1",
        rumAccessToken: "",
        applicationName: "streaming-app-frontend",
        deploymentEnvironment: "streaming-app",
        privacy: {
            maskAllText: false,
            sensitivityRules: [
                { rule: "mask", selector: "#billing, #billing *" }
            ]
        },
        sessionReplay: {
            maskAllInputs: false,
            maskAllText: false,
            sensitivityRules: [
                { rule: "mask", selector: "input[type='password']" },
                { rule: "mask", selector: "#billing, #billing *" }
            ],
            features: {
                video: true
            }
        }
    },
    probeTimeoutMs: 4500,
    channelLabels: ["Prime East", "Events", "Review Desk"],
    demoMovieTitle: "Big Buck Bunny",
    demoMovieDescription: "An openly licensed short staged through the media service for screening and playout review.",
    demoMovieUrl: "/api/v1/demo/media/movie.mp4",
    demoMovieBadges: ["media-service", "Kubernetes", "House asset"],
    observabilityLinks: {
        thousandEyesUrl: "",
        splunkApmUrl: "",
        splunkRumUrl: ""
    }
};
