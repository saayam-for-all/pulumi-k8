package com.saayam.k8;

import com.pulumi.aws.iam.*;
import com.pulumi.core.Output;
import com.pulumi.kubernetes.helm.v3.Release;
import com.pulumi.kubernetes.helm.v3.ReleaseArgs;
import com.pulumi.kubernetes.helm.v3.inputs.RepositoryOptsArgs;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.CustomResourceOptions;

import java.util.Map;

public class AlbIngressController extends ComponentResource {

  public AlbIngressController(
      String name, Output<String> clusterName, Output<String> oidcIssuerUrl) {
    super("timely:infrastructure:AlbIngressController", name);
    CustomResourceOptions parent = CustomResourceOptions.builder().parent(this).build();
    // Create an IAM policy for the ALB Ingress Controller
    var policy = new Policy(name + "AlbIngressPolicy", PolicyArgs.builder()
        .name(name + "AlbIngressPolicy")
        .policy(
            Output.of("""
                {
                    "Version": "2012-10-17",
                    "Statement": [
                        {
                            "Effect": "Allow",
                            "Action": [
                                "acm:DescribeCertificate",
                                "acm:ListCertificates",
                                "acm:GetCertificate",
                                "ec2:AuthorizeSecurityGroupIngress",
                                "ec2:CreateSecurityGroup",
                                "ec2:CreateTags",
                                "ec2:DeleteTags",
                                "ec2:DeleteSecurityGroup",
                                "ec2:DescribeAccountAttributes",
                                "ec2:DescribeAddresses",
                                "ec2:DescribeAvailabilityZones",
                                "ec2:DescribeInstances",
                                "ec2:DescribeInstanceStatus",
                                "ec2:DescribeInternetGateways",
                                "ec2:DescribeNetworkInterfaces",
                                "ec2:DescribeSecurityGroups",
                                "ec2:DescribeSubnets",
                                "ec2:DescribeTags",
                                "ec2:DescribeVpcs",
                                "ec2:ModifyInstanceAttribute",
                                "ec2:ModifyNetworkInterfaceAttribute",
                                "ec2:RevokeSecurityGroupIngress",
                                "elasticloadbalancing:AddListenerCertificates",
                                "elasticloadbalancing:AddTags",
                                "elasticloadbalancing:CreateListener",
                                "elasticloadbalancing:CreateLoadBalancer",
                                "elasticloadbalancing:CreateRule",
                                "elasticloadbalancing:CreateTargetGroup",
                                "elasticloadbalancing:DeleteListener",
                                "elasticloadbalancing:DeleteLoadBalancer",
                                "elasticloadbalancing:DeleteRule",
                                "elasticloadbalancing:DeleteTargetGroup",
                                "elasticloadbalancing:DeregisterTargets",
                                "elasticloadbalancing:DescribeListenerCertificates",
                                "elasticloadbalancing:DescribeListeners",
                                "elasticloadbalancing:DescribeLoadBalancers",
                                "elasticloadbalancing:DescribeLoadBalancerAttributes",
                                "elasticloadbalancing:DescribeRules",
                                "elasticloadbalancing:DescribeSSLPolicies",
                                "elasticloadbalancing:DescribeTags",
                                "elasticloadbalancing:DescribeTargetGroups",
                                "elasticloadbalancing:DescribeTargetGroupAttributes",
                                "elasticloadbalancing:DescribeTargetHealth",
                                "elasticloadbalancing:ModifyListener",
                                "elasticloadbalancing:ModifyLoadBalancerAttributes",
                                "elasticloadbalancing:ModifyRule",
                                "elasticloadbalancing:ModifyTargetGroup",
                                "elasticloadbalancing:ModifyTargetGroupAttributes",
                                "elasticloadbalancing:RegisterTargets",
                                "elasticloadbalancing:RemoveListenerCertificates",
                                "elasticloadbalancing:RemoveTags",
                                "elasticloadbalancing:SetIpAddressType",
                                "elasticloadbalancing:SetSecurityGroups",
                                "elasticloadbalancing:SetSubnets",
                                "elasticloadbalancing:SetWebACL",
                                "wafv2:GetWebACLForResource",
                                "waf-regional:GetWebACLForResource",
                                "s3:PutObject",
                                "s3:GetBucketAcl"
                            ],
                            "Resource": "*"
                        }
                    ]
                }
                """))
        .build(), parent);

    // Create an IAM role and attach the policy
    var role = new Role(name + "AlbIngressRole", RoleArgs.builder()
        .name(name + "AlbIngressRole")
        .assumeRolePolicy(
            oidcIssuerUrl.apply(oidc -> {
              String parsedOidc = oidc.substring("https://".length());
              // TODO(dharminder): Add account id below
              return Output.of(
                  String.format("""
                  {
                      "Version": "2012-10-17",
                      "Statement": [
                          {
                              "Effect": "Allow",
                              "Principal": {
                                  "Federated": "arn:aws:iam::<Add accountid>:oidc-provider/%s"
                              },
                              "Action": "sts:AssumeRoleWithWebIdentity",
                              "Condition": {
                                  "StringEquals": {
                                      "%s:sub": "system:serviceaccount:kube-system:aws-load-balancer-controller"
                                  }
                              }
                          }
                      ]
                  }
                  """, parsedOidc, parsedOidc));
            }))
        .build(), parent);

    new RolePolicyAttachment(name +"RolePolicyAttachment",
        RolePolicyAttachmentArgs.builder()
            .role(role.name())
            .policyArn(policy.arn())
            .build(), parent);

    Output<Map<String, Object>> valuesMap = clusterName.applyValue(c -> Map.of(
        "clusterName", c,
        "serviceAccount", Map.of(
            "create", true,
            "name", "aws-load-balancer-controller",
            "annotations", Map.of(
                "eks.amazonaws.com/role-arn",
                // TODO(dharminder): Add aws account id below.
                "arn:aws:iam::<account-id>:role/coreAlbIngressRole",
                "alb.ingress.kubernetes.io/healthcheck-path", "/actuator/health",
                "alb.ingress.kubernetes.io/success-codes", "200")
        )));


    var albControllerChart = new Release(
        "aws-load-balancer-controller",
        ReleaseArgs.builder()
            .chart("aws-load-balancer-controller")
            .repositoryOpts(
                RepositoryOptsArgs.builder()
                    .repo("https://aws.github.io/eks-charts")
                    .build())
            .version("1.4.0")
            .namespace("kube-system")
            .values(valuesMap)
            .build(),
        parent);
  }
}
