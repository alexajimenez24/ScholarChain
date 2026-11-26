# Edge: Cognito user pool (JWT issuer), API Gateway HTTP API with a JWT authorizer, and a
# VPC Link to the Cloud Map services. No load balancer, no custom domain, no public task.
# The services still verify every JWT themselves (Phase 10), so the authorizer is the
# first of two checks.
#
# Tokens: one confidential app client with the client-credentials grant and the three
# scopes the services check (modules/services/MODULE.md). Cognito generates its secret;
# it lives only in Cognito and, as a sensitive attribute, in the encrypted state file. No
# script reads it: smoke-test.sh takes a token from the environment (see its header).
# M2M client credentials work on the Lite plan (Cognito Developer Guide, "Features by
# plan", read 2026-11-26).

locals {
  scopes = {
    "query.run"     = "Run collaboration queries and read their jobs and explanations"
    "graph.read"    = "Read bounded graph neighbourhoods"
    "evidence.read" = "Read evidence items by ID"
  }
  resource_server = "scholarchain"
  scope_names     = [for s in keys(local.scopes) : "${local.resource_server}/${s}"]

  issuer = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"

  # Route -> (service, scope). Health routes are not exposed through the API.
  routes = {
    "POST /v1/query"                         = { service = "query-service", scope = "query.run" }
    "GET /v1/jobs/{jobId}"                   = { service = "query-service", scope = "query.run" }
    "GET /v1/jobs/{jobId}/result"            = { service = "query-service", scope = "query.run" }
    "GET /v1/explain/{queryId}"              = { service = "query-service", scope = "query.run" }
    "GET /v1/evidence/{evidenceId}"          = { service = "query-service", scope = "evidence.read" }
    "GET /v1/graph/nodes/{nodeId}"           = { service = "graph-service", scope = "graph.read" }
    "GET /v1/graph/nodes/{nodeId}/neighbors" = { service = "graph-service", scope = "graph.read" }
  }
}

# ----- Cognito ------------------------------------------------------------------------
resource "aws_cognito_user_pool" "this" {
  name                = "${local.name}-users"
  user_pool_tier      = "LITE"
  deletion_protection = var.persistent ? "ACTIVE" : "INACTIVE"
  mfa_configuration   = "OFF"

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length                   = 14
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 1
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }
}

resource "aws_cognito_resource_server" "this" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = local.resource_server
  name         = "ScholarChain API"

  dynamic "scope" {
    for_each = local.scopes
    content {
      scope_name        = scope.key
      scope_description = scope.value
    }
  }
}

# Prefix domain on amazoncognito.com (AWS-owned DNS and TLS): serves /oauth2/token.
resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${local.name}-${substr(sha256(aws_cognito_user_pool.this.id), 0, 8)}"
  user_pool_id = aws_cognito_user_pool.this.id
}

resource "aws_cognito_user_pool_client" "service" {
  name                                 = "${local.name}-service-client"
  user_pool_id                         = aws_cognito_user_pool.this.id
  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = local.scope_names
  supported_identity_providers         = ["COGNITO"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true
  access_token_validity                = var.access_token_minutes

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  depends_on = [aws_cognito_resource_server.this]
}

# ----- API Gateway HTTP API -----------------------------------------------------------
resource "aws_cloudwatch_log_group" "api" {
  name              = "/scholarchain/${var.environment}/api/access"
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_api" "this" {
  name          = "${local.name}-api"
  protocol_type = "HTTP"
  description   = "ScholarChain ${var.environment} query and graph API"
}

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id           = aws_apigatewayv2_api.this.id
  name             = "cognito-jwt"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]

  jwt_configuration {
    issuer   = local.issuer
    audience = [aws_cognito_user_pool_client.service.id]
  }
}

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = "${local.name}-vpc-link"
  subnet_ids         = local.private_subnet_ids
  security_group_ids = [local.p["sg/vpc-link"]]
}

resource "aws_apigatewayv2_integration" "service" {
  for_each = local.http

  api_id                 = aws_apigatewayv2_api.this.id
  description            = "Private integration to ${each.key} through Cloud Map"
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  connection_type        = "VPC_LINK"
  connection_id          = aws_apigatewayv2_vpc_link.this.id
  integration_uri        = aws_service_discovery_service.http[each.key].arn
  payload_format_version = "1.0"
  timeout_milliseconds   = 29000
}

resource "aws_apigatewayv2_route" "this" {
  for_each = local.routes

  api_id               = aws_apigatewayv2_api.this.id
  route_key            = each.key
  target               = "integrations/${aws_apigatewayv2_integration.service[each.value.service].id}"
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.jwt.id
  authorization_scopes = ["${local.resource_server}/${each.value.scope}"]
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_rate_limit  = var.api_throttle_rate
    throttling_burst_limit = var.api_throttle_burst
  }

  # No Authorization header, no body, no query string in the access log.
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api.arn
    format = jsonencode({
      requestId          = "$context.requestId"
      routeKey           = "$context.routeKey"
      status             = "$context.status"
      latencyMs          = "$context.responseLatency"
      integrationLatency = "$context.integration.latency"
      integrationStatus  = "$context.integration.status"
      authorizerError    = "$context.authorizer.error"
      clientId           = "$context.authorizer.claims.client_id"
      requestTime        = "$context.requestTimeEpoch"
    })
  }
}
