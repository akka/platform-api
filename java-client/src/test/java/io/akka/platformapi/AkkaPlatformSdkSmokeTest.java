package io.akka.platformapi;

public class AkkaPlatformSdkSmokeTest {
    public static void main(String... args) throws Exception {
        try (var sdk = AkkaPlatformSdk.create()) {
            String projectId = sdk.resolveProjectId("james-aws").toCompletableFuture().get();

            System.out.println(sdk.controlPlaneApi().listServices(projectId, null).toCompletableFuture().get());
        }

    }
}
