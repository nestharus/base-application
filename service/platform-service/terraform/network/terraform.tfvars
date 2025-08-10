# Network Module Configuration
vpc_region      = "ewr"  # Example: New Jersey region
vpc_cidr        = "10.0.0.0/16"
vpc_description = "Kubernetes Platform VPC"

# Tags for VPC
tags = {
  Environment = "platform"
  ManagedBy   = "terraform"
  Purpose     = "kubernetes-vpc"
}