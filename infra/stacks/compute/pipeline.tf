# The batch pipeline: one Step Functions Standard state machine that
#   1. creates one transient EMR cluster (bounded capacity, managed scaling, on-demand only,
#      idle auto-termination, IMDSv2, private subnet, project tags),
#   2. runs Hadoop ingestion (J01-J07), entity resolution (ER-J1, ER-J2) and Spark analytics
#      as EMR steps,
#   3. optionally starts the Neptune bulk load from inside the VPC,
#   4. terminates the cluster on success and on every failure path, then ends in Succeeded
#      or in an explicit Fail state.
#
# Execution input, built and validated by scripts/run-bounded-pipeline.sh:
#   { "runLabel": "run-YYYYMMDDTHHMMSSZ-<sha7>", "revision": "<40 hex>",
#     "snapshot": "YYYY-MM-DD", "dblpUri": "s3a://<raw bucket>/<key>",
#     "neptuneLoad": true|false, "neptuneSource": "s3://<graph bucket>/runs/<label>/..." }
# Artifacts (job jars and the step helper) are uploaded by the run script to
#   s3://<artifacts>/pipeline/<revision>/{jobs/hadoop.jar,jobs/entity-resolution.jar,
#   jobs/spark.jar,emr/scholarchain-step.sh}.
# Outputs: s3a://<curated>/runs/<label>/{hadoop,er,checkpoints} and
#   s3a://<graph>/runs/<label>/spark.
#
# Spot task capacity is NOT used: every unit is on-demand (MaximumOnDemandCapacityUnits =
# the maximum), because a lost Hadoop or Spark node would force a step retry the bounded
# run has no budget for. EventBridge rules: only the three AWS-managed rules the .sync
# integrations create; no project rule is used.

locals {
  artifacts = local.p["buckets/artifacts"]
  curated   = local.p["buckets/curated"]
  graph     = local.p["buckets/graph"]

  # Managed scaling limits apply to core and task nodes only; the primary is extra.
  scaling_max_units = var.emr_max_instances - 1

  ebs = {
    EbsBlockDeviceConfigs = [{
      VolumeSpecification = { VolumeType = "gp3", SizeInGB = var.emr_ebs_gb }
      VolumesPerInstance  = 1
    }]
  }

  emr_tags = [for k, v in merge(local.tags, { "for-use-with-amazon-emr-managed-policies" = "true" }) : { Key = k, Value = v }]

  catch_to_terminate = [{
    ErrorEquals = ["States.ALL"]
    ResultPath  = "$.error"
    Next        = "TerminateAfterFailure"
  }]

  hadoop_out = "States.Format('s3a://${local.curated}/runs/{}/hadoop', $.runLabel)"
  er_out     = "States.Format('s3a://${local.curated}/runs/{}/er', $.runLabel)"
  spark_out  = "States.Format('s3a://${local.graph}/runs/{}/spark', $.runLabel)"
  checkpoint = "States.Format('s3a://${local.curated}/runs/{}/checkpoints', $.runLabel)"
  jar        = "States.Format('s3://${local.artifacts}/pipeline/{}/jobs/%s', $.revision)"
  helper     = "/home/hadoop/scholarchain-step.sh"

  pipeline_definition = {
    Comment        = "ScholarChain bounded batch pipeline (${var.environment}): Hadoop, entity resolution, Spark, Neptune bulk load"
    StartAt        = "CreateCluster"
    TimeoutSeconds = var.pipeline_timeout_seconds
    States = {
      CreateCluster = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:createCluster.sync"
        TimeoutSeconds = 2700
        Parameters = {
          "Name.$"              = "States.Format('${local.name}-{}', $.runLabel)"
          ReleaseLabel          = var.emr_release_label
          Applications          = [{ Name = "Spark" }]
          ServiceRole           = local.p["roles/emr-service-arn"]
          JobFlowRole           = local.p["roles/emr-instance-profile-name"]
          SecurityConfiguration = aws_emr_security_configuration.this.name
          LogUri                = "s3://${local.p["buckets/logs"]}/emr/"
          VisibleToAllUsers     = true
          StepConcurrencyLevel  = 1
          AutoTerminationPolicy = { IdleTimeout = var.emr_idle_timeout_seconds }
          ManagedScalingPolicy = {
            ComputeLimits = {
              UnitType                     = "Instances"
              MinimumCapacityUnits         = var.emr_core_count
              MaximumCapacityUnits         = local.scaling_max_units
              MaximumOnDemandCapacityUnits = local.scaling_max_units
              MaximumCoreCapacityUnits     = var.emr_core_count
            }
          }
          Instances = {
            Ec2SubnetId                   = local.private_subnet_ids[0]
            EmrManagedMasterSecurityGroup = local.p["sg/emr-primary"]
            EmrManagedSlaveSecurityGroup  = local.p["sg/emr-core"]
            ServiceAccessSecurityGroup    = local.p["sg/emr-service-access"]
            KeepJobFlowAliveWhenNoSteps   = true
            TerminationProtected          = false
            InstanceGroups = [
              {
                Name             = "primary"
                InstanceRole     = "MASTER"
                Market           = "ON_DEMAND"
                InstanceType     = var.emr_instance_type
                InstanceCount    = 1
                EbsConfiguration = local.ebs
              },
              {
                Name             = "core"
                InstanceRole     = "CORE"
                Market           = "ON_DEMAND"
                InstanceType     = var.emr_instance_type
                InstanceCount    = var.emr_core_count
                EbsConfiguration = local.ebs
              },
            ]
          }
          BootstrapActions = [{
            Name = "install-step-helper"
            ScriptBootstrapAction = {
              "Path.$" = "States.Format('s3://${local.artifacts}/pipeline/{}/emr/scholarchain-step.sh', $.revision)"
              Args     = ["install"]
            }
          }]
          Tags = local.emr_tags
        }
        ResultSelector = { "ClusterId.$" = "$.ClusterId" }
        ResultPath     = "$.cluster"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "ClusterStartFailed"
        }]
        Next = "HadoopIngest"
      }

      HadoopIngest = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
        TimeoutSeconds = var.step_timeout_seconds
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "hadoop-ingest"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              "Jar.$"   = format(local.jar, "hadoop.jar")
              MainClass = "scholarchain.hadoop.Pipeline"
              "Args.$"  = "States.Array('--dblp', $.dblpUri, '--snapshot', $.snapshot, '--output', ${local.hadoop_out})"
            }
          }
        }
        ResultPath = null
        Catch      = local.catch_to_terminate
        Next       = "EntityResolution"
      }

      EntityResolution = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
        TimeoutSeconds = var.step_timeout_seconds
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "entity-resolution"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              "Jar.$"   = format(local.jar, "entity-resolution.jar")
              MainClass = "scholarchain.er.MatchJob"
              "Args.$"  = "States.Array('--run-root', ${local.hadoop_out}, '--output', ${local.er_out}, '--adjudicator', 'off')"
            }
          }
        }
        ResultPath = null
        Catch      = local.catch_to_terminate
        Next       = "SparkAnalytics"
      }

      SparkAnalytics = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
        TimeoutSeconds = var.step_timeout_seconds
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "spark-analytics"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar      = "command-runner.jar"
              "Args.$" = "States.Array('bash', '${local.helper}', 'spark', ${format(local.jar, "spark.jar")}, ${local.hadoop_out}, ${local.er_out}, ${local.spark_out}, ${local.checkpoint})"
            }
          }
        }
        ResultPath = null
        Catch      = local.catch_to_terminate
        Next       = "ChooseNeptuneLoad"
      }

      ChooseNeptuneLoad = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.neptuneLoad"
          BooleanEquals = true
          Next          = "NeptuneBulkLoad"
        }]
        Default = "TerminateCluster"
      }

      NeptuneBulkLoad = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
        TimeoutSeconds = var.step_timeout_seconds
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "neptune-bulk-load"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar      = "command-runner.jar"
              "Args.$" = "States.Array('bash', '${local.helper}', 'neptune-load', '${local.p["neptune/endpoint"]}', '${local.p["neptune/port"]}', '${local.p["neptune/loader-role-arn"]}', $.neptuneSource, '${var.region}')"
            }
          }
        }
        ResultPath = null
        Catch      = local.catch_to_terminate
        Next       = "TerminateCluster"
      }

      TerminateCluster = {
        Type       = "Task"
        Resource   = "arn:aws:states:::elasticmapreduce:terminateCluster.sync"
        Parameters = { "ClusterId.$" = "$.cluster.ClusterId" }
        ResultPath = null
        Retry = [{
          ErrorEquals     = ["States.ALL"]
          IntervalSeconds = 30
          MaxAttempts     = 3
          BackoffRate     = 2
        }]
        Next = "Succeeded"
      }

      TerminateAfterFailure = {
        Type       = "Task"
        Resource   = "arn:aws:states:::elasticmapreduce:terminateCluster.sync"
        Parameters = { "ClusterId.$" = "$.cluster.ClusterId" }
        ResultPath = "$.terminate"
        Retry = [{
          ErrorEquals     = ["States.ALL"]
          IntervalSeconds = 30
          MaxAttempts     = 3
          BackoffRate     = 2
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.terminateError"
          Next        = "PipelineFailed"
        }]
        Next = "PipelineFailed"
      }

      Succeeded = { Type = "Succeed" }

      ClusterStartFailed = {
        Type  = "Fail"
        Error = "ScholarChain.ClusterStartFailed"
        Cause = "The EMR cluster did not start; EMR terminates a cluster that fails to start, and the idle policy ends any other."
      }

      PipelineFailed = {
        Type  = "Fail"
        Error = "ScholarChain.PipelineFailed"
        Cause = "A pipeline stage failed; the cluster was terminated. Evidence: the execution history and the EMR step logs under the logs bucket."
      }
    }
  }
}

resource "aws_cloudwatch_log_group" "pipeline" {
  name              = "/scholarchain/${var.environment}/pipeline/states"
  retention_in_days = var.log_retention_days
}

resource "aws_sfn_state_machine" "pipeline" {
  name       = "${local.name}-pipeline"
  type       = "STANDARD"
  role_arn   = local.p["roles/step-functions-arn"]
  definition = jsonencode(local.pipeline_definition)

  # ERROR level without execution data: inputs hold only bucket paths, but failures can
  # echo step arguments; the full history stays in Step Functions itself.
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.pipeline.arn}:*"
    include_execution_data = false
    level                  = "ERROR"
  }
}
