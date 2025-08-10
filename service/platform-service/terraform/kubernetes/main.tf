terraform {
  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.0"
    }
    local = {
      source  = "hashicorp/local"
      version = "~> 2.0"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.0"
    }
  }
  required_version = ">= 1.0"
}

variable "cluster_name" {
  description = "Name of the Kubernetes cluster"
  type        = string
}

variable "cluster_version" {
  description = "Kubernetes version"
  type        = string
  default     = "v1.28.2+1"
}

variable "region" {
  description = "Vultr region for the cluster"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the cluster"
  type        = string
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
    plan       = "vc2-1c-2gb"  # Small general purpose instance
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
    plan       = "vc2-1c-2gb"  # Small general purpose instance
    min_nodes  = 1
    max_nodes  = 1
    label      = "application"
  }
}

variable "enable_autoscaling" {
  description = "Enable autoscaling for node pools"
  type        = bool
  default     = true
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}

variable "ansible_playbook_path" {
  description = "Path to kubernetes-ansible playbook"
  type        = string
  default     = "./kubernetes-setup.yml"
}

variable "ansible_inventory_path" {
  description = "Path to ansible inventory file"
  type        = string
  default     = "./ansible-inventory.ini"
}

# Create the Kubernetes cluster
resource "vultr_kubernetes" "cluster" {
  region  = var.region
  label   = var.cluster_name
  version = var.cluster_version
  vpc_id  = var.vpc_id

  node_pools {
    node_quantity = var.system_node_pool.min_nodes
    plan          = var.system_node_pool.plan
    label         = var.system_node_pool.label
    auto_scaler   = var.enable_autoscaling
    min_nodes     = var.system_node_pool.min_nodes
    max_nodes     = var.system_node_pool.max_nodes
  }

  node_pools {
    node_quantity = var.application_node_pool.min_nodes
    plan          = var.application_node_pool.plan
    label         = var.application_node_pool.label
    auto_scaler   = var.enable_autoscaling
    min_nodes     = var.application_node_pool.min_nodes
    max_nodes     = var.application_node_pool.max_nodes
  }
}

# Save kubeconfig to local file
resource "local_file" "kubeconfig" {
  content  = base64decode(vultr_kubernetes.cluster.kube_config)
  filename = "${path.module}/kubeconfig-${var.cluster_name}.yaml"
  file_permission = "0600"
}

# Generate Ansible inventory
resource "local_file" "ansible_inventory" {
  content = templatefile("${path.module}/inventory.tpl", {
    cluster_name = var.cluster_name
    region      = var.region
    kubeconfig  = local_file.kubeconfig.filename
    system_nodes = [for node in vultr_kubernetes.cluster.node_pools : node if node.label == var.system_node_pool.label]
    application_nodes = [for node in vultr_kubernetes.cluster.node_pools : node if node.label == var.application_node_pool.label]
  })
  filename = var.ansible_inventory_path
  file_permission = "0644"
}

# Label nodes after cluster creation
resource "null_resource" "label_nodes" {
  depends_on = [
    vultr_kubernetes.cluster,
    local_file.kubeconfig
  ]

  triggers = {
    cluster_id = vultr_kubernetes.cluster.id
  }

  provisioner "local-exec" {
    command = <<-EOT
      export KUBECONFIG=${local_file.kubeconfig.filename}
      
      # Wait for nodes to be ready
      kubectl wait --for=condition=Ready nodes --all --timeout=300s
      
      # Label system nodes
      kubectl get nodes -l vke.vultr.com/node-pool=${var.system_node_pool.label} -o name | xargs -I {} kubectl label {} nodepool=system --overwrite
      
      # Label application nodes  
      kubectl get nodes -l vke.vultr.com/node-pool=${var.application_node_pool.label} -o name | xargs -I {} kubectl label {} nodepool=application --overwrite
      
      # Add taints to system nodes for dedicated system workloads (optional)
      # kubectl get nodes -l nodepool=system -o name | xargs -I {} kubectl taint {} nodepool=system:NoSchedule --overwrite
    EOT
  }
}

# Run kubernetes-ansible after cluster creation and node labeling
resource "null_resource" "kubernetes_ansible" {
  depends_on = [
    vultr_kubernetes.cluster,
    local_file.kubeconfig,
    local_file.ansible_inventory,
    null_resource.label_nodes
  ]

  triggers = {
    cluster_id = vultr_kubernetes.cluster.id
  }

  provisioner "local-exec" {
    command = "ansible-playbook -i ${var.ansible_inventory_path} ${var.ansible_playbook_path}"
    environment = {
      KUBECONFIG = local_file.kubeconfig.filename
    }
  }
}

output "cluster_id" {
  description = "ID of the Kubernetes cluster"
  value       = vultr_kubernetes.cluster.id
}

output "cluster_endpoint" {
  description = "Endpoint for the Kubernetes cluster"
  value       = vultr_kubernetes.cluster.endpoint
}

output "cluster_ip" {
  description = "IP address of the Kubernetes cluster"
  value       = vultr_kubernetes.cluster.ip
}

output "kubeconfig_path" {
  description = "Path to the kubeconfig file"
  value       = local_file.kubeconfig.filename
}

output "cluster_status" {
  description = "Status of the Kubernetes cluster"
  value       = vultr_kubernetes.cluster.status
}