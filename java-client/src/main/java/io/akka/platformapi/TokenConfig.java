package io.akka.platformapi;

/**
 * Marker interface for token configuration types.
 * Implementations: {@link RefreshTokenConfig} (long-lived refresh token) and
 * {@link OAuthTokenConfig} (OAuth token exchange via RFC 8693).
 */
public interface TokenConfig {}
