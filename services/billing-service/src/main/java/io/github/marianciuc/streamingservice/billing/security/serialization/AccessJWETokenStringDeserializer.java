package io.github.marianciuc.streamingservice.billing.security.serialization;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.github.marianciuc.streamingservice.billing.security.dto.Token;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.ParseException;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class AccessJWETokenStringDeserializer implements TokenDeserializer {

    private final JWSVerifier verifier;

    @Override
    public Token apply(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(verifier)) {
                return null;
            }

            return new Token(
                    UUID.fromString(signedJWT.getHeader().getKeyID()),
                    UUID.fromString(signedJWT.getJWTClaimsSet().getClaim("user-id").toString()),
                    signedJWT.getJWTClaimsSet().getSubject(),
                    signedJWT.getJWTClaimsSet().getIssuer(),
                    signedJWT.getJWTClaimsSet().getStringListClaim("authorities"),
                    signedJWT.getJWTClaimsSet().getIssueTime().toInstant(),
                    signedJWT.getJWTClaimsSet().getExpirationTime().toInstant()
            );
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            log.error("Error parsing JWT", exception);
            return null;
        }
    }
}
