package io.akka.platformapi;

import kalix.api.projects.v1alpha.Region;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link HttpClient} that dynamically routes Akka control plane REST requests to the
 * correct regional endpoint.
 *
 * <p>On each {@code sendAsync} call it:
 * <ol>
 *   <li>Parses the UUID project ID from the URI path.</li>
 *   <li>Looks up the project's regions (using the cached fetcher).</li>
 *   <li>Selects the target region (primary, or the explicit one set at construction time,
 *       failing if the explicit region is not associated with the project).</li>
 *   <li>Replaces the request URI's scheme and authority with the region endpoint.</li>
 *   <li>Injects the current access token as an {@code Authorization: Bearer} header.</li>
 * </ol>
 *
 * <p>Recognised URI path patterns:
 * <ul>
 *   <li>{@code /apis/<group>/<version>/namespaces/<UUID>/...}</li>
 *   <li>{@code /api/<version>/namespaces/<UUID>/...}</li>
 * </ul>
 */
final class ControlPlaneHttpClient extends HttpClient {

    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile(
            "^/(?:apis/[^/]+/[^/]+|api/[^/]+)/namespaces/" +
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:/|$)");

    private final HttpClient delegate;
    private final AccessTokenCache tokenCache;
    private final Function<String, CompletableFuture<List<Region>>> regionsFetcher;
    /** {@code null} means auto-route to the primary region. */
    private final String explicitRegionName;

    /** Auto-routing mode: routes each request to the project's primary region. */
    ControlPlaneHttpClient(
            HttpClient delegate,
            AccessTokenCache tokenCache,
            Function<String, CompletableFuture<List<Region>>> regionsFetcher) {
        this(delegate, tokenCache, regionsFetcher, null);
    }

    /**
     * Explicit-region mode: routes all requests to {@code explicitRegionName}, validating
     * per-call that the project extracted from the URI has that region.
     */
    ControlPlaneHttpClient(
            HttpClient delegate,
            AccessTokenCache tokenCache,
            Function<String, CompletableFuture<List<Region>>> regionsFetcher,
            String explicitRegionName) {
        this.delegate = delegate;
        this.tokenCache = tokenCache;
        this.regionsFetcher = regionsFetcher;
        this.explicitRegionName = explicitRegionName;
    }

    // -------------------------------------------------------------------------
    // Routing sendAsync overrides
    // -------------------------------------------------------------------------

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return buildRoutedRequest(request)
                .thenCompose(routed -> delegate.sendAsync(routed, responseBodyHandler));
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        return buildRoutedRequest(request)
                .thenCompose(routed -> delegate.sendAsync(routed, responseBodyHandler, pushPromiseHandler));
    }

    // The generated ApiClient uses asyncNative=true so only sendAsync is exercised in practice.
    @Override
    public <T> HttpResponse<T> send(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return delegate.send(request, responseBodyHandler);
    }

    // -------------------------------------------------------------------------
    // Request building
    // -------------------------------------------------------------------------

    private CompletableFuture<HttpRequest> buildRoutedRequest(HttpRequest request) {
        URI original = request.uri();
        String projectId = extractProjectId(original.getPath());
        if (projectId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "No UUID project ID found in control plane URI path: " + original.getPath()));
        }

        CompletableFuture<Region> regionFuture = resolveRegion(projectId);
        CompletableFuture<String> tokenFuture = tokenCache.getToken();

        return regionFuture.thenCombine(tokenFuture, (region, token) -> {
            URI endpoint = URI.create(region.getEndpoint());
            try {
                URI routed = new URI(
                        endpoint.getScheme(), endpoint.getAuthority(),
                        original.getPath(), original.getRawQuery(), original.getFragment());
                return HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("Authorization"))
                        .uri(routed)
                        .header("Authorization", "Bearer " + token)
                        .build();
            } catch (URISyntaxException e) {
                throw new RuntimeException("Failed to construct routed URI for endpoint " + endpoint, e);
            }
        });
    }

    private CompletableFuture<Region> resolveRegion(String projectId) {
        return regionsFetcher.apply(projectId).thenApply(regions -> {
            if (explicitRegionName == null) {
                return regions.stream()
                        .filter(Region::getPrimary)
                        .findFirst()
                        .orElseGet(() -> {
                            if (regions.isEmpty()) {
                                throw new IllegalStateException(
                                        "No regions found for project " + projectId);
                            }
                            return regions.get(0);
                        });
            } else {
                String regionResourceName = "projects/%s/regions/%s".formatted(projectId, explicitRegionName);
                return regions.stream()
                        .filter(r -> r.getName().equals(regionResourceName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Region '" + explicitRegionName + "' is not associated with project '"
                                + projectId + "'"));
            }
        });
    }

    private static String extractProjectId(String path) {
        Matcher m = PROJECT_ID_PATTERN.matcher(path);
        return m.find() ? m.group(1) : null;
    }

    // -------------------------------------------------------------------------
    // Delegation for all other HttpClient methods
    // -------------------------------------------------------------------------

    @Override public Optional<CookieHandler> cookieHandler()  { return delegate.cookieHandler(); }
    @Override public Optional<Duration>       connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect                 followRedirects(){ return delegate.followRedirects(); }
    @Override public Optional<ProxySelector>  proxy()         { return delegate.proxy(); }
    @Override public SSLContext               sslContext()     { return delegate.sslContext(); }
    @Override public SSLParameters            sslParameters()  { return delegate.sslParameters(); }
    @Override public Optional<Authenticator>  authenticator()  { return delegate.authenticator(); }
    @Override public Version                  version()        { return delegate.version(); }
    @Override public Optional<Executor>       executor()       { return delegate.executor(); }
    @Override public void                     close()          { delegate.close(); }
}
