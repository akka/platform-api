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
     *   <li>Environment variables ({@code AKKA_REFRESH_TOKEN}, {@code AKKA_API_HOST}).</li>
     *   <li>The {@code akka} CLI, if installed: {@code akka config get refresh-token} and
     *       {@code akka config get api-server-host}.</li>
     *   <li>{@code api.kalix.io:443} as the default host if neither source provides one.</li>
     * </ol>
     * Throws {@link IllegalStateException} if no refresh token can be found from any source.
     */
    public static AkkaPlatformSdkConfig loadDefault() {
        String envToken = System.getenv("AKKA_REFRESH_TOKEN");
        String envHost  = System.getenv("AKKA_API_HOST");

        if (envToken != null && !envToken.isEmpty()) {
            String host = (envHost != null && !envHost.isEmpty()) ? envHost : DEFAULT_API_HOST;
            return new AkkaPlatformSdkConfig(new RefreshTokenConfig(envToken), host);
        }

        String cliToken = runAkkaConfig("refresh-token").orElseThrow(() ->
                new IllegalStateException(
                        "No Akka refresh token found. Set the AKKA_REFRESH_TOKEN environment " +
                        "variable or log in with the akka CLI ('akka auth login')."));

        String host;
        if (envHost != null && !envHost.isEmpty()) {
            host = envHost;
        } else {
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

    public static AkkaPlatformSdkConfig of(TokenConfig tokenConfig, String apiHost) {
        return new AkkaPlatformSdkConfig(tokenConfig, apiHost);
    }

    public TokenConfig getTokenConfig() {
        return tokenConfig;
    }

    public String getApiHost() {
        return apiHost;
    }
}
