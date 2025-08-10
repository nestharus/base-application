terraform {
  backend "s3" {
    endpoint                    = "https://ewr1.vultrobjects.com"  # Vultr Object Storage endpoint
    region                      = "us-east-1"  # Required but not used by Vultr
    bucket                      = "terraform-state-platform"
    key                         = "platform/terraform.tfstate"
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    force_path_style            = true
    # Access key and secret key will be provided via environment variables:
    # AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY
  }
}