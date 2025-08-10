# Terraform Kubernetes Infrastructure on Vultr

This Terraform configuration deploys a Kubernetes cluster on Vultr with the following components:

## Architecture

- **VPC**: Private network for Kubernetes cluster isolation
- **Kubernetes Cluster**: Managed Kubernetes with 2 node pools
  - **System Node Pool**: 1 node (min: 1, max: 1) - Small general purpose instance
  - **Application Node Pool**: 1 node (min: 1, max: 1) - Small general purpose instance
- **Ansible Integration**: Kubernetes deployment using kubernetes-ansible

## Prerequisites

1. Vultr account with API access
2. Vultr Object Storage for Terraform state
3. Terraform >= 1.0
4. Ansible installed locally
5. kubernetes-ansible playbooks

## Setup

1. **Configure Environment Variables**
   ```bash
   cp .env.example .env
   # Edit .env with your credentials
   source .env
   ```

2. **Create S3 Bucket for Terraform State**
   - Log into Vultr dashboard
   - Create Object Storage instance
   - Create bucket named `terraform-state-platform`
   - Note the access keys

3. **Initialize Terraform**
   ```bash
   terraform init
   ```

## Usage

### Plan Infrastructure
```bash
terraform plan
```

### Deploy Infrastructure
```bash
terraform apply
```

### Destroy Infrastructure
```bash
terraform destroy
```

## Configuration

### Main Variables (terraform.tfvars)

- `region`: Vultr region (e.g., "ewr" for New Jersey)
- `cluster_name`: Name of the Kubernetes cluster
- `vpc_cidr`: CIDR block for VPC (default: 10.0.0.0/16)
- `kubernetes_version`: Kubernetes version to deploy

### Node Pool Configuration

Both node pools use small general purpose instances (vc2-1c-2gb):
- 1 vCPU
- 2GB RAM
- Suitable for development/testing

### Module Structure

```
terraform/
├── backend.tf              # S3 backend configuration
├── main.tf                 # Root module
├── terraform.tfvars        # Main configuration values
├── network/               
│   ├── main.tf            # VPC module
│   └── terraform.tfvars   # Network configuration
└── kubernetes/
    ├── main.tf            # Kubernetes cluster module
    ├── terraform.tfvars   # Kubernetes configuration
    └── inventory.tpl      # Ansible inventory template
```

## Outputs

- `vpc_id`: ID of the created VPC
- `vpc_cidr`: CIDR block of the VPC
- `cluster_id`: ID of the Kubernetes cluster
- `cluster_endpoint`: API endpoint for the cluster
- `cluster_ip`: IP address of the cluster
- `kubeconfig_path`: Path to the kubeconfig file

## Security Notes

- API keys are managed via environment variables
- Kubernetes cluster is deployed in a private VPC
- State file is stored in Vultr Object Storage
- Kubeconfig files have restricted permissions (0600)

## Available Vultr Instance Plans

Small General Purpose (used in this config):
- `vc2-1c-2gb`: 1 vCPU, 2GB RAM
- `vc2-2c-4gb`: 2 vCPU, 4GB RAM
- `vc2-4c-8gb`: 4 vCPU, 8GB RAM

## Troubleshooting

1. **Terraform Init Fails**
   - Verify AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY are set
   - Check S3 bucket exists in Vultr Object Storage

2. **Cluster Creation Fails**
   - Verify VULTR_API_KEY is set correctly
   - Check region availability for Kubernetes

3. **Ansible Playbook Fails**
   - Ensure ansible is installed
   - Verify kubernetes-ansible playbook path
   - Check kubeconfig file was created