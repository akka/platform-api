package io.akka.platformapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Token configuration for OAuth token exchange (RFC 8693).
 *
 * <p>The subject token can be supplied directly or read from a file. When a file path is
 * configured the file is re-read on every token exchange, which supports subject-token rotation.
 */
public final class OAuthTokenConfig implements TokenConfig {

    private final String token;
    private final Path tokenFile;
    private final String audience;

    private OAuthTokenConfig(String token, Path tokenFile, String audience) {
        this.token = token;
        this.tokenFile = tokenFile;
        this.audience = audience;
    }

    /** Uses a fixed subject token value. */
    public static OAuthTokenConfig withToken(String token, String audience) {
        if (token == null || token.isEmpty())
            throw new IllegalArgumentException("token must not be null or empty");
        if (audience == null || audience.isEmpty())
            throw new IllegalArgumentException("audience must not be null or empty");
        return new OAuthTokenConfig(token, null, audience);
    }

    /** Reads the subject token from a file on every exchange, supporting token rotation. */
    public static OAuthTokenConfig withTokenFile(String tokenFilePath, String audience) {
        if (tokenFilePath == null || tokenFilePath.isEmpty())
            throw new IllegalArgumentException("tokenFilePath must not be null or empty");
        if (audience == null || audience.isEmpty())
            throw new IllegalArgumentException("audience must not be null or empty");
        return new OAuthTokenConfig(null, Path.of(tokenFilePath), audience);
    }

    public String getAudience() {
        return audience;
    }

    /** Returns the subject token, reading it from the configured file if necessary. */
    String readToken() throws IOException {
        if (token != null) {
            return token;
        }
        String content = Files.readString(tokenFile).strip();
        if (content.isEmpty()) {
            throw new IOException("OAuth token file is empty: " + tokenFile);
        }
        return content;
    }
}
