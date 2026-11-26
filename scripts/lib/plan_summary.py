"""Summarize `terraform show -json <plan>` read from stdin.

Prints greppable lines only (addresses, actions and counts, never attribute values, so no
sensitive value from the JSON plan reaches the output):
  ==> STATEFUL-DESTROY <address> actions=<a,b>   (listed first)
  ==> IAM-CHANGE <address> actions=<a,b>
  ==> BUDGET-CHANGE <address> actions=<a,b>
  ==> ADMIN-CHANGE <address> actions=<a,b>       (foundation only)
  ==> PLAN-SUMMARY add=N change=N destroy=N replace=N stateful-destroy=N iam-changes=N
      guard-changes=N admin-changes=N|n/a   (one line)
guard-changes counts IAM and budget resources. admin-changes (foundation only, n/a for
other stacks) counts every change to a resource type the deployment role may not apply
there (all types except ROLE_APPLICABLE below: the NAT, interface endpoint, security group
rule and SSM toggles); deploy.sh reads it: 0 requires the deployment role, more requires
the human's administrator credentials (repair round 2: roles and their trust are
foundation resources the deployment role cannot change).
Exit 0 always on a readable plan; exit 2 on unreadable input or arguments.
Run as: terraform show -json plan | python3 -I scripts/lib/plan_summary.py --stack <stack>
"""

import json
import sys

STATEFUL = {
    "aws_neptune_cluster",
    "aws_neptune_cluster_instance",
    "aws_dynamodb_table",
    "aws_s3_bucket",
    "aws_s3vectors_vector_bucket",
    "aws_s3vectors_index",
    "aws_ecr_repository",
    "aws_cognito_user_pool",
    "aws_sqs_queue",
}

# Foundation resource types the deployment role's network and data policies cover.
ROLE_APPLICABLE = {
    "aws_eip",
    "aws_nat_gateway",
    "aws_route",
    "aws_vpc_endpoint",
    "aws_vpc_security_group_ingress_rule",
    "aws_vpc_security_group_egress_rule",
    "aws_ssm_parameter",
}


def main() -> int:
    args = sys.argv[1:]
    if len(args) != 2 or args[0] != "--stack" or not args[1].isidentifier():
        print("==> PLAN-SUMMARY usage: plan_summary.py --stack <stack>")
        return 2
    foundation = args[1] == "foundation"
    try:
        plan = json.load(sys.stdin)
    except ValueError:
        print("==> PLAN-SUMMARY unreadable plan JSON")
        return 2

    add = change = destroy = replace = 0
    stateful, iam, budget, admin = [], [], [], []
    for rc in plan.get("resource_changes", []):
        if rc.get("mode") != "managed":
            continue
        actions = rc.get("change", {}).get("actions", [])
        if actions == ["no-op"] or actions == ["read"]:
            continue
        if "create" in actions:
            add += 1
        if "update" in actions:
            change += 1
        if "delete" in actions:
            destroy += 1
        if "create" in actions and "delete" in actions:
            replace += 1
        label = ",".join(actions)
        if "delete" in actions and rc.get("type") in STATEFUL:
            stateful.append((rc["address"], label))
        if rc.get("type", "").startswith("aws_iam_"):
            iam.append((rc["address"], label))
        if rc.get("type", "").startswith("aws_budgets_"):
            budget.append((rc["address"], label))
        if foundation and rc.get("type", "") not in ROLE_APPLICABLE:
            admin.append((rc["address"], label))

    for address, label in stateful:
        print("==> STATEFUL-DESTROY %s actions=%s" % (address, label))
    for address, label in iam:
        print("==> IAM-CHANGE %s actions=%s" % (address, label))
    for address, label in budget:
        print("==> BUDGET-CHANGE %s actions=%s" % (address, label))
    for address, label in admin:
        print("==> ADMIN-CHANGE %s actions=%s" % (address, label))
    print(
        "==> PLAN-SUMMARY add=%d change=%d destroy=%d replace=%d stateful-destroy=%d iam-changes=%d"
        " guard-changes=%d admin-changes=%s"
        % (
            add, change, destroy, replace, len(stateful), len(iam), len(iam) + len(budget),
            str(len(admin)) if foundation else "n/a",
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
