package com.netflix.spinnaker.keel.ec2.resource

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.actuation.Job
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancer
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.Action
import com.netflix.spinnaker.keel.api.ec2.ApplicationLoadBalancerSpec.ApplicationLoadBalancerOverride
import com.netflix.spinnaker.keel.api.ec2.EC2_CLOUD_PROVIDER
import com.netflix.spinnaker.keel.api.ec2.EC2_APPLICATION_LOAD_BALANCER_V1_2
import com.netflix.spinnaker.keel.api.ec2.LoadBalancerDependencies
import com.netflix.spinnaker.keel.api.ec2.Location
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ResourceNotFound
import com.netflix.spinnaker.keel.core.parseMoniker
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.diff.toIndividualDiffs
import com.netflix.spinnaker.keel.ec2.toEc2Api
import com.netflix.spinnaker.keel.ec2.toOrcaRequest
import com.netflix.spinnaker.keel.model.OrcaJob
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import java.time.Duration

class ApplicationLoadBalancerHandler(
  private val cloudDriverService: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache,
  private val orcaService: OrcaService,
  private val taskLauncher: TaskLauncher,
  resolvers: List<Resolver<*>>
) : BaseLoadBalancerHandler<ApplicationLoadBalancerSpec, ApplicationLoadBalancer>(cloudDriverCache, taskLauncher, resolvers) {

  override val supportedKind = EC2_APPLICATION_LOAD_BALANCER_V1_2

  override suspend fun toResolvedType(resource: Resource<ApplicationLoadBalancerSpec>):
    Map<String, ApplicationLoadBalancer> =
    with(resource.spec) {
      locations.regions.map { region ->
        ApplicationLoadBalancer(
          moniker,
          Location(
            account = locations.account,
            region = region.name,
            vpc = locations.vpc ?: error("No vpc supplied or resolved"),
            subnet = locations.subnet ?: error("No subnet purpose supplied or resolved"),
            availabilityZones = region.availabilityZones
          ),
          internal,
          overrides[region.name]?.dependencies ?: dependencies,
          idleTimeout,
          overrides[region.name]?.listeners ?: listeners,
          overrides[region.name]?.targetGroups ?: targetGroups
        )
      }
        .associateBy { it.location.region }
    }

  override suspend fun current(resource: Resource<ApplicationLoadBalancerSpec>): Map<String, ApplicationLoadBalancer> =
    cloudDriverService.getApplicationLoadBalancer(resource.spec, resource.serviceAccount)

  override suspend fun upsert(
    resource: Resource<ApplicationLoadBalancerSpec>,
    resourceDiff: ResourceDiff<Map<String, ApplicationLoadBalancer>>
  ): List<Task> =
    coroutineScope {
      resourceDiff
        .toIndividualDiffs()
        .filter { diff -> diff.hasChanges() }
        .map { diff ->
          val desired = diff.desired

          val action = when {
            resourceDiff.current == null -> "Create"
            else -> "Update"
          }
          val description =
            "$action ${resource.kind} load balancer ${desired.moniker} in ${desired.location.account}/${desired.location.region}"

          async {
            taskLauncher.submitJob(
              resource = resource,
              description = description,
              correlationId = "${resource.id}:${desired.location.region}",
              job = diff.toUpsertJob()
            )
          }
        }
        .map { it.await() }
    }

  override suspend fun export(exportable: Exportable): ApplicationLoadBalancerSpec {
    val albs = cloudDriverService.getApplicationLoadBalancer(
      account = exportable.account,
      name = exportable.moniker.toString(),
      regions = exportable.regions,
      serviceAccount = exportable.user
    )

    if (albs.isEmpty()) {
      throw ResourceNotFound(
        "Could not find application load balancer: ${exportable.moniker} " +
          "in account: ${exportable.account}"
      )
    }

    val zonesByRegion = albs.map { (region, alb) ->
      region to cloudDriverCache.availabilityZonesBy(
        account = exportable.account,
        vpcId = cloudDriverCache.subnetBy(exportable.account, region, alb.location.subnet).vpcId,
        purpose = alb.location.subnet,
        region = region
      )
    }
      .toMap()

    val zonesForALB = albs.map { (region, alb) ->
      region to if (
        alb.location.availabilityZones
          .containsAll(
            zonesByRegion[region]
              ?: error(
                "Failed resolving availabilityZones for account: ${exportable.account}, region: $region, " +
                  "subnet: ${alb.location.subnet}"
              )
          )
      ) {
        emptySet()
      } else {
        alb.location.availabilityZones
      }
    }.toMap()

    val base = albs.values.first()
    val spec = ApplicationLoadBalancerSpec(
      moniker = base.moniker,
      locations = SubnetAwareLocations(
        account = exportable.account,
        vpc = base.location.vpc,
        subnet = base.location.subnet,
        regions = albs.map { (region, _) ->
          SubnetAwareRegionSpec(
            name = region,
            availabilityZones = zonesForALB.getValue(region)
          )
        }.toSet()
      ),
      internal = base.internal,
      dependencies = base.dependencies,
      idleTimeout = base.idleTimeout,
      listeners = base.listeners,
      targetGroups = base.targetGroups,
      overrides = mutableMapOf()
    )

    spec.generateOverrides(albs)

    return spec
  }

  override suspend fun actuationInProgress(resource: Resource<ApplicationLoadBalancerSpec>): Boolean =
    resource
      .spec
      .locations
      .regions
      .map { it.name }
      .any { region ->
        orcaService
          .getCorrelatedExecutions("${resource.id}:$region")
          .isNotEmpty()
      }

  private suspend fun CloudDriverService.getApplicationLoadBalancer(
    spec: ApplicationLoadBalancerSpec,
    serviceAccount: String
  ) = getApplicationLoadBalancer(
    account = spec.locations.account,
    name = spec.moniker.toString(),
    regions = spec.locations.regions.map { it.name }.toSet(),
    serviceAccount = serviceAccount
  )

  private suspend fun CloudDriverService.getApplicationLoadBalancer(
    account: String,
    name: String,
    regions: Set<String>,
    serviceAccount: String
  ): Map<String, ApplicationLoadBalancer> =
    // TODO: filtering out default rules seems wrong, see TODO in ApplicationLoadBalancerNormalizer
    regions.map { region ->
      coroutineScope {
        async {
          try {
            getApplicationLoadBalancer(
              serviceAccount,
              EC2_CLOUD_PROVIDER,
              account,
              region,
              name
            )
              .firstOrNull()
              ?.let { lb ->
                val securityGroupNames = lb.securityGroups.map {
                  cloudDriverCache.securityGroupById(account, region, it).name
                }.toMutableSet()

                ApplicationLoadBalancer(
                  moniker = if (lb.moniker != null) {
                    Moniker(lb.moniker!!.app, lb.moniker!!.stack, lb.moniker!!.detail)
                  } else {
                    parseMoniker(lb.loadBalancerName)
                  },
                  location = Location(
                    account = account,
                    region = region,
                    vpc = lb.vpcId.let { cloudDriverCache.networkBy(it).name }
                      ?: error("Keel does not support load balancers that are not in a VPC subnet"),
                    subnet = cloudDriverCache.subnetBy(lb.subnets.first()).purpose
                      ?: error("Keel does not support load balancers that are not in a VPC subnet"),
                    availabilityZones = lb.availabilityZones
                  ),
                  internal = lb.scheme != null && lb.scheme!!.contains(
                    "internal",
                    ignoreCase = true
                  ),
                  idleTimeout = Duration.ofSeconds(lb.idleTimeout.toLong()),
                  listeners = lb.listeners.map { l ->
                    ApplicationLoadBalancerSpec.Listener(
                      port = l.port,
                      protocol = l.protocol,
                      certificate = l.certificates?.firstOrNull()
                        ?.let { cloudDriverCache.certificateByArn(it.certificateArn).serverCertificateName },
                      // TODO: filtering out default rules seems wrong, see TODO in ApplicationLoadBalancerNormalizer
                      rules = l.rules.filter { !it.default }.map { it.toEc2Api() }.toSet(),
                      defaultActions = l.defaultActions.map { it.toEc2Api() }.toSet()
                    )
                  }.toSet(),
                  dependencies = LoadBalancerDependencies(
                    securityGroupNames = securityGroupNames
                  ),
                  targetGroups = lb.targetGroups.map { tg ->
                    ApplicationLoadBalancerSpec.TargetGroup(
                      name = tg.targetGroupName,
                      targetType = tg.targetType,
                      protocol = tg.protocol,
                      port = tg.port,
                      healthCheckEnabled = tg.healthCheckEnabled,
                      healthCheckTimeout = Duration.ofSeconds(tg.healthCheckTimeoutSeconds.toLong()),
                      healthCheckPort = when (tg.healthCheckPort) {
                        "traffic-port" -> tg.port
                        else -> tg.healthCheckPort.toInt()
                      },
                      healthCheckProtocol = tg.healthCheckProtocol,
                      healthCheckHttpCode = tg.matcher.httpCode,
                      healthCheckPath = tg.healthCheckPath,
                      healthCheckInterval = Duration.ofSeconds(tg.healthCheckIntervalSeconds.toLong()),
                      healthyThresholdCount = tg.healthyThresholdCount,
                      unhealthyThresholdCount = tg.unhealthyThresholdCount,
                      attributes = tg.attributes.toEc2Api()
                    )
                  }.toSet()
                )
              }
          } catch (e: HttpException) {
            if (e.isNotFound) {
              null
            } else {
              throw e
            }
          }
        }
      }
    }
      .mapNotNull { it.await() }
      .associateBy { it.location.region }

  private fun ResourceDiff<ApplicationLoadBalancer>.toUpsertJob(): Job =
    with(desired) {
      OrcaJob(
        "upsertLoadBalancer",
        mapOf(
          "application" to moniker.app,
          "credentials" to location.account,
          "cloudProvider" to EC2_CLOUD_PROVIDER,
          "name" to moniker.toString(),
          "region" to location.region,
          "availabilityZones" to mapOf(location.region to location.availabilityZones),
          "loadBalancerType" to loadBalancerType.toString().toLowerCase(),
          "vpcId" to cloudDriverCache.networkBy(location.vpc, location.account, location.region).id,
          "subnetType" to location.subnet,
          "isInternal" to internal,
          "idleTimeout" to idleTimeout.seconds,
          "securityGroups" to dependencies.securityGroupNames,
          "listeners" to listeners.map {
            mapOf(
              "port" to it.port,
              "protocol" to it.protocol,
              "rules" to it.rules,
              "defaultActions" to it.defaultActions.sortedBy(Action::order).map { action ->
                mapOf(
                  "type" to action.type,
                  "order" to action.order,
                ) + action.toOrcaRequest()
              },
            ).run {
              it.certificate?.let { certificateName ->
                this + mapOf(
                  "certificates" to listOf(
                    mapOf(
                      "certificateArn" to cloudDriverCache.certificateByAccountAndName(location.account, certificateName).arn
                    )
                  )
                )
              } ?: this
            }
          },
          "targetGroups" to targetGroups.map {
            mapOf(
              "name" to it.name,
              "targetType" to it.targetType,
              "protocol" to it.protocol,
              "port" to it.port,
              "healthCheckEnabled" to it.healthCheckEnabled,
              "healthCheckTimeout" to it.healthCheckTimeout.seconds,
              "healthCheckPort" to it.healthCheckPort,
              "healthCheckProtocol" to it.healthCheckProtocol,
              "healthCheckHttpCode" to it.healthCheckHttpCode,
              "healthCheckPath" to it.healthCheckPath,
              "healthCheckInterval" to it.healthCheckInterval.seconds,
              "healthyThreshold" to it.healthyThresholdCount,
              "unhealthyThreshold" to it.unhealthyThresholdCount,
              "attributes" to it.attributes
            )
          }
        )
      )
    }

  private fun ApplicationLoadBalancerSpec.generateOverrides(
    regionalAlbs: Map<String, ApplicationLoadBalancer>
  ) =
    regionalAlbs.forEach { (region, alb) ->
      val dependenciesDiff = DefaultResourceDiff(alb.dependencies, dependencies).hasChanges()
      val listenersDiff = DefaultResourceDiff(alb.listeners, listeners).hasChanges()
      val targetGroupDiff = DefaultResourceDiff(alb.targetGroups, targetGroups).hasChanges()

      if (dependenciesDiff || listenersDiff || targetGroupDiff) {
        (overrides as MutableMap)[region] = ApplicationLoadBalancerOverride(
          dependencies = if (dependenciesDiff) {
            alb.dependencies
          } else {
            null
          },
          listeners = if (listenersDiff) {
            alb.listeners
          } else {
            null
          },
          targetGroups = if (targetGroupDiff) {
            alb.targetGroups
          } else {
            null
          }
        )
      }
    }
}
