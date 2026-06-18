package io.akka.platformapi;

import kalix.api.auth.v1alpha.AccessToken;
import kalix.api.auth.v1alpha.AuthClient;
import kalix.api.auth.v1alpha.CreateAccessTokenRequest;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe cache for short-lived access tokens. Obtains new tokens via
 * {@code Auth.CreateAccessToken} authenticated with the configured refresh token.
 * Tokens are pre-emptively refreshed 60 seconds before expiry.
 */
final class AccessTokenCache {

    private static final int EXPIRY_BUFFER_SECONDS = 60;

    private final AuthClient authClient;
    private final String refreshToken;

    private volatile CachedToken cachedToken = null;

    AccessTokenCache(AuthClient authClient, String refreshToken) {
        this.authClient = authClient;
        this.refreshToken = refreshToken;
    }

    CompletableFuture<String> getToken() {
        var token = cachedToken;
        if (token != null && token.isValid()) {
            return CompletableFuture.completedFuture(token.token);
        }
        // It's possible that concurrent executions when no valid token is available will result in multiple
        // concurrent fetches, but that's ok, it's not going to hurt.
        return authClient.createAccessToken()
                .addHeader("authorization", "Bearer " + refreshToken)
                .invoke(CreateAccessTokenRequest.newBuilder().build())
                .toCompletableFuture()
                .thenApply((response) -> {
                    cachedToken = new CachedToken(
                            response.getToken(),
                            Instant.ofEpochSecond(
                                    response.getExpireTime().getSeconds(),
                                    response.getExpireTime().getNanos()));
                    return response.getToken();
                });
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().plusSeconds(EXPIRY_BUFFER_SECONDS).isBefore(expiresAt);
        }
    }
}
