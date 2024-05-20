package com.saayam.k8;

import com.google.common.collect.ImmutableList;
import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.kubernetes.Provider;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.resources.StackReference;

public class App {
    private static final String APPLICATION_PREFIX = "application:";
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            String infraStackString = ctx.config().require("infra-stack");
            Environment environment = Environment.valueOf(ctx.config().require("environment"));
            StackReference infraStack = new StackReference(infraStackString);

            Provider k8sProvider = new Provider("k8sProvider",
                ProviderArgs.builder()
                    .build());

            AlbIngressController albIngressController = new AlbIngressController("core",
                infraStack.output("eksClusterName")
                        .applyValue(String::valueOf),
                infraStack.output("eksClusterEndpointOidcIssuerUrl")
                    .applyValue(String::valueOf));

            ImmutableList<EnabledApplication> applications
                = EnabledApplication.read(ctx.config().require("enabled-applications"));

            applications.forEach(
                application ->
                    new Application(
                        application.name(),
                        environment,
                        Output.of(infraStack.getValueAsync(APPLICATION_PREFIX + application.name())
                            .thenApply(repositoryURL -> repositoryURL + ":" + application.tag())),
                        ctx.config().getSecret(application.name() + "-database-password"),
                        albIngressController
                    ));
        });
    }
}
