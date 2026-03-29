package io.github.marianciuc.streamingservice.user.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.util.regex.Pattern;

@RestController
public class DemoAuthController {

    private static final String COOKIE_NAME = "acme_demo_session";
    private static final Pattern COMPANY_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@acmebroadcasting\\.com$");
    private static final Map<String, DemoUserProfile> DEMO_USERS = Map.ofEntries(
            Map.entry("ops@acmebroadcasting.com", new DemoUserProfile("Ops", "platform_admin", "Platform Admin")),
            Map.entry("platform@acmebroadcasting.com", new DemoUserProfile("Platform", "platform_admin", "Platform Admin")),
            Map.entry("programming@acmebroadcasting.com", new DemoUserProfile("Programming", "programming_manager", "Programming Manager")),
            Map.entry("qa@acmebroadcasting.com", new DemoUserProfile("Qa", "qa_reviewer", "QA Reviewer")),
            Map.entry("exec@acmebroadcasting.com", new DemoUserProfile("Exec", "executive", "Executive Stakeholder")),
            Map.entry("staff@acmebroadcasting.com", new DemoUserProfile("Staff", "staff_operator", "Staff Operator")),
            Map.entry("billingadmin@acmebroadcasting.com", new DemoUserProfile("Billingadmin", "billing_admin", "Billing Admin")),
            Map.entry("finance@acmebroadcasting.com", new DemoUserProfile("Finance", "finance_analyst", "Finance Analyst")),
            Map.entry("controller@acmebroadcasting.com", new DemoUserProfile("Controller", "billing_admin", "Billing Admin"))
    );
    private static final Map<String, String> DEMO_PERSONAS = Map.of(
            "operator", "ops@acmebroadcasting.com",
            "exec", "exec@acmebroadcasting.com",
            "programming", "programming@acmebroadcasting.com"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${demo.auth.secret}")
    private String authSecret;

    @Value("${demo.auth.max-age-seconds:43200}")
    private long maxAgeSeconds;

    @Value("${demo.auth.password}")
    private String demoPassword;

    @GetMapping("/api/v1/demo/auth/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping(path = "/api/v1/demo/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password().trim();
        DemoUserProfile profile = DEMO_USERS.get(email);

        if (!COMPANY_EMAIL.matcher(email).matches() || profile == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Use one of the approved Acme Broadcasting demo accounts."));
        }

        if (!MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), demoPassword.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Password is invalid."));
        }

        return issueSession(email, profile, response);
    }

    @PostMapping("/api/v1/demo/auth/persona/{persona}")
    public ResponseEntity<?> personaLogin(@PathVariable String persona, HttpServletResponse response) {
        String email = DEMO_PERSONAS.get(normalizePersona(persona));
        DemoUserProfile profile = email == null ? null : DEMO_USERS.get(email);
        if (profile == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Persona is not available in this demo environment."));
        }

        return issueSession(email, profile, response);
    }

    private ResponseEntity<?> issueSession(String email, DemoUserProfile profile, HttpServletResponse response) {
        Instant expiresAt = Instant.now().plusSeconds(maxAgeSeconds);
        SessionPrincipal principal = new SessionPrincipal(
                email,
                profile.name(),
                profile.role(),
                profile.roleLabel(),
                expiresAt.getEpochSecond()
        );
        String token = issueToken(principal);

        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token, maxAgeSeconds).toString());
        return ResponseEntity.ok(sessionResponse(principal));
    }

    @GetMapping("/api/v1/demo/auth/session")
    public ResponseEntity<?> session(
            @CookieValue(value = COOKIE_NAME, required = false) String cookieToken,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        SessionPrincipal principal = authenticate(cookieToken, authorizationHeader);
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Session expired."));
        }

        return ResponseEntity.ok(sessionResponse(principal));
    }

    @GetMapping("/api/v1/demo/auth/validate")
    public ResponseEntity<Void> validate(
            @CookieValue(value = COOKIE_NAME, required = false) String cookieToken,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        return authenticate(cookieToken, authorizationHeader) == null
                ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
                : ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/demo/auth/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
        return ResponseEntity.noContent().build();
    }

    private SessionPrincipal authenticate(String cookieToken, String authorizationHeader) {
        String token = StringUtils.hasText(cookieToken) ? cookieToken : bearerToken(authorizationHeader);
        if (!StringUtils.hasText(token)) {
            return null;
        }

        try {
            return readToken(token);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith("Bearer ")) {
            return "";
        }

        return authorizationHeader.substring(7).trim();
    }

    private String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String normalizePersona(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private ResponseCookie sessionCookie(String token, long maxAge) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private Map<String, Object> sessionResponse(SessionPrincipal principal) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", true);
        response.put("expiresAt", principal.expiresAtEpochSeconds());
        response.put("user", Map.of(
                "email", principal.email(),
                "name", principal.name(),
                "role", principal.role(),
                "roleLabel", principal.roleLabel()
        ));
        return response;
    }

    private String issueToken(SessionPrincipal principal) {
        try {
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(principal));
            String signature = sign(payload);
            return payload + "." + signature;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to encode token.", exception);
        }
    }

    private SessionPrincipal readToken(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed token.");
        }

        byte[] expectedSignature = sign(parts[0]).getBytes(StandardCharsets.UTF_8);
        byte[] actualSignature = parts[1].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
            throw new IllegalArgumentException("Invalid signature.");
        }

        try {
            SessionPrincipal principal = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]),
                    SessionPrincipal.class
            );
            if (principal.expiresAtEpochSeconds() <= Instant.now().getEpochSecond()) {
                throw new IllegalArgumentException("Expired token.");
            }
            return principal;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Malformed payload.", exception);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to sign token.", exception);
        }
    }

    public record LoginRequest(String email, String password) {
    }

    public record SessionPrincipal(String email, String name, String role, String roleLabel, long expiresAtEpochSeconds) {
    }

    private record DemoUserProfile(String name, String role, String roleLabel) {
    }
}
