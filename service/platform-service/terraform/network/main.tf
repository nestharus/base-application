terraform {
  required_providers {
    vultr = {
      source  = "vultr/vultr"
      version = "~> 2.0"
    }
  }
  required_version = ">= 1.0"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "vpc_region" {
  description = "Vultr region for the VPC"
  type        = string
}

variable "vpc_description" {
  description = "Description for the VPC"
  type        = string
  default     = "Kubernetes VPC"
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}

resource "vultr_vpc" "kubernetes_vpc" {
  region      = var.vpc_region
  description = var.vpc_description
  v4_subnet   = var.vpc_cidr
  v4_subnet_mask = split("/", var.vpc_cidr)[1]
}

output "vpc_id" {
  description = "ID of the VPC"
  value       = vultr_vpc.kubernetes_vpc.id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC"
  value       = vultr_vpc.kubernetes_vpc.v4_subnet
}

output "vpc_region" {
  description = "Region of the VPC"
  value       = vultr_vpc.kubernetes_vpc.region
}