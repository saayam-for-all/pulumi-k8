package com.saayam.k8;

import com.pulumi.core.Output;
import com.pulumi.kubernetes.apps.v1.Deployment;
import com.pulumi.kubernetes.apps.v1.DeploymentArgs;
import com.pulumi.kubernetes.core.v1.Secret;
import com.pulumi.kubernetes.core.v1.SecretArgs;
import com.pulumi.kubernetes.core.v1.Service;
import com.pulumi.kubernetes.core.v1.ServiceArgs;
import com.pulumi.kubernetes.apps.v1.inputs.DeploymentSpecArgs;
import com.pulumi.kubernetes.core.v1.inputs.*;
import com.pulumi.kubernetes.meta.v1.inputs.ObjectMetaArgs;
import com.pulumi.kubernetes.meta.v1.inputs.LabelSelectorArgs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pulumi.kubernetes.networking.v1.Ingress;
import com.pulumi.kubernetes.networking.v1.IngressArgs;
import com.pulumi.kubernetes.networking.v1.inputs.*;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.CustomResourceOptions;

public class Application extends ComponentResource {
  public Application(String name,
                     Environment environment,
                     Output<String> image,
                     Output<Optional<String>> databasePasswordOutput,
                     AlbIngressController ingressController) {
    super("timely:infrastructure:Application", name);
    CustomResourceOptions parent = CustomResourceOptions.builder().parent(this).build();
    String deploymentName = name + "-deployment";
    String serviceName = name + "-service";
    String ingressName = name + "-ingress";
    String secretName = name + "-db-credentials";

    Output<Boolean> createdDBSecret = CreateDatabaseSecretIfNeeded(databasePasswordOutput, secretName);

    var appLabels = Map.of("app", serviceName);

    CreateDeployment(
        name,
        environment,
        image,
        deploymentName,
        appLabels,
        secretName,
        createdDBSecret,
        parent);

    CreateService(serviceName, appLabels, parent);

    CreateIngress(ingressName, serviceName, this, ingressController);
  }

  private static Output<Boolean> CreateDatabaseSecretIfNeeded(
      Output<Optional<String>> databasePasswordOutput, String secretName) {
    return databasePasswordOutput.applyValue(
        databasePassword -> {
          if (databasePassword.isPresent()) {
              var secret = new Secret(secretName,
                  SecretArgs
                      .builder()
                      .metadata(ObjectMetaArgs.builder()
                          .name(secretName)
                          .build())
                      .stringData(Map.of("password", databasePassword.orElseThrow()))
                      .build());
              return true;
            }
            return false;
        });
  }

  private static void CreateDeployment(
      String name,
      Environment environment,
      Output<String> image,
      String deploymentName,
      Map<String, String> appLabels,
      String secretName,
      Output<Boolean> createdDatabaseSecret,
      CustomResourceOptions parent) {
    ProbeArgs livenessProbe = ProbeArgs.builder()
        .httpGet(HTTPGetActionArgs.builder()
            .path("/actuator/health")
            .port(8080)
            .build())
        .initialDelaySeconds(15)
        .periodSeconds(10)
        .build();

    ProbeArgs readinessProbe = ProbeArgs.builder()
        .httpGet(HTTPGetActionArgs.builder()
            .path("/actuator/health")
            .port(8080)
            .build())
        .initialDelaySeconds(5)
        .periodSeconds(5)
        .build();

    // Just configure a DB password if needed
    Output<List<EnvVarArgs>> environmentList = Output.all(List.of(
        Output.of(EnvVarArgs.builder()
            .name("SPRING_PROFILES_ACTIVE")
            .value(environment.name())
            .build()),
        createdDatabaseSecret.applyValue(hasDBSecret ->
           hasDBSecret
              ? EnvVarArgs.builder()
                  .name("DB_PASSWORD")
                  .valueFrom(
                      EnvVarSourceArgs.builder()
                          .secretKeyRef(
                              SecretKeySelectorArgs.builder()
                                  .name(secretName)
                                  .key("password")
                                  .build())
                          .build())
                  .build()
              : EnvVarArgs
                .builder()
                .value("NO-DATABASE-CONFIGURED")
                .build())));

    var deployment = new Deployment(deploymentName,
        DeploymentArgs.builder()
            .metadata(ObjectMetaArgs.builder()
                .name(deploymentName)
                .build())
            .spec(DeploymentSpecArgs.builder()
                .selector(LabelSelectorArgs.builder()
                    .matchLabels(appLabels)
                    .build())
                .replicas(2) // Number of replicas
                .template(PodTemplateSpecArgs.builder()
                    .metadata(ObjectMetaArgs.builder()
                        .labels(appLabels)
                        .build())
                    .spec(PodSpecArgs.builder()
                        .containers(List.of(ContainerArgs.builder()
                            .name(name)
                            .image(image)
                            .livenessProbe(livenessProbe)
                            .readinessProbe(readinessProbe)
                            .ports(List.of(ContainerPortArgs.builder()
                                .containerPort(80)
                                .build()))
                            .env(environmentList)
                            .build()))
                        .build())
                    .build())
                .build())
            .build(),
        parent);
  }

  private static void CreateIngress(
      String ingressName,
      String serviceName,
      Application application,
      AlbIngressController ingressController) {
    Ingress ingress = new Ingress(ingressName,
        IngressArgs.builder()
            .metadata(ObjectMetaArgs.builder()
                .name(ingressName)
                .annotations(Map.of(
                    "kubernetes.io/ingress.class", "alb",
                    "alb.ingress.kubernetes.io/scheme", "internet-facing",
                    "alb.ingress.kubernetes.io/target-type", "ip",
                    "alb.ingress.kubernetes.io/load-balances-attributes",
                        "idle_timeout.timeout_seconds=120",
                   "alb.ingress.kubernetes.io/healthcheck-path",
                        "/actuator/health"
                ))
                .build())
            .spec(IngressSpecArgs.builder()
                .rules(IngressRuleArgs.builder()
                    .http(HTTPIngressRuleValueArgs.builder()
                        .paths(HTTPIngressPathArgs.builder()
                            .path("/")
                            .pathType("Prefix")
                            .backend(IngressBackendArgs.builder()
                                .service(IngressServiceBackendArgs.builder()
                                    .name(serviceName)
                                    .port(ServiceBackendPortArgs.builder()
                                        .number(80)
                                        .build())
                                    .build())
                                .build())
                            .build())
                        .build())
                    .build())
                .build())
            .build(),
        CustomResourceOptions.builder()
            .parent(application)
            .dependsOn(ingressController)
            .build());
  }

  private static void CreateService(
      String serviceName,
      Map<String, String> appLabels,
      CustomResourceOptions parent) {
    var service = new Service(serviceName,
        ServiceArgs.builder()
            .metadata(ObjectMetaArgs.builder()
                .name(serviceName)
                .build())
            .spec(ServiceSpecArgs.builder()
                .selector(appLabels)
                .ports(List.of(ServicePortArgs.builder()
                    .port(80)
                    .targetPort(8080)
                    .protocol("TCP")
                    .build()))
                .build())
            .build(),
        parent);
  }
}
