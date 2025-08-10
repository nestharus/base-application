# Kubernetes Module Configuration
cluster_name    = "platform-k8s-cluster"
cluster_version = "v1.28.2+1"
region          = "ewr"  # Example: New Jersey region

# Node Pool Configurations
system_node_pool = {
  plan      = "vc2-1c-2gb"  # Small general purpose: 1 vCPU, 2GB RAM
  min_nodes = 1
  max_nodes = 1
  label     = "system"
}

application_node_pool = {
  plan      = "vc2-1c-2gb"  # Small general purpose: 1 vCPU, 2GB RAM
  min_nodes = 1
  max_nodes = 1
  label     = "application"
}

# Autoscaling
enable_autoscaling = true

# Ansible Configuration
ansible_playbook_path  = "./kubernetes-setup.yml"
ansible_inventory_path = "./ansible-inventory.ini"

# Tags for Kubernetes resources
tags = {
  Environment = "platform"
  ManagedBy   = "terraform"
  Purpose     = "kubernetes-cluster"
}