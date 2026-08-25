# Akka Platform API

Libraries and schemas for interacting with the Akka Platform API. The API has two planes:

- **Federation plane** — a gRPC API for managing projects, organizations, billing, users, and auth. Served at `api.kalix.io:443`.
- **Control plane** — a REST API (OpenAPI) for managing resources within a project (services, routes, secrets, etc.). Served at a region-specific endpoint, e.g. `https://api.gcp-us-east1.akka.io`.

## Authentication

All API calls require a short-lived access token obtained by one of the following mechanisms.

**Refresh token** — a long-lived token exchanged for a short-lived access token via the Auth API. There are two kinds:

- *User token* — grants access to all projects belonging to your user account:
  ```sh
  akka auth token create --description 'My refresh token'
  ```
- *Service token* — grants access to a single project; suitable for CI/CD pipelines:
  ```sh
  akka service-account token create --description 'My service token'
  ```

Store the token value securely. It is shown only once.

**OAuth token exchange (RFC 8693)** — exchange an existing identity token (e.g. a GitHub Actions OIDC token or a workload identity token) for an Akka access token. No long-lived Akka credential needs to be stored. The audience parameter identifies the configured OpenID Connect identity provider and is required.

---

## Java client

The `java-client` module provides a ready-to-use SDK that handles token acquisition and caching, gRPC channel management, and automatic routing of control plane requests to the correct regional endpoint.

### Installation

Akka artifacts are served from Akka's secured maven repository, which requires a token. Generate a token and copy the exact repository configuration for your build tool from **https://account.akka.io/token**.

Then depend on `io.akka:akka-platform-api-java-client`, replacing `VERSION` with the [latest release](https://github.com/akka/platform-api/releases):

**Maven**

```xml
<dependency>
  <groupId>io.akka</groupId>
  <artifactId>akka-platform-api-java-client</artifactId>
  <version>VERSION</version>
</dependency>
```

**Gradle**

```kotlin
dependencies {
    implementation("io.akka:akka-platform-api-java-client:VERSION")
}
```

**sbt**

```scala
libraryDependencies += "io.akka" % "akka-platform-api-java-client" % "VERSION"
```

### Configuration

**Refresh token — environment variable**

Set `AKKA_TOKEN` before starting your application. Optionally set `AKKA_API_HOST` to override the federation plane host (defaults to `api.kalix.io:443`).

```sh
export AKKA_TOKEN=<your-refresh-token>
```

```java
AkkaPlatformSdk sdk = AkkaPlatformSdk.create();
```

**Refresh token — automatic fallback to the Akka CLI**

If `AKKA_TOKEN` is not set and the `akka` CLI is installed and logged in, the SDK will run `akka config get refresh-token` (and `akka config get api-server-host` for non-default installations) automatically. No code changes are needed.

**Refresh token — programmatic**

```java
AkkaPlatformSdk sdk = AkkaPlatformSdk.create(
    AkkaPlatformSdkConfig.withRefreshToken("your-refresh-token"));
```

To also override the federation plane host:

```java
AkkaPlatformSdk sdk = AkkaPlatformSdk.create(
    AkkaPlatformSdkConfig.withRefreshToken("your-refresh-token", "api.kalix.io:443"));
```

**OAuth token exchange — environment variables**

Set `AKKA_OAUTH_TOKEN` (or `AKKA_OAUTH_TOKEN_FILE` to point to a file containing the token) and `AKKA_OAUTH_TOKEN_AUDIENCE`. When either OAuth variable is present it takes precedence over `AKKA_TOKEN`. If the audience variable is missing the SDK throws at startup.

```sh
export AKKA_OAUTH_TOKEN="$(cat /var/run/secrets/token)"   # or a static value
export AKKA_OAUTH_TOKEN_AUDIENCE="regions/gcp-us-east1"
# -- or --
export AKKA_OAUTH_TOKEN_FILE=/var/run/secrets/token
export AKKA_OAUTH_TOKEN_AUDIENCE="regions/gcp-us-east1"
```

```java
AkkaPlatformSdk sdk = AkkaPlatformSdk.create();  // picks up the env vars automatically
```

When `AKKA_OAUTH_TOKEN_FILE` is used the file is re-read on every token exchange, so rotating the file contents is sufficient to rotate the credential without restarting the application.

> **Akka services:** if workload identity has been enabled for an Akka service, these environment variables are injected automatically into the service at runtime. Calling `AkkaPlatformSdk.create()` with no further configuration is sufficient.

**OAuth token exchange — programmatic**

```java
// Fixed token value
AkkaPlatformSdk sdk = AkkaPlatformSdk.create(
    AkkaPlatformSdkConfig.withOAuthToken(myOidcToken, "regions/gcp-us-east1"));

// File-based (re-read on every exchange)
AkkaPlatformSdk sdk = AkkaPlatformSdk.create(
    AkkaPlatformSdkConfig.withOAuthTokenFile("/var/run/secrets/token", "regions/gcp-us-east1"));
```

**Custom token fetcher**

For authentication mechanisms not covered above, implement both `TokenConfig` and `TokenFetcher` on the same class. `TokenFetcher.getToken()` must return a `CompletableFuture<TokenWithExpiry>` containing a bearer token and its expiry instant. The SDK caches the token until 60 seconds before expiry and then calls `getToken()` again.

```java
public class MyTokenConfig implements TokenConfig, TokenFetcher {
    @Override
    public CompletableFuture<TokenWithExpiry> getToken() {
        return fetchMyToken().thenApply(t ->
            new TokenWithExpiry(t.value(), t.expiresAt()));
    }
}

AkkaPlatformSdk sdk = AkkaPlatformSdk.create(
    AkkaPlatformSdkConfig.of(new MyTokenConfig(), "api.kalix.io:443"));
```

### Looking up a project

Projects are identified by a UUID internally. Use `resolveProjectId` to translate a friendly name to the UUID required by the control plane API:

```java
sdk.resolveProjectId("my-project")
    .thenAccept(projectId -> System.out.println("Project ID: " + projectId));
```

To list all projects directly:

```java
sdk.projects()
    .listProjects(ListProjectsRequest.newBuilder().build())
    .thenAccept(response ->
        response.getProjectsList().forEach(p ->
            System.out.println(p.getFriendlyName() + " → " + p.getName())));
```

### Getting a service

The control plane API is accessed via `sdk.controlPlaneApi()`. Each call is automatically routed to the project's primary region — the project UUID is parsed from the request URI, the region list is fetched and cached, and the request is forwarded to the correct regional endpoint.

```java
AkkaControlPlaneApi api = sdk.controlPlaneApi();

sdk.resolveProjectId("my-project").thenCompose(projectId ->
    api.getService("my-service", projectId)
).thenAccept(service ->
    System.out.println(service.getMetadata().getName()
        + " — " + service.getStatus()));
```

To list all services in a project:

```java
sdk.resolveProjectId("my-project").thenCompose(projectId ->
    api.listServices(projectId, null)
).thenAccept(list ->
    list.getItems().forEach(s -> System.out.println(s.getMetadata().getName())));
```

### Creating a service

```java
import io.akka.platformapi.controlplane.model.*;

Service service = new Service()
    .metadata(new ObjectMeta()
        .name("my-service"))
    .spec(new ServiceSpec()
        .containers(List.of(new ServiceSpecContainer()
            .name("main")
            .image("my-registry.example.com/my-image:1.0.0"))));

sdk.resolveProjectId("my-project").thenCompose(projectId ->
    api.createService(projectId, service)
).thenAccept(created ->
    System.out.println("Created: " + created.getMetadata().getName()));
```

### Explicit region routing

By default `controlPlaneApi()` routes to the primary region. To target a specific region (e.g. for multi-region projects):

```java
// Region name format: "<region-id>" e.g. "gcp-us-east1"
AkkaControlPlaneApi regionalApi = sdk.controlPlaneApiForRegion("gcp-us-east1");
```

If the specified region is not associated with the project identified in the request URI, the call will fail asynchronously with `IllegalArgumentException`.

Region information is cached. After a region configuration change, call `sdk.clearRegionCache()` to force a fresh lookup on the next request.

### Lifecycle

Close the SDK when your application shuts down to release the underlying gRPC channels and (if applicable) the managed `ActorSystem`:

```java
sdk.close();
```

---

## Using the schemas directly

The federation plane protobuf schemas are in `schemas/federation-plane/protobuf/` and the control plane OpenAPI schema is at `schemas/control-plane/openapi/api.json`. The following describes the authentication and region discovery flow for clients that use these schemas without the Java SDK.

### Authenticating

All requests require a short-lived access token. Exchange your refresh token for one by calling `CreateAccessToken` on the Auth service (`api.kalix.io:443`, TLS):

```
Service:  kalix.api.auth.v1alpha.Auth
Method:   CreateAccessToken
Request:  CreateAccessTokenRequest {}   (empty body)
Header:   Authorization: Bearer <refresh-token>
```

The response `AccessToken.token` is your access token. Send it as `Authorization: Bearer <access-token>` on all subsequent requests. Tokens are short-lived — cache them and refresh before `expire_time`.

### Region endpoint discovery

Control plane REST requests must be directed to the region-specific endpoint for the project, not to `api.kalix.io`. Discover the endpoint by calling `ListProjects` on the Projects service (authenticated with your access token):

```
Service:  kalix.api.projects.v1alpha.Projects
Method:   ListProjects
Request:  ListProjectsRequest {}
Header:   Authorization: Bearer <access-token>
```

Each `Project` in the response contains a `regions` list. Each `Region` has:

- `endpoint` — the base URL for control plane REST requests, e.g. `https://api.gcp-us-east1.akka.io`
- `primary` — `true` for the region that should be used by default
- `name` — the fully-qualified resource name, e.g. `projects/<id>/regions/gcp-us-east1`

In most cases you want the region where `primary == true`. Use its `endpoint` as the base URL for all control plane REST calls for that project:

```
GET https://api.gcp-us-east1.akka.io/apis/kalix.io/v1alpha1/namespaces/<project-id>/kalixservices
Authorization: Bearer <access-token>
```

If you already have the project ID, you can also call `ListRegions` directly with `parent = "projects/<project-id>"` instead of iterating through `ListProjects`.

Page through `ListProjects` using the `next_page_token` field in the response until it is empty, as projects are returned in pages.
