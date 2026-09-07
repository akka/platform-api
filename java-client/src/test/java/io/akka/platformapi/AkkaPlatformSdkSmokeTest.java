package io.akka.platformapi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the SDK against a real Akka control plane. Authentication and target host are
 * resolved by {@link AkkaPlatformSdkConfig#loadDefault()}: locally this typically comes from the
 * {@code akka} CLI login, in CI it comes from the {@code AKKA_OAUTH_TOKEN}/{@code AKKA_API_HOST}
 * environment variables set up via GitHub's OIDC workload identity (see {@code ci.yml}).
 *
 * <p>The {@code e2e-testing-apps} project is available in dev, test and stage, so this test
 * passes regardless of which of those environments it is pointed at.
 */
@Tag("smoke")
class AkkaPlatformSdkSmokeTest {

    private static final String PROJECT_NAME = "e2e-testing-apps";
    private static final String EXPECTED_SERVICE = "the-ubiquitous-shopping-cart-akka-sdk";

    @Test
    void listServicesIncludesExpectedService() throws Exception {
        try (var sdk = AkkaPlatformSdk.create()) {
            String projectId = sdk.resolveProjectId(PROJECT_NAME).toCompletableFuture().get();

            var services = sdk.controlPlaneApi().listServices(projectId, null).toCompletableFuture().get();

            List<String> serviceNames = services.getItems().stream()
                    .map(service -> service.getMetadata().getName())
                    .toList();

            assertTrue(
                    serviceNames.contains(EXPECTED_SERVICE),
                    "Expected service '" + EXPECTED_SERVICE + "' not found. Found: " + serviceNames);
        }
    }
}
