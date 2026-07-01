package io.akka.platformapi;

import akka.actor.ActorSystem;
import akka.grpc.GrpcClientSettings;
import io.akka.platformapi.controlplane.ApiClient;
import io.akka.platformapi.controlplane.api.AkkaControlPlaneApi;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.io.Closeable;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import kalix.api.auth.v1alpha.AuthClient;
import kalix.api.organizations.v1alpha.OrganizationsClient;
import kalix.api.projects.v1alpha.ListProjectsRequest;
import kalix.api.projects.v1alpha.ListRegionsRequest;
import kalix.api.projects.v1alpha.ListRegionsResponse;
import kalix.api.projects.v1alpha.ProjectsClient;
import kalix.api.projects.v1alpha.Region;
import kalix.api.users.v1alpha.UsersClient;

/**
 * Entry point for the Akka Platform SDK.
 *
 * <p>Provides authenticated access to:
 * <ul>
 *   <li>Federation plane gRPC services (Auth, Projects, Billing, etc.) via {@code CompletionStage<XxxClient>} methods</li>
 *   <li>Control plane REST API ({@link AkkaControlPlaneApi}) auto-routed to the correct regional endpoint</li>
 * </ul>
 *
 * <p>Access tokens are obtained automatically from the configured refresh token and cached
 * until 60 seconds before expiry.
 *
 * <p>Usage:
 * <pre>{@code
 * AkkaPlatformSdk sdk = AkkaPlatformSdk.create();  // reads AKKA_TOKEN, AKKA_API_HOST
 * sdk.projects().thenCompose(c -> c.listProjects(ListProjectsRequest.newBuilder().build()))
 *    .thenAccept(System.out::println);
 * sdk.controlPlaneApi("my-project").thenCompose(api -> api.listServices("my-project", ...));
 * sdk.close();
 * }</pre>
 *
 * <p>The gRPC clients returned by methods like {@link #projects()} are ephemeral wrappers
 * over a shared channel. Do not call {@code close()} on them; use {@link #close()} on
 * the SDK instance instead.
 */
public final class AkkaPlatformSdk implements Closeable {

    private final ActorSystem system;
    private final boolean ownSystem;
    private final AccessTokenCache tokenCache;
    private final AuthClient authClient;
    private final ProjectsClient projectsClient;
    private final OrganizationsClient organizationsClient;
    private final UsersClient usersClient;
    private final ConcurrentHashMap<String, CompletableFuture<List<Region>>> regionCache =
            new ConcurrentHashMap<>();
    private final HttpClient baseHttpClient;
    private final AkkaControlPlaneApi controlPlaneApiInstance;

    /** Creates an SDK instance reading configuration from environment variables. */
    public static AkkaPlatformSdk create() {
        return create(AkkaPlatformSdkConfig.loadDefault());
    }

    /** Creates an SDK instance with the given configuration, managing its own {@link ActorSystem}. */
    public static AkkaPlatformSdk create(AkkaPlatformSdkConfig config) {
        ActorSystem system = ActorSystem.create("akka-platform-sdk");
        return new AkkaPlatformSdk(config, system, true);
    }

    /**
     * Creates an SDK instance with the given configuration and a caller-supplied {@link ActorSystem}.
     * The system will not be terminated when {@link #close()} is called.
     */
    public static AkkaPlatformSdk create(AkkaPlatformSdkConfig config, ActorSystem system) {
        return new AkkaPlatformSdk(config, system, false);
    }

    private static Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private AkkaPlatformSdk(AkkaPlatformSdkConfig config, ActorSystem system, boolean ownSystem) {
        this.system = system;
        this.ownSystem = ownSystem;

        String apiHost = config.getApiHost();
        String host;
        int port;
        int colonIdx = apiHost.lastIndexOf(':');
        if (colonIdx > 0) {
            host = apiHost.substring(0, colonIdx);
            port = Integer.parseInt(apiHost.substring(colonIdx + 1));
        } else {
            host = apiHost;
            port = 443;
        }

        GrpcClientSettings unauthedSettings = GrpcClientSettings
                .connectToServiceAt(host, port, system)
                .withTls(true);
        var unauthedAuthClient = AuthClient.create(unauthedSettings, system);

        TokenConfig tokenConfig = config.getTokenConfig();
        TokenFetcher fetcher;
        if (tokenConfig instanceof TokenFetcher tf) {
            fetcher = tf;
        } else if (tokenConfig instanceof RefreshTokenConfig rc) {
            fetcher = new RefreshTokenFetcher(unauthedAuthClient, rc.getRefreshToken());
        } else if (tokenConfig instanceof OAuthTokenConfig oc) {
            fetcher = new OAuthTokenFetcher(unauthedAuthClient, oc);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported token type: " + tokenConfig.getClass().getSimpleName() +
                    ". Implement TokenFetcher to provide a custom authentication mechanism.");
        }
        this.tokenCache = new AccessTokenCache(fetcher);

        GrpcClientSettings settings = unauthedSettings.withCallCredentials(new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                tokenCache.getToken().thenAccept(token -> {
                    var metadata = new Metadata();
                    metadata.put(AUTHORIZATION, "Bearer " + token);
                    applier.apply(metadata);
                });
            }
        });


        this.authClient = AuthClient.create(settings, system);
        this.projectsClient = ProjectsClient.create(settings, system);
        this.organizationsClient = OrganizationsClient.create(settings, system);
        this.usersClient = UsersClient.create(settings, system);

        this.baseHttpClient = ApiClient.createDefaultHttpClientBuilder().build();
        this.controlPlaneApiInstance = new AkkaControlPlaneApi(
                new ControlPlaneApiClient(
                        new ControlPlaneHttpClient(baseHttpClient, tokenCache, this::fetchRegions)));
    }

    private static final class ControlPlaneApiClient extends ApiClient {
        private final HttpClient httpClient;

        ControlPlaneApiClient(HttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public HttpClient getHttpClient() {
            return httpClient;
        }
    }

    // -------------------------------------------------------------------------
    // Federation plane gRPC clients
    // -------------------------------------------------------------------------

    /**
     * Returns an {@link AuthClient} authenticated with the current access token.
     * Use this for operations like {@code getCurrentToken} and {@code createContainerRegistryToken}.
     * Token creation/refresh is handled internally by the SDK.
     */
    public AuthClient auth() {
        return authClient;
    }

    /** Returns a {@link ProjectsClient} authenticated with the current access token. */
    public ProjectsClient projects() {
        return projectsClient;
    }

    /** Returns an {@link OrganizationsClient} authenticated with the current access token. */
    public OrganizationsClient organizations() {
        return organizationsClient;
    }

    /** Returns a {@link UsersClient} authenticated with the current access token. */
    public UsersClient users() {
        return usersClient;
    }

    // -------------------------------------------------------------------------
    // Control plane REST API
    // -------------------------------------------------------------------------

    /**
     * Returns the shared {@link AkkaControlPlaneApi} client. Each request is automatically
     * routed to the primary region of the project identified by the UUID in the URI path.
     * Region information is cached; call {@link #clearRegionCache()} after a region
     * configuration change to force a fresh lookup.
     */
    public AkkaControlPlaneApi controlPlaneApi() {
        return controlPlaneApiInstance;
    }

    /**
     * Returns an {@link AkkaControlPlaneApi} client that routes all requests to the
     * specified region. On each call the project is identified from the UUID in the URI path,
     * the project's region list is consulted, and an {@link IllegalArgumentException} is
     * thrown (as a failed {@link java.util.concurrent.CompletableFuture}) if the region is
     * not associated with that project.
     *
     * @param regionName the full region resource name, e.g. {@code projects/<id>/regions/gcp-us-east1}
     */
    public AkkaControlPlaneApi controlPlaneApiForRegion(String regionName) {
        return new AkkaControlPlaneApi(
                new ControlPlaneApiClient(
                        new ControlPlaneHttpClient(baseHttpClient, tokenCache, this::fetchRegions, regionName)));
    }

    /**
     * Resolves a project friendly name to its project ID (the UUID portion of the resource
     * name {@code projects/<id>}).
     *
     * <p>All pages of {@code ListProjects} are fetched until a match is found or the list is
     * exhausted. The returned future completes exceptionally with
     * {@link IllegalArgumentException} if no project with the given friendly name exists.
     *
     * @param friendlyName the human-readable project name as shown in the Akka console
     * @return a {@link CompletionStage} that yields the project UUID string
     */
    public CompletionStage<String> resolveProjectId(String friendlyName) {
        return listProjectsPage(friendlyName, "");
    }

    private CompletionStage<String> listProjectsPage(String friendlyName, String pageToken) {
        var request = ListProjectsRequest.newBuilder();
        if (!pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }
        return projectsClient.listProjects(request.build()).thenCompose(response -> {
            var match = response.getProjectsList().stream()
                    .filter(p -> friendlyName.equals(p.getFriendlyName()))
                    .findFirst();
            if (match.isPresent()) {
                String resourceName = match.get().getName(); // "projects/<uuid>"
                String projectId = resourceName.startsWith("projects/")
                        ? resourceName.substring("projects/".length())
                        : resourceName;
                return CompletableFuture.completedFuture(projectId);
            }
            String nextToken = response.getNextPageToken();
            if (!nextToken.isEmpty()) {
                return listProjectsPage(friendlyName, nextToken);
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "No project found with friendly name: " + friendlyName));
        });
    }

    /**
     * Clears the cached region list for all projects. Call this after a region configuration
     * change (e.g., after adding or changing the primary region) to ensure subsequent
     * {@link #controlPlaneApi} calls use fresh region data.
     */
    public void clearRegionCache() {
        regionCache.clear();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        authClient.close();
        projectsClient.close();
        organizationsClient.close();
        usersClient.close();
        if (ownSystem) {
            system.terminate();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<List<Region>> fetchRegions(String projectId) {
        return regionCache.computeIfAbsent(projectId, pid ->
                projectsClient.listRegions(
                                ListRegionsRequest.newBuilder()
                                        .setParent("projects/" + pid)
                                        .build())
                        .thenApply(ListRegionsResponse::getRegionsList)
                        .toCompletableFuture());
    }
}
