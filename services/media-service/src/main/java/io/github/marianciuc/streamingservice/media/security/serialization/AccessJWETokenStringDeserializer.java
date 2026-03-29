package io.github.marianciuc.streamingservice.media.security.serialization;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.github.marianciuc.streamingservice.media.security.dto.Token;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.text.ParseException;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class AccessJWETokenStringDeserializer implements TokenDeserializer {

    private final JWSVerifier verifier;

    @Override
    public Token apply(String token) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            if (!signedJwt.verify(verifier)) {
                return null;
            }

            return new Token(
                    UUID.fromString(signedJwt.getHeader().getKeyID()),
                    UUID.fromString(signedJwt.getJWTClaimsSet().getClaim("user-id").toString()),
                    signedJwt.getJWTClaimsSet().getSubject(),
                    signedJwt.getJWTClaimsSet().getIssuer(),
                    signedJwt.getJWTClaimsSet().getStringListClaim("authorities"),
                    signedJwt.getJWTClaimsSet().getIssueTime().toInstant(),
                    signedJwt.getJWTClaimsSet().getExpirationTime().toInstant()
            );
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            log.error("Error parsing JWT", exception);
            return null;
        }
    }
}
