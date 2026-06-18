package io.akka.platformapi;

public final class RefreshTokenConfig implements TokenConfig {
    private final String refreshToken;

    public RefreshTokenConfig(String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new IllegalArgumentException("refreshToken must not be null or empty");
        }
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }
}
