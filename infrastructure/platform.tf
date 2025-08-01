# Platform Cluster - Cross-environment services (JCR, Infisical)
# This cluster runs services that are shared across all environments

# VPC for the platform cluster
resource "vultr_vpc" "platform" {
  description    = "VPC for Platform cluster - shared services"
  region         = var.vultr_region
  v4_subnet      = "10.1.0.0"
  v4_subnet_mask = 24
}

# Platform cluster configuration
resource "vultr_kubernetes" "platform" {
  region  = var.vultr_region
  label   = "${var.cluster_name}-platform"
  version = var.kubernetes_version

  # Platform cluster only uses general-restricted nodes
  node_pools {
    node_quantity = 1
    plan          = "vc2-2c-4gb"  # 2 vCPU, 4GB RAM
    label         = "general-restricted"
    auto_scaler   = true
    min_nodes     = 1
    max_nodes     = 4
  }

  # Enable HA control plane for production
  enable_ha_control_planes = true
}

# Save platform kubeconfig locally
resource "local_file" "platform_kubeconfig" {
  content  = vultr_kubernetes.platform.kube_config
  filename = "${path.module}/platform-kubeconfig"

  provisioner "local-exec" {
    command = "chmod 600 ${path.module}/platform-kubeconfig"
  }
}

# VPC Peering between application and platform clusters
resource "vultr_vpc_peering" "app_to_platform" {
  source_vpc_id = vultr_vpc.kubernetes.id
  peer_vpc_id   = vultr_vpc.platform.id
}

# Configure platform cluster
resource "null_resource" "configure_platform_cluster" {
  depends_on = [
    vultr_kubernetes.platform,
    local_file.platform_kubeconfig
  ]

  # Re-run if cluster changes
  triggers = {
    cluster_id = vultr_kubernetes.platform.id
  }

  provisioner "local-exec" {
    command = <<-EOT
      export KUBECONFIG=${local_file.platform_kubeconfig.filename}
      
      # Wait for cluster to be fully ready
      echo "Waiting for platform cluster to be ready..."
      kubectl wait --for=condition=Ready nodes --all --timeout=600s
      
      # Label nodes for platform workloads
      kubectl label nodes --all node-type=general-restricted --overwrite
      kubectl label nodes --all cluster-type=platform --overwrite
      kubectl label nodes --all workload=platform-services --overwrite
      
      # Create namespaces for platform services
      kubectl create namespace jfrog-artifactory --dry-run=client -o yaml | kubectl apply -f -
      kubectl create namespace infisical --dry-run=client -o yaml | kubectl apply -f -
      kubectl create namespace flux-system --dry-run=client -o yaml | kubectl apply -f -
      kubectl create namespace crossplane-system --dry-run=client -o yaml | kubectl apply -f -
      
      # Apply pod security standards
      kubectl label namespace jfrog-artifactory pod-security.kubernetes.io/enforce=baseline --overwrite
      kubectl label namespace infisical pod-security.kubernetes.io/enforce=baseline --overwrite
      
      # Run platform-specific Ansible playbook
      ansible-playbook -i localhost, ${path.module}/playbooks/platform-setup.yml
    EOT
  }
}

# Deploy Crossplane to platform cluster
resource "null_resource" "deploy_platform_crossplane" {
  depends_on = [null_resource.configure_platform_cluster]

  triggers = {
    playbook_hash = filemd5("${path.module}/playbooks/crossplane.yml")
  }

  provisioner "local-exec" {
    command = <<-EOT
      export KUBECONFIG=${local_file.platform_kubeconfig.filename}
      ansible-playbook -i localhost, ${path.module}/playbooks/crossplane.yml
    EOT
  }
}

# Deploy Flux to platform cluster
resource "null_resource" "deploy_platform_flux" {
  depends_on = [null_resource.deploy_platform_crossplane]

  triggers = {
    playbook_hash = filemd5("${path.module}/playbooks/flux.yml")
  }

  provisioner "local-exec" {
    command = <<-EOT
      export KUBECONFIG=${local_file.platform_kubeconfig.filename}
      ansible-playbook -i localhost, ${path.module}/playbooks/flux.yml
    EOT
  }
}

# Outputs for platform cluster
output "platform_cluster_endpoint" {
  value       = vultr_kubernetes.platform.endpoint
  description = "Platform Kubernetes cluster endpoint"
  sensitive   = true
}

output "platform_cluster_id" {
  value       = vultr_kubernetes.platform.id
  description = "Platform Kubernetes cluster ID"
}

output "platform_kubeconfig_path" {
  value       = local_file.platform_kubeconfig.filename
  description = "Path to the platform kubeconfig file"
}

output "platform_vpc_id" {
  value       = vultr_vpc.platform.id
  description = "Platform VPC ID for peering"
}

output "vpc_peering_id" {
  value       = vultr_vpc_peering.app_to_platform.id
  description = "VPC peering connection ID"
}