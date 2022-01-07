import { HealthCheck } from "@aws-cdk/aws-autoscaling";
import {
  ApplicationProtocol,
  ListenerAction,
  TargetType,
} from "@aws-cdk/aws-elasticloadbalancingv2";
import type { App } from "@aws-cdk/core";
import { Duration } from "@aws-cdk/core";
import { GuAutoScalingGroup } from "@guardian/cdk/lib/constructs/autoscaling";
import { GuInstanceRole } from "@guardian/cdk/lib/constructs/iam";
import {
  GuArnParameter,
  GuParameter,
  GuStringParameter,
} from "@guardian/cdk/lib/constructs/core";
import type { GuStackProps } from "@guardian/cdk/lib/constructs/core/stack";
import { GuStack } from "@guardian/cdk/lib/constructs/core/stack";
import {
  GuPublicInternetAccessSecurityGroup,
  GuSecurityGroup,
  GuVpc,
} from "@guardian/cdk/lib/constructs/ec2";
import {
  GuApplicationListener,
  GuApplicationLoadBalancer,
  GuApplicationTargetGroup,
} from "@guardian/cdk/lib/constructs/loadbalancing";
import { GuGetS3ObjectPolicy } from "@guardian/cdk/lib/constructs/iam";
import { InstanceType, Port } from "@aws-cdk/aws-ec2";
import { Stage } from "@guardian/cdk/lib/constants";

export class RuleManager extends GuStack {
  constructor(scope: App, id: string, props: GuStackProps) {
    super(scope, id, props);

    const parameters = {
      VPC: new GuParameter(this, "VPC", {
        type: "AWS::SSM::Parameter::Value<AWS::EC2::VPC::Id>",
        description: "Virtual Private Cloud to run EC2 instances within",
        default: "/account/vpc/default/id",
      }),
      PublicSubnets: new GuParameter(this, "PublicSubnets", {
        type: "AWS::SSM::Parameter::Value<List<AWS::EC2::Subnet::Id>>",
        description: "Subnets to run load balancer within",
        default: "/account/vpc/default/public.subnets",
      }),
      PrivateSubnets: new GuParameter(this, "PrivateSubnets", {
        type: "AWS::SSM::Parameter::Value<List<AWS::EC2::Subnet::Id>>",
        description: "Subnets to run the ASG and instances within",
        default: "/account/vpc/default/private.subnets",
      }),
      TLSCert: new GuArnParameter(this, "TLSCert", {
        description: "ARN of a TLS certificate to install on the load balancer",
      }),
    };

    const vpc = GuVpc.fromId(this, "vpc", { vpcId: parameters.VPC.valueAsString } );

    const pandaAuthPolicy = new GuGetS3ObjectPolicy(this, "PandaAuthPolicy", {
      bucketName: "pan-domain-auth-settings",
    });

    const ruleManagerRole = new GuInstanceRole(this, "RuleManagerRole", {
      additionalPolicies: [pandaAuthPolicy],
    });

    const targetGroup = new GuApplicationTargetGroup(
      this,
      "PublicTargetGroup",
      {
        vpc: vpc,
        port: 9000,
        protocol: ApplicationProtocol.HTTP,
        targetType: TargetType.INSTANCE,
        deregistrationDelay: Duration.seconds(30),
      }
    );

    const privateSubnets = GuVpc.subnets(
      this,
      parameters.PrivateSubnets.valueAsList
    );
    const publicSubnets = GuVpc.subnets(
      this,
      parameters.PublicSubnets.valueAsList
    );

    const loadBalancer = new GuApplicationLoadBalancer(
      this,
      "PublicLoadBalancer",
      {
        vpc,
        internetFacing: true,
        vpcSubnets: { subnets: publicSubnets },
        securityGroup: new GuPublicInternetAccessSecurityGroup(
          this,
          "LoadBalancerSecurityGroup",
          { allowAllOutbound: false, vpc }
        ),
      }
    );

    new GuApplicationListener(this, "PublicListener", {
      loadBalancer,
      certificates: [{ certificateArn: parameters.TLSCert.valueAsString }],
      defaultAction: ListenerAction.forward([targetGroup]),
      open: false,
    });

    const appSecurityGroup = new GuSecurityGroup(
      this,
      "ApplicationSecurityGroup",
      {
        description: "HTTP",
        vpc,
        allowAllOutbound: true,
      }
    )

    appSecurityGroup.connections.allowFrom(loadBalancer, Port.tcp(9000), "Port 9000 LB to fleet");

    const userData = `#!/bin/bash -ev
aws --quiet --region ${this.region} s3 cp s3://composer-dist/${this.stack}/${this.stage}/typerighter-rule-manager/typerighter-rule-manager.deb /tmp/package.deb
dpkg -i /tmp/package.deb`;

    new GuAutoScalingGroup(this, "AutoscalingGroup", {
      vpc,
      vpcSubnets: { subnets: privateSubnets },
      role: ruleManagerRole,
      userData: userData,
      instanceType: new InstanceType("t4g.micro"),
      stageDependentProps: {
        [Stage.CODE]: {
          minimumInstances: 1
        },
        [Stage.PROD]: {
          minimumInstances: 1
        }
      },
      healthCheck: HealthCheck.elb({
        grace: Duration.minutes(5),
      }),
      targetGroup,
      additionalSecurityGroups: [appSecurityGroup],
      associatePublicIpAddress: false,
    });
  }
}
