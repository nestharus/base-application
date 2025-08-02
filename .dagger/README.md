# Dagger Pipeline

This Dagger pipeline simulates the complete infrastructure using containers instead of actual cloud resources.

## Overview

The pipeline creates:
- **Platform services container**: Runs Infisical and JFrog Artifactory
- **Environment container**: Runs Kong, monitoring, and application services

## Usage

### Basic Usage

Run the default development environment:
```bash
dagger call main-pipeline
```

### Custom Environment

Create a specific environment type:
```bash
# Create a staging environment
dagger call main-pipeline --env-name=staging-qa --env-type=staging

# Create a production environment
dagger call main-pipeline --env-name=prod-main --env-type=prod

# Create a SUE environment
dagger call main-pipeline --env-name=sue-experiment --env-type=sue
```

### Using Custom Playbooks

```bash
dagger call main-pipeline --infrastructure-dir=./my-infrastructure
```

## Pipeline Functions

### Platform Services

- `platform-services`: Creates a Docker-in-Docker container for platform services
- `deploy-infisical`: Deploys Infisical secret management
- `deploy-jfrog`: Deploys JFrog Artifactory JCR

### Environment Services

- `environment-cluster`: Creates a Docker-in-Docker container for an environment
- `deploy-kong`: Deploys Kong API Gateway
- `setup-environment-services`: Sets up all environment services

### Verification

- `verify-services`: Checks all services are running correctly

## Service URLs

After running the pipeline, services are available at:

**Platform Services:**
- Infisical: http://localhost:8080
- JFrog Artifactory: http://localhost:8081

**Environment Services:**
- Kong Gateway: http://localhost:80
- Kong Admin API: http://localhost:8000
- Prometheus: http://localhost:9090 (staging/prod only)
- Grafana: http://localhost:3000 (staging/prod only)

## Environment Types

The pipeline supports four environment types with different resource allocations:

- **dev**: Minimal services, no monitoring
- **staging**: Includes monitoring stack
- **prod**: Full feature set with monitoring
- **sue**: Minimal services for evaluation

## Container Mode vs Kubernetes Mode

The pipeline runs in "container mode" where:
- Services run as Docker containers instead of Kubernetes deployments
- Networking is handled through Docker networks
- Storage uses Docker volumes
- No actual cloud resources are created

This allows for:
- Local development and testing
- CI/CD pipeline validation
- Infrastructure code testing
- Demo environments

## Troubleshooting

If services fail to start:
1. Check Docker is running
2. Ensure ports are not already in use
3. Review container logs with `docker logs <container-name>`
4. Verify service health endpoints

## Integration with Infrastructure

The pipeline uses the same Ansible playbooks as production but with:
- `deployment_mode: container` variable set
- Docker modules instead of Kubernetes modules
- Local networking instead of cloud networking