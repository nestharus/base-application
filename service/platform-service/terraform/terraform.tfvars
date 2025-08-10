# Main configuration
region       = "ewr"  # Example: New Jersey region
cluster_name = "platform-k8s-cluster"

# VPC Configuration
vpc_cidr        = "10.0.0.0/16"
vpc_description = "Kubernetes Platform VPC"

# Kubernetes Configuration
kubernetes_version = "v1.28.2+1"

# System Node Pool Configuration
system_node_pool = {
  plan      = "vc2-1c-2gb"  # Small general purpose: 1 vCPU, 2GB RAM
  min_nodes = 1
  max_nodes = 1
  label     = "system"
}

# Application Node Pool Configuration
application_node_pool = {
  plan      = "vc2-1c-2gb"  # Small general purpose: 1 vCPU, 2GB RAM
  min_nodes = 1
  max_nodes = 1
  label     = "application"
}

# Tags for resource organization
tags = {
  Environment = "platform"
  ManagedBy   = "terraform"
  Purpose     = "kubernetes-cluster"
}

# MinIO Configuration
minio_storage_size  = "1Gi"                              # Storage size for MinIO persistent volume
minio_storage_class = "vultr-block-storage-nvme-retain"  # NVMe storage class for high performance

# Infisical Kubernetes Auth Configuration
infisical_auth_method   = "kubernetes"  # Use Kubernetes native auth (no long-lived tokens)
infisical_token_ttl     = "7d"          # Access token TTL (7 days)
infisical_token_max_ttl = "180d"        # Maximum TTL for token renewals (180 days)
infisical_renewal_threshold = 0.8       # Renew tokens at 80% of TTL