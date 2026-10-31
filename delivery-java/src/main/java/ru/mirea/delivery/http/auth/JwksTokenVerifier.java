package ru.mirea.delivery.http.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.error.UnauthorizedException;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Set;

/**
 * Проверка токена по открытым ключам Keycloak (JWKS).
 *
 * Ключи забираются один раз и держатся в памяти: обращения к провайдеру
 * на каждый запрос нет, иначе он стал бы узким местом и точкой отказа.
 * Проверяются подпись, срок и получатель; часы сервиса и провайдера
 * расходятся на секунды — допуск 5 с.
 */
public final class JwksTokenVerifier implements TokenVerifier {
    private final ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public JwksTokenVerifier(AppConfig.Auth auth) {
        String base = auth.providerUrl() != null ? auth.providerUrl() : auth.issuer();
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.create(URI.create(base + "/protocol/openid-connect/certs").toURL())
                    .cache(10 * 60 * 1000, 15 * 1000)
                    .build();
        } catch (MalformedURLException error) {
            throw new IllegalStateException("неверный адрес провайдера входа: " + base, error);
        }
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(auth.audience(),
                new JWTClaimsSet.Builder().issuer(auth.issuer()).build(), Set.of("sub", "exp"));
        claims.setMaxClockSkew(5);
        processor.setJWTClaimsSetVerifier(claims);
    }

    @Override
    public AuthenticatedUser verify(String token) {
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (Exception error) {
            // Наружу — общая причина: подробности облегчают подбор.
            throw new UnauthorizedException("токен недействителен");
        }
        return TokenVerifier.toUser(claims.toJSONObject());
    }
}
