package io.akka.platformapi;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Obtains a short-lived access token and its expiry time.
 *
 * <p>Built-in implementations ({@link RefreshTokenFetcher}, {@link OAuthTokenFetcher}) are
 * constructed automatically from the configured {@link TokenConfig}. To provide a custom
 * authentication mechanism, implement both {@link TokenConfig} and this interface on the same
 * class and pass it to {@link AkkaPlatformSdkConfig#of}.
 */
public interface TokenFetcher {

    record TokenWithExpiry(String token, Instant expiresAt) {}

    CompletableFuture<TokenWithExpiry> getToken();
}
