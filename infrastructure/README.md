# Infrastructure as Code

This directory contains the Infrastructure as Code (IaC) configuration for the base application platform using Crossplane and Flux.

## Overview

The infrastructure is designed to support a multi-tenant, scalable Kubernetes platform on Vultr with:

- **Platform Cluster**: Hosts cross-environment services (JFrog Artifactory, Infisical secrets)
- **Application Clusters**: Dynamically created based on environment types:
  - **dev**: Development environments with minimal resources, no HA
  - **staging**: Pre-production environments with moderate resources
  - **prod**: Production environments with full resources and HA
  - **sue**: System Under Evaluation environments for experimentation

- **Multiple node pool types**:
  - `general-restricted`: System workloads (taints prevent application pods)
  - `general-public`: Regular application workloads
  - `memory`: Memory-intensive workloads
  - `storage`: Storage-intensive workloads
  - `spot`: Batch and non-critical workloads
  - `ai`: GPU workloads

## Architecture

Infrastructure is managed through GitOps using:
- **Crossplane**: For declarative infrastructure provisioning
- **Flux**: For continuous deployment and reconciliation
- **Infisical**: For secrets management with automatic token scoping

## Prerequisites

1. Kubernetes cluster with Flux and Crossplane installed
2. Vultr API key configured as a secret
3. Git repository for GitOps
4. kubectl
5. flux CLI

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
│   ├── environments/       # Actual environment deployments
│   ├── bootstrap/          # Common bootstrap configuration
│   └── cluster-template.yaml # Template for new environments
├── node-pools/             # Node pool configurations
├── playbooks/              # Ansible playbooks for cluster setup
├── flux-system/            # Application cluster Flux configs
├── platform-flux-system/   # Platform cluster Flux configs
├── kong/                   # Kong API Gateway configurations
├── jfrog-artifactory/      # JFrog Artifactory setup
└── infisical/              # Infisical secrets management
```

## Getting Started

1. Deploy the platform cluster using Terraform:
   ```bash
   cd infrastructure
   terraform init
   terraform apply -target=vultr_kubernetes.platform
   ```

2. Apply Crossplane and Flux to platform cluster:
   ```bash
   export KUBECONFIG=./platform-kubeconfig
   kubectl apply -k crossplane/providers
   kubectl apply -k platform-flux-system
   ```

3. Deploy environments as needed:
   ```bash
   # Create a development environment for team1
   kubectl apply -f clusters/environments/dev-team1.yaml
   
   # Create a production environment
   kubectl apply -f clusters/environments/prod-main.yaml
   ```

4. The system will automatically:
   - Create VPCs and Kubernetes clusters
   - Bootstrap Flux on each cluster
   - Generate and distribute Infisical tokens
   - Deploy applications based on Git commits

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

## Creating New Environments

To deploy a new environment:

1. Choose an environment type (`dev`, `staging`, `prod`, `sue`)
2. Create a new file in `clusters/environments/` using the template:
   ```bash
   cp clusters/cluster-template.yaml clusters/environments/my-env.yaml
   ```
3. Replace variables in the file:
   - `environment_name`: Your unique environment name
   - `environment_type`: One of the four types
4. Apply the configuration:
   ```bash
   kubectl apply -f clusters/environments/my-env.yaml
   ```

The system will automatically:
- Provision the cluster with appropriate resources based on type
- Generate and distribute Infisical tokens
- Bootstrap Flux for GitOps deployments

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

To remove an environment:
```bash
# Replace 'my-env' with your environment name
kubectl delete kubernetescluster my-env-cluster -n flux-system
```

To remove the platform cluster (WARNING: This affects all environments):
```bash
terraform destroy -target=vultr_kubernetes.platform
```

**Note**: Deleting clusters will remove all resources within them.