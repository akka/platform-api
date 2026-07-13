package io.akka.platformapi;

import kalix.api.auth.v1.AuthClient;
import kalix.api.auth.v1.CreateAccessTokenRequest;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

final class RefreshTokenFetcher implements TokenFetcher {

    private final AuthClient authClient;
    private final String refreshToken;

    RefreshTokenFetcher(AuthClient authClient, String refreshToken) {
        this.authClient = authClient;
        this.refreshToken = refreshToken;
    }

    @Override
    public CompletableFuture<TokenWithExpiry> getToken() {
        return authClient.createAccessToken()
                .addHeader("authorization", "Bearer " + refreshToken)
                .invoke(CreateAccessTokenRequest.newBuilder().build())
                .toCompletableFuture()
                .thenApply(response -> new TokenWithExpiry(
                        response.getToken(),
                        Instant.ofEpochSecond(
                                response.getExpireTime().getSeconds(),
                                response.getExpireTime().getNanos())));
    }
}
