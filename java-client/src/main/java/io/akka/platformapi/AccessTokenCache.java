package io.akka.platformapi;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe cache for short-lived access tokens. Tokens are pre-emptively refreshed 60 seconds
 * before expiry. The actual token-exchange logic is provided by the injected {@link TokenFetcher}.
 */
final class AccessTokenCache {

    private static final int EXPIRY_BUFFER_SECONDS = 60;

    private final TokenFetcher fetcher;
    private volatile CachedToken cachedToken = null;

    AccessTokenCache(TokenFetcher fetcher) {
        this.fetcher = fetcher;
    }

    CompletableFuture<String> getToken() {
        var token = cachedToken;
        if (token != null && token.isValid()) {
            return CompletableFuture.completedFuture(token.token);
        }
        // Concurrent callers when no valid token is cached may each trigger a fetch; that's benign.
        return fetcher.getToken().thenApply(result -> {
            cachedToken = new CachedToken(result.token(), result.expiresAt());
            return result.token();
        });
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().plusSeconds(EXPIRY_BUFFER_SECONDS).isBefore(expiresAt);
        }
    }
}
