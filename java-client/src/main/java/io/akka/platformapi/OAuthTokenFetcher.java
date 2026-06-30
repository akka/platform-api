package io.akka.platformapi;

import kalix.api.auth.v1alpha.AuthClient;
import kalix.api.auth.v1alpha.CreateOAuthTokenRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

final class OAuthTokenFetcher implements TokenFetcher {

    private final AuthClient authClient;
    private final OAuthTokenConfig config;

    OAuthTokenFetcher(AuthClient authClient, OAuthTokenConfig config) {
        this.authClient = authClient;
        this.config = config;
    }

    @Override
    public CompletableFuture<TokenWithExpiry> getToken() {
        String subjectToken;
        try {
            subjectToken = config.readToken();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        return authClient.createOAuthToken()
                .invoke(CreateOAuthTokenRequest.newBuilder()
                        .setGrantType("urn:ietf:params:oauth:grant-type:token-exchange")
                        .setAudience(config.getAudience())
                        .setRequestedTokenType("urn:ietf:params:oauth:token-type:access_token")
                        .setSubjectToken(subjectToken)
                        .setSubjectTokenType("urn:ietf:params:oauth:token-type:id_token")
                        .build())
                .toCompletableFuture()
                .thenApply(response -> new TokenWithExpiry(
                        response.getAccessToken(),
                        Instant.now().plusSeconds(response.getExpiresIn())));
    }
}
