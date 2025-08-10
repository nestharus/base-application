Slowly moving into platform-service with fixes





# Infrastructure as Code

This directory contains the Infrastructure as Code (IaC) configuration for the base application platform using Crossplane and Flux.

## Overview

The infrastructure is designed to support a multi-tenant, scalable Kubernetes platform on Vultr with:

- **Platform Cluster**: Hosts shared services for dev/sue environments (JFrog Artifactory, Infisical secrets, MinIO S3 storage, shared Elasticsearch logging cluster, shared Kong instance, shared OPAL/Cedar with Geldata)
- **Application Clusters**: Dynamically created based on environment deployment claims stored in MinIO:
  - **dev**: Development environments with minimal resources, no HA, shared logging/monitoring/Kong/OPAL on platform (no expiration)
  - **stage**: Pre-production environments with moderate resources, dedicated Elasticsearch/Monitoring/Kong/OPAL, Cloudflare WAF (no expiration)
  - **prod**: Production environments with full resources and HA, dedicated Elasticsearch/Monitoring/Kong/OPAL, Cloudflare WAF (no expiration)
  - **sue**: System Under Evaluation environments for experimentation, shared logging/monitoring/Kong/OPAL on platform (24-hour expiration by default)

- **Multiple node pool types**:
  - `general-restricted`: System workloads (taints prevent application pods)
  - `general-public`: Regular application workloads
  - `memory`: Memory-intensive workloads
  - `storage`: Storage-intensive workloads
  - `spot`: Batch and non-critical workloads
  - `ai`: GPU workloads

## Architecture

Infrastructure is managed through GitOps using:
- **Crossplane**: For declarative infrastructure provisioning (platform resources only)
- **Flux**: For continuous deployment and reconciliation
- **Infisical**: For secrets management with automatic token scoping
- **MinIO S3**: For environment deployment claim storage
- **Elasticsearch**: Multi-cluster logging architecture - shared cluster on platform for dev/sue, dedicated clusters for stage/prod
- **Platform API**: For environment lifecycle management with permission-based access
- **OPAL/Cedar**: Multi-instance authorization - shared instance on platform for dev/sue, dedicated instances for stage/prod
- **GitLab CI/Runners**: For continuous integration and automated testing
- **Authentik**: For centralized authentication and identity management
- **Kong API Gateway**: Multi-instance API management - shared instance on platform for dev/sue, dedicated instances for stage/prod
- **Monitoring Stack**: Grafana and Prometheus deployed with each Elasticsearch cluster
- **Cloudflare**: WAF and DNS management for stage/prod environments via Ansible/Crossplane/Flux

## Environment Claims Architecture

The platform uses a split-responsibility model for environment management:

### Two-Part Environment Claims

1. **Kubernetes Cluster**: Controlled by the platform's Crossplane instance
   - VPC and network infrastructure
   - Kubernetes control plane and worker nodes
   - Base security configurations
   - Platform connectivity and authentication

2. **Environment Resources**: Controlled by each environment's own Crossplane instance
   - Application workloads and services
   - Environment-specific configurations
   - Custom resources and policies
   - Service-level infrastructure

### Claim Management

- **Dual Claims**: Both platform and environment claims can be made simultaneously
- **Flux Integration**: Flux points to the specific claim configuration for each environment
- **Base Components**: Core infrastructure (kubernetes-ansible, crossplane playbook, flux playbook) managed by platform
- **Application Components**: All other resources controlled by the environment's own cluster

### Control Separation

This architecture provides:
- **Platform Consistency**: Standardized cluster provisioning and base configurations
- **Environment Autonomy**: Teams can manage their own application infrastructure
- **Security Boundaries**: Clear separation of platform and application concerns
- **Scalability**: Distributed management reduces platform bottlenecks

## Environment Deployment Methods

The platform supports two primary deployment methods for environments:

### Platform Service Deployment (Dynamic Environments)

- **Target**: Suitable for development, staging, and SUE environments
- **Management**: Via Platform API REST endpoints
- **Storage**: Claims stored in MinIO S3 buckets
- **Updates**: Real-time updates through API calls
- **Flexibility**: Dynamic environment creation and destruction
- **Use Cases**: Feature branches, testing, experimentation

### Git Repository Deployment (Static Environments)

- **Target**: Production and critical staging environments
- **Management**: Via Git repository pull requests
- **Storage**: Claims stored as files in Git repositories
- **Updates**: Only through approved pull requests
- **Audit Trail**: Complete change history in Git
- **Use Cases**: Production deployments, compliance-required environments

### Platform Service Target Configuration

The platform service can be configured to target different storage backends:

- **MinIO Target**: For environments not requiring change approval
- **Git Repository Target**: For static environments requiring change approval
- **Flag-Based Switching**: Configuration flag determines target backend

### Deployment Method Selection

```json
{
  "environment_name": "prod-main",
  "deployment_method": "git",
  "git_repository": "https://github.com/company/prod-environments",
  "branch": "main"
}
```

### Git-Based Environment Restrictions

- **No Platform Service Updates**: Git-based environments cannot be modified via Platform API
- **Pull Request Required**: All changes must go through Git workflow
- **Immutable MinIO Claims**: GitOps repositories create immutable MinIO claim files that are only writable by GitLab CI
- **Audit Compliance**: Full change tracking and approval process

### GitOps Repository Integration

#### MinIO Claim File Management

GitOps repositories automatically create and manage MinIO claim files with strict permission controls:

##### Bucket Separation

- **Dynamic Environments**: Claims stored in the shared `environments` bucket with read/write access for Platform API
- **Static Environments**: Claims stored in dedicated `static-environments` bucket with GitLab-only write access
- **Bucket Isolation**: Static environment claims are completely isolated from dynamic environment operations

```yaml
# GitLab CI pipeline creates MinIO claims for static environments
apiVersion: v1
kind: ConfigMap
metadata:
  name: minio-claim-permissions
data:
  permissions: |
    - path: /static-environments/*.json
      write_access: gitlab-ci-service-account
      read_access: ["flux-system", "platform-service"]
      immutable: true
    - path: /environments/*.json
      write_access: ["platform-service", "gitlab-ci-service-account"]
      read_access: ["flux-system", "platform-service"]
      immutable: false
```

#### Claim File Creation Process

1. **Git Commit**: Developer commits environment changes to GitOps repository
2. **GitLab CI Pipeline**: Triggered on merge to main branch
3. **Claim Generation**: Pipeline generates MinIO claim files from Git configuration
4. **Secure Upload**: Claims uploaded to MinIO with GitLab-only write permissions
5. **Flux Synchronization**: Flux detects changes and applies to target clusters
6. **Immutable State**: Claims cannot be modified except through GitLab CI

#### Permission Model

- **Write Access**: Only GitLab CI service account can create/update claim files
- **Read Access**: Flux and platform service can read claim files for deployment
- **Immutability**: Claims are marked immutable after creation to prevent drift
- **Audit Trail**: All changes tracked through Git history and GitLab CI logs

## Service Versioning

The platform supports flexible service versioning strategies:

### Version Tags (Anchored)

Services can specify exact version tags for precise control:

```json
{
  "services": {
    "user-service": "v1.2.3",
    "auth-service": "v2.1.0",
    "api-gateway": "v1.0.5"
  }
}
```

### Version Constraints (Dynamic)

Services can use semantic version constraints for automatic updates:

#### Greater Than or Equal (`^`)
```json
{
  "services": {
    "user-service": "^1.0.0.0"
  }
}
```

#### Pattern match
```json
{
  "services": {
    "user-service": "1.x.x.x"
  }
}
```

### Version Resolution Process

1. **Constraint Evaluation**: Flux evaluates version constraints against available tags
2. **Latest Matching**: Selects the latest version that satisfies the constraint
3. **Automatic Updates**: Flux automatically pulls newer versions when they become available
4. **Rollback Support**: Previous versions remain available for quick rollbacks

### Version Strategy Recommendations

- **Production**: Use anchored tags (`v1.2.3`) for stability
- **Staging**: Use constraint ranges (`>=1.2.0, <2.0.0`) for testing
- **Development**: Use latest (`*`) for rapid iteration
- **SUE**: Use latest (`*`) for experimentation

## Prerequisites

1. Vultr API key configured as environment variable
2. Git repository for GitOps
3. Terraform CLI
4. Ansible (automatically used by Terraform)

## Directory Structure

```
infrastructure/
├── README.md
├── crossplane/             # Crossplane configurations
│   ├── providers/          # Provider configurations
│   ├── xrds/              # Composite Resource Definitions
│   └── compositions/       # Resource compositions
├── clusters/               # Cluster management
│   ├── environment-types/  # Environment type configurations
│   ├── environments/       # Legacy environment deployments (deprecated)
│   ├── bootstrap/          # Common bootstrap configuration
│   └── cluster-template.yaml # Template for new environments
├── node-pools/             # Node pool configurations
├── playbooks/              # Ansible playbooks for cluster setup
├── flux-system/            # Application cluster Flux configs
├── platform-flux-system/   # Platform cluster Flux configs
├── kong/                   # Kong API Gateway configurations (shared + dedicated instances)
├── elasticsearch/          # Elasticsearch logging clusters (shared + dedicated)
├── monitoring/             # Grafana/Prometheus monitoring stack
├── cloudflare/             # WAF and DNS configurations via Ansible
├── jfrog-artifactory/      # JFrog Artifactory setup (Docker images + OCI artifacts)
├── infisical/              # Infisical secrets management
├── opal-cedar/             # OPAL/Cedar authorization policies (shared + dedicated instances)
├── gitlab-ci/              # GitLab CI runner configurations
└── authentik/              # Authentik authentication setup
```

## Deployment Tool (pip package)

The platform provides a pip-installable deployment tool for seamless interaction with the platform service:

### Installation

```bash
pip install platform-deployment-tool
```

### Usage

```bash
# Deploy to cloud environment
platform-deploy --environment dev-team1 --services user-service:v1.2.3

# Deploy with local flag for development
platform-deploy --local --environment dev-local --services user-service:latest
```

### Local Development Mode

When the `--local` flag is used, the deployment tool:

1. **Local Artifact Upload**: Automatically uploads local Docker images and OCI artifacts with the specified version as part of the deployment request
2. **User-Specific Repository**: Stores artifacts in Artifactory under a user-specific repository (e.g., `username-local-repo`)
3. **24-Hour Retention**: User repositories have an automatic 24-hour retention policy for cost optimization
4. **Dynamic Claim Generation**: Creates claim files pointing to the user's repository and those specific artifact versions
5. **Isolated Development**: Each developer gets their own artifact namespace for testing

### Local Development Flow

```bash
# Build and deploy local changes
platform-deploy --local --environment dev-johndoe \
  --services user-service:dev-snapshot auth-service:dev-snapshot

# Tool automatically:
# 1. Builds Docker image: user-service:dev-snapshot
# 2. Packages OCI artifact: user-service-ansible:dev-snapshot
# 3. Uploads to: artifactory/johndoe-local-repo/
# 4. Creates claims pointing to johndoe-local-repo artifacts
# 5. Submits deployment request to platform service
```

## Getting Started

### Cloud Deployment

Deploy the entire platform with a single Terraform command:

```bash
cd infrastructure
terraform init
terraform apply
```

This single command automatically:
- Provisions the kubernetes-ansible cluster on Vultr
- Runs all necessary Ansible playbooks to install and configure:
  - Crossplane for infrastructure management
  - Flux for GitOps continuous deployment
  - Infisical for secrets management
  - MinIO S3 storage for environment claims
  - Shared Elasticsearch cluster for dev/sue environment logging
  - Shared OPAL/Cedar with Geldata for dev/sue authorization
  - Shared Kong API Gateway for dev/sue environments
  - Shared Grafana/Prometheus monitoring stack
  - Cloudflare DNS and WAF automation via Ansible
  - GitLab CI runners for continuous integration
  - Authentik for centralized authentication
  - JFrog Artifactory for container and OCI artifact storage
  - All platform services and configurations

After deployment, the system will automatically:
- Monitor MinIO `environments` bucket for claim changes
- Create/destroy VPCs and Kubernetes clusters based on bucket contents
- Bootstrap Flux on each cluster
- Deploy dedicated Elasticsearch/Monitoring/Kong/OPAL for stage/prod environments
- Configure Cloudflare WAF and DNS for stage/prod environments
- Generate and distribute Infisical tokens
- Deploy applications based on Git commits
- Remove expired SUE environments after 24 hours

### Local Development with Kind and Dagger

For local development and testing, the platform can be run entirely on your local machine using Kind Kubernetes clusters and Dagger CI pipelines:

```bash
# Create local platform cluster using Dagger
dagger run python .dagger/src/pipeline/main.py create_local_platform
```

#### Architecture Overview

- **Kind Kubernetes**: Local Kubernetes clusters running in Docker containers
- **Dagger CI Pipelines**: Execute Ansible playbooks to configure local infrastructure
- **Local Platform Services**: MinIO, Elasticsearch, Infisical, and other platform services run locally
- **Environment Simulation**: Full environment lifecycle management without cloud resources

#### Platform Creation Pipeline

The Dagger pipeline for creating the local platform:

1. **Kind Cluster Creation**: Provisions a local Kubernetes cluster using Kind
2. **Ansible Execution**: Runs platform setup playbooks via Dagger containers
3. **Service Deployment**: Deploys all platform services (MinIO, Elasticsearch, Infisical, etc.)
4. **Configuration**: Sets up local networking and service discovery
5. **Ready State**: Platform API becomes available for environment management

```bash
# Create complete local platform
dagger run python .dagger/src/pipeline/main.py create_local_platform

# Verify platform status
kubectl get pods -n platform-system
```

### Environment Deployment Pipeline

Additional Dagger pipeline for creating and managing local environments:

```bash
# Deploy environment with services from JSON payload
dagger run python .dagger/src/pipeline/main.py deploy_environment \
  --payload '{"environment_name": "dev-local", "environment_type": "dev", "services": {"user-service": "latest"}}'

# Deploy environment with services from file
dagger run python .dagger/src/pipeline/main.py deploy_environment \
  --file ./environment-config.json

# Deploy ONLY specified services and their dependencies
dagger run python .dagger/src/pipeline/main.py deploy_environment \
  --payload '{"services": {"user-service": "latest"}}' \
  --deploy-only-specified

# Use payload as override with environment type tags (sue environment uses dev tag)
dagger run python .dagger/src/pipeline/main.py deploy_environment \
  --payload '{"environment_type": "sue"}' \
  --use-dev-tags
```

#### Pipeline Features

- **Service Selection**: Deploy specific services with automatic dependency resolution
- **Override Mode**: Use JSON payload to override default configurations
- **Tag Scoping**: SUE environments use dev tags for cost optimization
- **Platform API Integration**: Uses platform service API instead of direct container creation
- **Dynamic Infrastructure**: Pulls service infrastructure definitions from service repositories

#### Flag Options

- `--deploy-only-specified`: Deploy only the services listed in the payload/file AND their dependencies
- `--use-dev-tags`: Use development tags for cost optimization (automatically applied for sue environment type)
- `--file`: Read environment configuration from JSON file instead of inline payload
- `--payload`: Inline JSON configuration for environment and services

### Service Infrastructure Integration

Services contain their infrastructure definitions within their repositories:

```
services/user-service/
├── src/main/resources/infrastructure/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── hpa.yaml
│   ├── network-policy.yaml
│   └── kustomization.yaml
└── ...
```

#### Dynamic Infrastructure Pulling

1. **Service Discovery**: Environment deployer scans service repositories for infrastructure
2. **Template Processing**: Infrastructure templates are processed with environment-specific values
3. **Claim Generation**: Infrastructure is converted to Flux claims and stored in MinIO
4. **Flux Pickup**: Claims are automatically picked up by Flux for deployment
5. **Service Deployment**: Services are deployed with appropriate configurations

#### Integration Flow

```bash
# Environment deployer process
1. Read environment configuration (JSON payload or file)
2. Scan specified services for infrastructure definitions
3. Process templates with environment variables
4. Generate Flux claims from processed infrastructure
5. Store claims in MinIO for Flux synchronization
6. Monitor deployment status via Kubernetes API
```

### MinIO and Elasticsearch Architecture

#### Updated Storage Architecture

- **MinIO S3 Storage**: Stores raw Flux claims as JSON files
- **Platform Service Integration**: Ties Flux claims to Elasticsearch documents
- **Elasticsearch Documents**: Contain enriched information from original S3 files
- **Real-time Synchronization**: Changes in MinIO trigger Elasticsearch updates

#### Data Flow

```
1. Environment Claim → MinIO S3 Bucket
2. Platform Service → Monitors MinIO bucket changes
3. Platform Service → Enriches claim data with metadata
4. Platform Service → Indexes enriched data in Elasticsearch
5. Flux → Reads claims from MinIO for deployment
6. Elasticsearch → Provides search and analytics capabilities
```

#### Local Development Benefits

- **Fast Iteration**: No cloud provisioning delays
- **Cost Effective**: No cloud resource usage
- **Full Feature Parity**: Complete platform functionality locally
- **Isolated Testing**: Independent development environments
- **CI/CD Integration**: Same Dagger pipelines work locally and in CI

## Infisical Token Management

The system implements a hierarchical token structure:

1. **Platform Flux Token**: Full platform access
2. **Environment Flux Tokens**: Per-environment access based on environment type
3. **Service Tokens**: Minimal scoped access per service

Tokens are automatically:
- Generated with appropriate scopes
- Rotated periodically
- Distributed to the correct clusters
- Injected into services that need them

## Environment Types

The platform supports four environment types, each with predefined resource allocations:

### Development (dev)
- Purpose: Active development and testing
- HA: Disabled (cost optimization)
- Node pools: general-restricted, general-public, spot
- Auto-scaling: Aggressive for cost optimization
- Example environments: `dev-team1`, `dev-feature-x`, `dev-qa`

### Staging
- Purpose: Pre-production testing
- HA: Enabled
- Node pools: general-restricted, general-public, memory
- Auto-scaling: Moderate
- Example environments: `staging-qa`, `staging-uat`, `staging-demo`

### Production (prod)
- Purpose: Live production workloads
- HA: Enabled
- Node pools: All types available
- Auto-scaling: Conservative for stability
- Example environments: `prod-main`, `prod-eu`, `prod-us`

### SUE (System Under Evaluation)
- Purpose: Evaluation and experimentation
- HA: Disabled
- Node pools: general-restricted, general-public, spot
- Auto-scaling: Aggressive for flexibility
- Example environments: `sue-poc`, `sue-vendor-eval`, `sue-experiment`

## Environment Deployment Architecture

### MinIO S3 Storage

Environment deployment is controlled through MinIO S3 buckets deployed as part of the platform infrastructure:

- **Platform Bucket**: Contains platform-wide configurations and templates
- **Environments Bucket**: Contains environment deployment claims as JSON files

### Environment Claims

Each environment claim is a JSON file stored in the `environments` bucket containing:

```json
{
  "user": "john.doe@company.com",
  "environment_pool": "team1",
  "creation_date": "2024-01-15T10:30:00Z",
  "environment_name": "dev-team1-feature-x",
  "environment_type": "dev",
  "expiration_date": null,
  "services": {
    "user-service": "v1.2.3",
    "auth-service": "v2.1.0",
    "api-gateway": "v1.0.5"
  }
}
```

### Claim Expiration Rules

- **dev, staging, prod**: No expiration (persistent environments)
- **sue**: 24-hour expiration by default (configurable per claim)

### Elasticsearch Logging Architecture

Multi-cluster Elasticsearch deployment provides comprehensive logging coverage:

#### Platform Shared Logging Cluster
- **Location**: Platform Kubernetes cluster
- **Coverage**: ALL dev and sue type environments
- **Configuration**: 1-node Elasticsearch cluster with Functionbeat nodes
- **Monitoring**: Grafana and Prometheus deployed on same cluster
- **Purpose**: Centralized logging for development and experimental environments

#### Dedicated Environment Logging Clusters
- **Location**: Each stage/prod environment cluster
- **Coverage**: Individual stage or prod environment only
- **Configuration**: Dedicated Elasticsearch cluster with Functionbeat nodes
- **Monitoring**: Dedicated Grafana and Prometheus on same cluster
- **Purpose**: Isolated logging for production and staging environments

#### Features
- **Automatic Indexing**: Environment claims are pushed to Elasticsearch when created in MinIO
- **Real-time Updates**: Changes and deletions in MinIO are reflected in Elasticsearch
- **Search Capabilities**: Enables fast querying and filtering of environments
- **Log Security**: Isolated logging ensures stage/prod logs remain separate from dev/sue

### Platform API

A platform service provides REST API endpoints for environment management:

- **Create Environment**: `POST /api/v1/environments`
- **List Environments**: `GET /api/v1/environments` (filtered by user permissions)
- **Destroy Environment**: `DELETE /api/v1/environments/{name}`
- **Permission-Based Access**: Users can only manage environments they have access to

## Kong API Gateway Architecture

### Overview

Kong API Gateway deployment follows the shared/dedicated model to provide API management with appropriate isolation:

#### Shared Kong Instance (Platform Cluster)
- **Location**: Platform Kubernetes cluster
- **Coverage**: ALL dev and sue type environments
- **Configuration**: Single Kong instance managing APIs for all dev/sue environments
- **DNS**: Managed via Cloudflare for dev/sue subdomains
- **Authorization**: Integrated with shared OPAL/Cedar instance
- **WAF**: Basic protection, not Cloudflare WAF

#### Dedicated Kong Instances (Stage/Prod Clusters)
- **Location**: Each individual stage/prod environment cluster
- **Coverage**: Single stage or prod environment only
- **Configuration**: Isolated Kong instance per environment
- **DNS**: Managed via Cloudflare with dedicated domain configurations
- **Authorization**: Integrated with dedicated OPAL/Cedar instance
- **WAF**: Full Cloudflare WAF protection

### Cloudflare Integration

#### WAF Configuration
- **Stage/Prod Only**: Cloudflare WAF applied to dedicated Kong instances
- **Management**: WAF rules controlled by Ansible via Crossplane/Flux
- **Automation**: WAF configurations stored in Git and applied via GitOps
- **Security**: Advanced threat protection for production workloads

#### DNS Management
- **All Environments**: DNS records managed via Cloudflare
- **Automation**: DNS configurations controlled by Ansible via Crossplane/Flux
- **Kong Integration**: DNS records point to appropriate Kong instances
- **SSL/TLS**: Automatic certificate management through Cloudflare

### Architecture Benefits

- **Cost Optimization**: Shared Kong for dev/sue reduces infrastructure overhead
- **Security Isolation**: Dedicated Kong instances ensure stage/prod API isolation
- **Advanced Protection**: Full WAF protection for production environments
- **Automated Management**: Infrastructure-as-code for all Kong and Cloudflare configurations

## Monitoring Stack Architecture

### Overview

Grafana and Prometheus are deployed alongside each Elasticsearch cluster to provide comprehensive monitoring:

#### Platform Monitoring Stack
- **Location**: Platform Kubernetes cluster (same as shared Elasticsearch)
- **Coverage**: Platform services + shared Elasticsearch + dev/sue environment metrics
- **Components**: Grafana, Prometheus, Alertmanager
- **Dashboards**: Platform health, shared services, dev/sue environment overview

#### Dedicated Monitoring Stacks
- **Location**: Each stage/prod cluster (same as dedicated Elasticsearch)
- **Coverage**: Individual environment metrics + dedicated Elasticsearch + dedicated Kong
- **Components**: Grafana, Prometheus, Alertmanager per environment
- **Dashboards**: Environment-specific metrics, application performance, infrastructure health

### Monitoring Features

- **Four Golden Signals**: Latency, Traffic, Errors, Saturation tracking for all services
- **Infrastructure Metrics**: Kubernetes cluster health, resource utilization
- **Application Metrics**: Service-specific performance and business metrics
- **Log Analytics**: Integration with Elasticsearch for log-based alerting
- **Alerting**: Environment-appropriate alert routing and escalation

## Creating New Environments

Environments are now created through the Platform API or by directly placing JSON claims in the MinIO `environments` bucket:

### Via Platform API (Recommended)

```bash
# Create a new development environment
curl -X POST https://platform-api.company.com/api/v1/environments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "environment_name": "dev-team1-feature-x",
    "environment_type": "dev",
    "environment_pool": "team1",
    "services": {
      "user-service": "v1.2.3",
      "auth-service": "v2.1.0"
    }
  }'
```

### Via Direct MinIO Upload (Advanced)

```bash
# Upload claim file directly to MinIO
mc cp my-environment-claim.json minio/environments/
```

The system will automatically:
- Validate the claim format and permissions
- Provision the cluster with appropriate resources based on type
- Generate and distribute Infisical tokens
- Bootstrap Flux for GitOps deployments
- Index the environment in Elasticsearch
- Handle expiration for SUE environments

## OPAL/Cedar Authorization Architecture

### Overview

OPAL (Open Policy Administration Layer) with Cedar policies provides real-time authorization control using a shared/dedicated deployment model:
- **Platform Service API**: Controls who can create, read, update, and delete environments
- **Elasticsearch Records**: Fine-grained access control to environment data and logs
- **Service Authorization**: Policy-based access control for all platform services

### Deployment Architecture

#### Shared OPAL/Cedar Instance (Platform Cluster)
- **Location**: Platform Kubernetes cluster
- **Coverage**: ALL dev and sue type environments
- **Database**: Shared Geldata database on platform cluster
- **Policy Scope**: Manages authorization for dev/sue environments and shared services
- **Service Integration**: Cedar agents in shared Kong, shared Elasticsearch, and platform services

#### Dedicated OPAL/Cedar Instances (Stage/Prod Clusters)
- **Location**: Each individual stage/prod environment cluster
- **Coverage**: Single stage or prod environment only
- **Database**: Dedicated Geldata database per environment cluster
- **Policy Scope**: Isolated authorization policies for specific environment
- **Service Integration**: Cedar agents in dedicated Kong, dedicated Elasticsearch, and environment services

### Architecture Benefits

- **Policy Isolation**: Stage/prod environments have completely isolated authorization policies
- **Shared Efficiency**: Dev/sue environments share authorization infrastructure for cost optimization
- **Real-time Updates**: OPAL synchronizes policy changes across all relevant services
- **Scalable Security**: Distributed authorization reduces bottlenecks and improves security boundaries

### Getting Started

OPAL/Cedar instances are automatically deployed as part of the main Terraform deployment. The single `terraform apply` command includes:
- Provisioning shared Geldata database on platform cluster
- Provisioning dedicated Geldata databases on stage/prod clusters
- Deploying OPAL servers and Cedar agents across all clusters
- Running Ansible playbooks to configure policy synchronization
- Setting up service integration for all API gateways and Elasticsearch clusters

## Artifactory Artifact Storage

### Overview

JFrog Artifactory serves as the central artifact repository for the platform, storing two distinct types of artifacts:

### Artifact Types

#### 1. Docker Images
- **Purpose**: Package the compiled executable from each service
- **Format**: Standard Docker container images
- **Naming**: `service-name:version-tag`
- **Contents**: Application binaries, runtime dependencies, and configuration
- **Usage**: Deployed as containers in Kubernetes pods

#### 2. OCI Artifacts
- **Purpose**: Package the Ansible playbook files from each service
- **Format**: OCI-compliant artifacts (non-Docker)
- **Naming**: `service-name-ansible:version-tag`
- **Contents**: Ansible playbooks, roles, variables, and infrastructure-as-code
- **Usage**: Executed during environment provisioning and configuration

### Repository Structure

```
artifactory/
├── docker-releases/          # Production Docker images
├── docker-snapshots/         # Development Docker images
├── oci-releases/             # Production OCI artifacts
├── oci-snapshots/           # Development OCI artifacts
└── user-repos/              # User-specific repositories (24h retention)
    ├── johndoe-local-repo/  # Individual developer artifacts
    └── janedoe-local-repo/
```

### Claim File Integration

Environment claim files reference both artifact types for complete service deployment:

```json
{
  "services": {
    "user-service": {
      "version": "v1.2.3",
      "docker_image": "artifactory/docker-releases/user-service:v1.2.3",
      "ansible_artifact": "artifactory/oci-releases/user-service-ansible:v1.2.3"
    }
  }
}
```

### Retention Policies

- **Release Repositories**: Permanent retention for production artifacts
- **Snapshot Repositories**: 30-day retention for development artifacts
- **User Repositories**: 24-hour retention for local development artifacts

## GitLab CI and Runners

### Overview

GitLab CI provides continuous integration capabilities with self-hosted runners deployed on the platform Kubernetes cluster:

- **GitLab Runners**: Containerized runners for executing CI/CD pipelines
- **Dagger Integration**: Pipelines use Dagger for reproducible builds
- **Resource Scaling**: Runners auto-scale based on pipeline demand
- **Dual Artifact Publishing**: Builds and publishes both Docker images and OCI artifacts to Artifactory

### Getting Started

GitLab CI runners are automatically deployed as part of the main Terraform deployment. The single `terraform apply` command includes:
- GitLab runner pods with appropriate resource limits
- Integration with Infisical for CI/CD secrets
- Network policies for secure pipeline execution
- Connection to JFrog Artifactory for dual artifact storage (Docker + OCI)

## Authentik Authentication

### Overview

Authentik provides centralized authentication and identity management for all platform services:

- **Single Sign-On (SSO)**: Unified authentication across platform services
- **Identity Providers**: Integration with external identity systems
- **User Management**: Centralized user provisioning and lifecycle management
- **Service Integration**: Authentication for Platform API, Grafana, and other services

### Getting Started

Authentik is automatically deployed as part of the main Terraform deployment. The single `terraform apply` command includes:
- Deploying Authentik on the platform cluster
- Configuring integration with platform services
- Setting up default authentication flows
- Running Ansible playbooks for service configuration

## Integration with Dagger Pipeline

The Dagger pipeline in `.dagger/src/pipeline/main.py` integrates with this infrastructure:

```python
# Run the pipeline
dagger run python .dagger/src/pipeline/main.py main_pipeline
```

## Security

- All secrets managed through Infisical with scoped access
- Pod Security Standards enforced
- Network policies restrict inter-namespace communication
- RBAC configured for least privilege access
- Automatic token rotation and distribution

## Cleanup

To remove an environment, the method depends on the environment deployment type:

### Dynamic Environments (Platform API Managed)

#### Via Platform API (Recommended)
```bash
# Delete environment through API
curl -X DELETE https://platform-api.company.com/api/v1/environments/my-env \
  -H "Authorization: Bearer $TOKEN"
```

#### Via MinIO (Advanced)
```bash
# Remove claim file from MinIO environments bucket
mc rm minio/environments/my-env-claim.json
```

### Static Environments (Git Repository Managed)

#### Via Git Repository (Required Method)
```bash
# Remove environment definition from Git repository
git rm environments/prod-main.yaml
git commit -m "Remove prod-main environment"
git push origin main
# Environment will be destroyed after merge and GitLab CI pipeline completion
```

**Note**: 
- **Dynamic environments**: Destruction is controlled by the presence of claim files in the `environments` MinIO bucket
- **Static environments**: Destruction requires removing the environment definition from the Git repository and merging the change
- **GitLab CI Integration**: For static environments, GitLab CI will automatically remove the corresponding claim file from the `static-environments` MinIO bucket
- Removing a claim (by any method) will automatically trigger cluster deletion and cleanup

To remove the entire platform (WARNING: This affects all environments):
```bash
terraform destroy
```

**Note**: Deleting clusters will remove all resources within them.