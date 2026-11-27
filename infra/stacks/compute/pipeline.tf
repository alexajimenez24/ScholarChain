# The batch pipeline: one Step Functions Standard state machine that
#   1. creates one transient EMR cluster (bounded capacity, managed scaling, on-demand only,
#      idle auto-termination, IMDSv2, private subnet, project tags),
#   2. runs the packaged Hadoop ingestion (J01-J07), entity resolution (ER-J1, ER-J2) and
#      Spark analytics jobs as EMR steps,
#   3. runs the storage loaders (Phase 13, storage PipelineCli in the query-service
#      assembly, uploaded as loaders.jar) on the primary node through the step helper:
#      neptune-load (writes the bulk-load files under graph/neptune-load/ and starts and
#      waits for the Neptune bulk loader; only with neptuneLoad), load-dynamo (always),
#      and enqueue-embeddings for the embedding worker (unless embedUnits is "0"),
#   4. terminates the cluster on success and on every failure path, then ends in Succeeded
#      or in an explicit Fail state.
#
# Execution input, built and validated by scripts/run-bounded-pipeline.sh:
#   { "runLabel": "run-YYYYMMDDtHHMMSSz-<sha7>", "revision": "<40 hex>",
#     "snapshot": "YYYY-MM-DD", "dblpUri": "s3a://<raw>/dblp/snapshot_date=<label>/...",
#     "openalexUri": "s3a://<raw>/openalex/...", "crossrefUri": "s3a://<raw>/crossref/...",
#     "neptuneLoad": true|false, "embedUnits": "0" | "all" | "<1..999999>" }
# Artifacts (job jars, the loaders jar and the step helper) are uploaded by the run script
# to s3://<artifacts>/pipeline/<revision>/{jobs/hadoop.jar,jobs/entity-resolution.jar,
#   jobs/spark.jar,jobs/loaders.jar,emr/scholarchain-step.sh}.
# Outputs: s3a://<curated>/runs/<label>/{hadoop,er,checkpoints},
#   s3a://<graph>/runs/<label>/spark, s3://<graph>/neptune-load/run_id=<run>/ (CLI), the
#   DynamoDB researcher-metric items, the queued embedding jobs and the run ledger.
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

  # Loader steps: configuration names the storage CLI reads (step-02 report), passed as
  # NAME=VALUE words the helper checks against its own allow list and patterns. No value
  # is secret; AWS credentials come from the instance profile inside the helper.
  loader_common = [
    "AWS_REGION=${var.region}",
    "CURATED_BUCKET=${local.curated}",
    "DDB_RUN_LEDGER=${local.p["dynamodb/run-ledger"]}",
  ]
  loader_env = {
    neptune-load = concat(local.loader_common, [
      "GRAPH_BUCKET=${local.graph}",
      "NEPTUNE_ENDPOINT=${local.p["neptune/endpoint"]}",
      "NEPTUNE_PORT=${local.p["neptune/port"]}",
      "NEPTUNE_LOADER_ROLE_ARN=${local.p["neptune/loader-role-arn"]}",
    ])
    load-dynamo = concat(local.loader_common, [
      "DDB_RESEARCHER_METRIC=${local.p["dynamodb/researcher-metric"]}",
    ])
    enqueue-embeddings = concat(local.loader_common, [
      "EMBEDDING_QUEUE_URL=${aws_sqs_queue.embedding.url}",
      "EMBEDDING_DIMENSION=${local.p["vectors/dimension"]}",
    ])
  }
  loader_words = { for k, v in local.loader_env : k => join(", ", [for w in v : "'${w}'"]) }

  # Neptune bulk loader wait inside the CLI (its own default), below the step timeout.
  neptune_wait_seconds = min(3600, var.step_timeout_seconds - 600)

  loader_step = {
    for k, limit in {
      neptune-load       = "'${local.neptune_wait_seconds}'"
      load-dynamo        = "'-'"
      enqueue-embeddings = "$.embedUnits"
      } : k => {
      Type           = "Task"
      Resource       = "arn:aws:states:::elasticmapreduce:addStep.sync"
      TimeoutSeconds = var.step_timeout_seconds
      Parameters = {
        "ClusterId.$" = "$.cluster.ClusterId"
        Step = {
          Name            = k
          ActionOnFailure = "CONTINUE"
          HadoopJarStep = {
            Jar      = "command-runner.jar"
            "Args.$" = "States.Array('bash', '${local.helper}', 'pipeline-cli', ${format(local.jar, "loaders.jar")}, '${k}', ${local.spark_out}, ${limit}, ${local.loader_words[k]})"
          }
        }
      }
      ResultPath = null
      Catch      = local.catch_to_terminate
    }
  }

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
              "Args.$"  = "States.Array('--dblp', $.dblpUri, '--openalex', $.openalexUri, '--crossref', $.crossrefUri, '--snapshot', $.snapshot, '--output', ${local.hadoop_out})"
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
        Default = "LoadDynamo"
      }

      # Writes the bulk-load files, starts the Neptune loader and waits for it (CLI exit 3:
      # rejected records, no load started; exit 4: not finished in time; both fail the
      # step, and the catch terminates the cluster).
      NeptuneBulkLoad = merge(local.loader_step["neptune-load"], { Next = "LoadDynamo" })

      LoadDynamo = merge(local.loader_step["load-dynamo"], { Next = "ChooseEnqueue" })

      ChooseEnqueue = {
        Type = "Choice"
        Choices = [{
          Variable     = "$.embedUnits"
          StringEquals = "0"
          Next         = "TerminateCluster"
        }]
        Default = "EnqueueEmbeddings"
      }

      EnqueueEmbeddings = merge(local.loader_step["enqueue-embeddings"], { Next = "TerminateCluster" })

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
  # echo step arguments (the loader steps carry the queue URL and loader role ARN, which
  # include the account ID); the full history stays in Step Functions itself.
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.pipeline.arn}:*"
    include_execution_data = false
    level                  = "ERROR"
  }
}
