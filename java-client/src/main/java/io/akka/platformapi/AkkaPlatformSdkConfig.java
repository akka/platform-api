package io.akka.platformapi;

import java.util.Optional;

public final class AkkaPlatformSdkConfig {

    static final String DEFAULT_API_HOST = "api.kalix.io:443";

    private final TokenConfig tokenConfig;
    private final String apiHost;

    private AkkaPlatformSdkConfig(TokenConfig tokenConfig, String apiHost) {
        this.tokenConfig = tokenConfig;
        this.apiHost = apiHost;
    }

    /**
     * Resolves configuration using the following precedence:
     * <ol>
     *   <li>Refresh token: {@code AKKA_TOKEN} environment variable.</li>
     *   <li>OAuth token exchange: {@code AKKA_OAUTH_TOKEN} or {@code AKKA_OAUTH_TOKEN_FILE}
     *       (requires {@code AKKA_OAUTH_TOKEN_AUDIENCE}).</li>
     *   <li>The {@code akka} CLI, if installed: {@code akka config get refresh-token} and
     *       {@code akka config get api-server-host}.</li>
     *   <li>{@code api.kalix.io:443} as the default host if neither source provides one.</li>
     * </ol>
     * Throws {@link IllegalStateException} if no authentication can be configured from any source.
     */
    public static AkkaPlatformSdkConfig loadDefault() {
        String envHost = System.getenv("AKKA_API_HOST");
        String host = (envHost != null && !envHost.isEmpty()) ? envHost : null;

        String envToken = System.getenv("AKKA_TOKEN");
        if (envToken != null && !envToken.isEmpty()) {
            return new AkkaPlatformSdkConfig(
                    new RefreshTokenConfig(envToken),
                    host != null ? host : DEFAULT_API_HOST);
        }

        String oauthToken     = System.getenv("AKKA_OAUTH_TOKEN");
        String oauthTokenFile = System.getenv("AKKA_OAUTH_TOKEN_FILE");
        if ((oauthToken != null && !oauthToken.isEmpty())
                || (oauthTokenFile != null && !oauthTokenFile.isEmpty())) {
            String audience = System.getenv("AKKA_OAUTH_TOKEN_AUDIENCE");
            if (audience == null || audience.isEmpty()) {
                throw new IllegalStateException(
                        "AKKA_OAUTH_TOKEN_AUDIENCE must be set when AKKA_OAUTH_TOKEN or " +
                        "AKKA_OAUTH_TOKEN_FILE is configured.");
            }
            OAuthTokenConfig oauthConfig = (oauthToken != null && !oauthToken.isEmpty())
                    ? OAuthTokenConfig.withToken(oauthToken, audience)
                    : OAuthTokenConfig.withTokenFile(oauthTokenFile, audience);
            return new AkkaPlatformSdkConfig(oauthConfig, host != null ? host : DEFAULT_API_HOST);
        }

        String cliToken = runAkkaConfig("refresh-token").orElseThrow(() ->
                new IllegalStateException(
                        "No Akka authentication found. Set one of the AKKA_TOKEN, AKKA_OAUTH_TOKEN, or" +
                        "AKKA_OAUTH_TOKEN_FILE environment variables, or log in with the akka CLI " +
                        "('akka auth login')."));

        if (host == null) {
            host = runAkkaConfig("api-server-host").orElse(DEFAULT_API_HOST);
        }

        return new AkkaPlatformSdkConfig(new RefreshTokenConfig(cliToken), host);
    }

    private static Optional<String> runAkkaConfig(String key) {
        try {
            Process process = new ProcessBuilder("akka", "config", "get", key)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return (exitCode == 0 && !output.isEmpty()) ? Optional.of(output) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static AkkaPlatformSdkConfig withRefreshToken(String refreshToken) {
        return new AkkaPlatformSdkConfig(new RefreshTokenConfig(refreshToken), DEFAULT_API_HOST);
    }

    public static AkkaPlatformSdkConfig withRefreshToken(String refreshToken, String apiHost) {
        return new AkkaPlatformSdkConfig(new RefreshTokenConfig(refreshToken), apiHost);
    }

    /** Uses a fixed OAuth subject token value for token exchange. */
    public static AkkaPlatformSdkConfig withOAuthToken(String oauthToken, String audience) {
        return new AkkaPlatformSdkConfig(OAuthTokenConfig.withToken(oauthToken, audience), DEFAULT_API_HOST);
    }

    /** Uses a fixed OAuth subject token value for token exchange against the given API host. */
    public static AkkaPlatformSdkConfig withOAuthToken(String oauthToken, String audience, String apiHost) {
        return new AkkaPlatformSdkConfig(OAuthTokenConfig.withToken(oauthToken, audience), apiHost);
    }

    /** Reads the OAuth subject token from a file on every exchange (supports rotation). */
    public static AkkaPlatformSdkConfig withOAuthTokenFile(String tokenFilePath, String audience) {
        return new AkkaPlatformSdkConfig(OAuthTokenConfig.withTokenFile(tokenFilePath, audience), DEFAULT_API_HOST);
    }

    /** Reads the OAuth subject token from a file on every exchange against the given API host. */
    public static AkkaPlatformSdkConfig withOAuthTokenFile(String tokenFilePath, String audience, String apiHost) {
        return new AkkaPlatformSdkConfig(OAuthTokenConfig.withTokenFile(tokenFilePath, audience), apiHost);
    }

    public static AkkaPlatformSdkConfig of(TokenConfig tokenConfig, String apiHost) {
        return new AkkaPlatformSdkConfig(tokenConfig, apiHost);
    }

    public static AkkaPlatformSdkConfig of(TokenConfig tokenConfig) {
        String envHost = System.getenv("AKKA_API_HOST");
        String host = (envHost != null && !envHost.isEmpty()) ? envHost : DEFAULT_API_HOST;

        return new AkkaPlatformSdkConfig(tokenConfig, host);
    }

    public TokenConfig getTokenConfig() {
        return tokenConfig;
    }

    public String getApiHost() {
        return apiHost;
    }
}
