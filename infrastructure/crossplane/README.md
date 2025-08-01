# Crossplane Infrastructure Management

This directory contains Crossplane configurations for managing Vultr infrastructure using GitOps principles through Flux.

## Architecture Overview

The infrastructure is managed through:
1. **Platform Cluster**: Hosts shared services (Infisical, JFrog Artifactory)
2. **Application Clusters**: Environment-specific clusters (dev, staging, prod)

## Directory Structure

```
crossplane/
├── providers/          # Crossplane provider configurations
├── xrds/              # Composite Resource Definitions
├── compositions/      # Resource compositions
└── README.md
```

## Infisical Secret Management

### Machine Token Hierarchy

1. **Platform Flux Token**: Full access to platform secrets and ability to create environment tokens
2. **Environment Flux Tokens**: Read access to environment secrets, ability to create service tokens
3. **Service Tokens**: Scoped read access to service-specific secrets

### Token Scoping Configuration

Machine token types and their scopes are defined in `/infrastructure/infisical/machine-token-config.yaml`:

- `platform-flux`: Platform cluster Flux controller token
- `environment-flux`: Environment-specific Flux controller tokens
- `service-token`: Individual service tokens with minimal permissions

### Automatic Token Provisioning

1. Platform cluster creates environment tokens via CronJob
2. Tokens are synced to application clusters using cross-cluster secret sync
3. Service tokens are created automatically when deployments are annotated with:
   ```yaml
   metadata:
     annotations:
       infisical.io/inject-token: "true"
   ```

## Deployment Flow

1. Crossplane creates VPC and Kubernetes clusters based on claims
2. Flux bootstraps each cluster with appropriate configurations
3. Infisical tokens are automatically provisioned and distributed
4. Services receive scoped tokens for accessing their secrets

## Adding a New Service

To enable Infisical secrets for a new service:

1. Ensure the namespace has the label:
   ```yaml
   metadata:
     labels:
       infisical.io/enabled: "true"
   ```

2. Annotate your deployment:
   ```yaml
   metadata:
     annotations:
       infisical.io/inject-token: "true"
       infisical.io/secret-path: "/services/my-service"  # Optional
   ```

3. The operator will automatically create and inject the token