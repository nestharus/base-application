# Environment Deployments

This directory contains actual environment deployments using the environment types.

## Creating a New Environment

To create a new environment:

1. Copy the template:
   ```bash
   cp ../cluster-template.yaml my-env.yaml
   ```

2. Replace the variables:
   - `${environment_name}`: Your environment name (e.g., `dev-team1`, `staging-qa`)
   - `${environment_type}`: One of: `dev`, `staging`, `prod`, `sue`
   - `${vultr_region}`: Region (default: `ewr`)
   - `${kubernetes_version}`: K8s version (default: `v1.29.4+1`)

3. Apply the configuration:
   ```bash
   kubectl apply -f my-env.yaml
   ```

## Examples

### Development Environment for Team 1
```yaml
apiVersion: infrastructure.base.io/v1alpha1
kind: KubernetesCluster
metadata:
  name: dev-team1-cluster
  namespace: flux-system
spec:
  parameters:
    environmentName: dev-team1
    environmentType: dev
    region: ewr
    version: v1.29.4+1
```

### Production Environment for Main Application
```yaml
apiVersion: infrastructure.base.io/v1alpha1
kind: KubernetesCluster
metadata:
  name: prod-main-cluster
  namespace: flux-system
spec:
  parameters:
    environmentName: prod-main
    environmentType: prod
    region: ewr
    version: v1.29.4+1
```

## Environment Types

- **dev**: Minimal resources, no HA, aggressive autoscaling
- **staging**: Moderate resources, HA enabled, standard monitoring
- **prod**: Full resources, HA enabled, conservative autoscaling
- **sue**: Minimal resources, no HA, for evaluation purposes