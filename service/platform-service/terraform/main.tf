terraform {
  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.0"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.0"
    }
  }
  required_version = ">= 1.0"
}

# Configure the Vultr Provider
provider "vultr" {
  api_key = var.vultr_api_key
}

variable "vultr_api_key" {
  description = "Vultr API key"
  type        = string
  sensitive   = true
  default     = ""  # Will use VULTR_API_KEY environment variable if not set
}

variable "region" {
  description = "Vultr region for resources"
  type        = string
}

variable "cluster_name" {
  description = "Name of the Kubernetes cluster"
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "vpc_description" {
  description = "Description for the VPC"
  type        = string
  default     = "Kubernetes VPC"
}

variable "kubernetes_version" {
  description = "Kubernetes version"
  type        = string
  default     = "v1.28.2+1"
}

variable "system_node_pool" {
  description = "Configuration for system node pool"
  type = object({
    plan       = string
    min_nodes  = number
    max_nodes  = number
    label      = string
  })
  default = {
    plan       = "vc2-1c-2gb"
    min_nodes  = 1
    max_nodes  = 1
    label      = "system"
  }
}

variable "application_node_pool" {
  description = "Configuration for application node pool"
  type = object({
    plan       = string
    min_nodes  = number
    max_nodes  = number
    label      = string
  })
  default = {
    plan       = "vc2-1c-2gb"
    min_nodes  = 1
    max_nodes  = 1
    label      = "application"
  }
}

variable "tags" {
  description = "Tags to apply to all resources"
  type        = map(string)
  default     = {}
}

variable "minio_storage_size" {
  description = "Storage size for MinIO persistent volume"
  type        = string
  default     = "1Gi"
}

variable "minio_storage_class" {
  description = "Storage class for MinIO persistent volume (vultr-block-storage-hdd-retain or vultr-block-storage-nvme-retain)"
  type        = string
  default     = "vultr-block-storage-nvme-retain"
}

# Network Module - Creates VPC
module "network" {
  source = "./network"
  
  vpc_region      = var.region
  vpc_cidr        = var.vpc_cidr
  vpc_description = var.vpc_description
  tags            = var.tags
}

# Kubernetes Module - Creates cluster with 2 node pools
module "kubernetes" {
  source = "./kubernetes"
  
  cluster_name          = var.cluster_name
  cluster_version       = var.kubernetes_version
  region                = var.region
  vpc_id                = module.network.vpc_id
  system_node_pool      = var.system_node_pool
  application_node_pool = var.application_node_pool
  enable_autoscaling    = true
  tags                  = var.tags
  
  depends_on = [module.network]
}

# Run Crossplane playbook after cluster is ready
resource "null_resource" "crossplane_deployment" {
  depends_on = [module.kubernetes]
  
  triggers = {
    cluster_id = module.kubernetes.cluster_id
    playbook_checksum = filesha256("${path.module}/playbook/crossplane.yml")
  }
  
  provisioner "local-exec" {
    command = "ansible-playbook -v ${path.module}/playbook/crossplane.yml"
    environment = {
      KUBECONFIG = module.kubernetes.kubeconfig_path
      ANSIBLE_HOST_KEY_CHECKING = "False"
    }
  }
}

# Run Infisical playbook after Crossplane is deployed
resource "null_resource" "infisical_deployment" {
  depends_on = [
    module.kubernetes,
    null_resource.crossplane_deployment
  ]
  
  triggers = {
    cluster_id = module.kubernetes.cluster_id
    playbook_checksum = filesha256("${path.module}/playbook/infisical.yml")
  }
  
  provisioner "local-exec" {
    command = "ansible-playbook -v ${path.module}/playbook/infisical.yml"
    environment = {
      KUBECONFIG = module.kubernetes.kubeconfig_path
      ANSIBLE_HOST_KEY_CHECKING = "False"
      INFISICAL_STORAGE_CLASS = var.minio_storage_class
      INFISICAL_AUTH_METHOD = "kubernetes"  # Use Kubernetes native auth
      INFISICAL_TOKEN_TTL = "7d"  # 7-day token TTL
      INFISICAL_TOKEN_MAX_TTL = "180d"  # 180-day max renewal
    }
  }
}

# Run Flux playbook after Infisical is deployed
resource "null_resource" "flux_deployment" {
  depends_on = [
    module.kubernetes,
    null_resource.crossplane_deployment,
    null_resource.infisical_deployment
  ]
  
  triggers = {
    cluster_id = module.kubernetes.cluster_id
    playbook_checksum = filesha256("${path.module}/playbook/flux.yml")
  }
  
  provisioner "local-exec" {
    command = "ansible-playbook -v ${path.module}/playbook/flux.yml"
    environment = {
      KUBECONFIG = module.kubernetes.kubeconfig_path
      ANSIBLE_HOST_KEY_CHECKING = "False"
    }
  }
}

# Run MinIO playbook after Infisical is deployed (runs on system node pool)
resource "null_resource" "minio_deployment" {
  depends_on = [
    module.kubernetes,
    null_resource.crossplane_deployment,
    null_resource.infisical_deployment
  ]
  
  triggers = {
    cluster_id = module.kubernetes.cluster_id
    playbook_checksum = filesha256("${path.module}/playbook/minio.yml")
    storage_size = var.minio_storage_size
    storage_class = var.minio_storage_class
  }
  
  provisioner "local-exec" {
    command = "ansible-playbook -v ${path.module}/playbook/minio.yml"
    environment = {
      KUBECONFIG = module.kubernetes.kubeconfig_path
      ANSIBLE_HOST_KEY_CHECKING = "False"
      MINIO_STORAGE_SIZE = var.minio_storage_size
      MINIO_STORAGE_CLASS = var.minio_storage_class
      # Kubernetes auth will be used - no long-lived tokens
      INFISICAL_AUTH_METHOD = "kubernetes"
      INFISICAL_PROJECT_ID = "platform"  # Will be set by infisical deployment
      INFISICAL_IDENTITY_ID = ""  # Will be auto-created by auth manager
    }
  }
}

# Outputs
output "vpc_id" {
  description = "ID of the created VPC"
  value       = module.network.vpc_id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC"
  value       = module.network.vpc_cidr
}

output "cluster_id" {
  description = "ID of the Kubernetes cluster"
  value       = module.kubernetes.cluster_id
}

output "cluster_endpoint" {
  description = "Endpoint for the Kubernetes cluster"
  value       = module.kubernetes.cluster_endpoint
}

output "cluster_ip" {
  description = "IP address of the Kubernetes cluster"
  value       = module.kubernetes.cluster_ip
}

output "kubeconfig_path" {
  description = "Path to the kubeconfig file"
  value       = module.kubernetes.kubeconfig_path
}

output "cluster_status" {
  description = "Status of the Kubernetes cluster"
  value       = module.kubernetes.cluster_status
}