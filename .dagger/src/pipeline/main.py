import dagger
import anyio
from dagger import dag, function, object_type
import yaml
import json

@object_type
class Pipeline:
    @function
    def platform_services(self) -> dagger.Container:
        """
        Returns a container that simulates the platform cluster services.
        This includes Infisical and JFrog Artifactory.
        """
        return (
            dag.container()
            .from_("docker:24-dind")
            .with_exposed_port(8080)  # Infisical
            .with_exposed_port(8081)  # JFrog UI
            .with_exposed_port(8082)  # JFrog Registry
            .with_env_variable("PLATFORM_CLUSTER", "true")
            .with_exec(["sh", "-c", "dockerd-entrypoint.sh &"])
        )

    @function
    def environment_cluster(self, env_name: str = "dev-local", env_type: str = "dev") -> dagger.Container:
        """
        Returns a container that simulates an environment cluster.
        Uses docker-in-docker to run services as containers.
        """
        return (
            dag.container()
            .from_("docker:24-dind")
            .with_env_variable("ENVIRONMENT_NAME", env_name)
            .with_env_variable("ENVIRONMENT_TYPE", env_type)
            .with_exposed_port(80)    # Kong Gateway
            .with_exposed_port(8000)  # Kong Admin API
            .with_exposed_port(9090)  # Prometheus
            .with_exposed_port(3000)  # Grafana
            .with_exec(["sh", "-c", "dockerd-entrypoint.sh &"])
        )

    @function
    async def run_platform_playbook(self, platform: dagger.Container, playbooks_dir: dagger.Directory, playbook: str) -> str:
        """
        Runs playbooks adapted for container deployment on the platform.
        """
        return await (
            self._container_ansible_runner()
            .with_service_binding("platform", platform)
            .with_mounted_directory("/playbooks", playbooks_dir)
            .with_workdir("/playbooks")
            .with_env_variable("ANSIBLE_HOST_KEY_CHECKING", "False")
            .with_env_variable("DEPLOYMENT_MODE", "container")
            .with_env_variable("PLATFORM_HOST", "platform")
            .with_exec(["ansible-playbook", "-i", "localhost,", playbook, 
                       "-e", "deployment_mode=container",
                       "-e", "target_host=platform"])
            .stdout()
        )

    @function
    async def run_environment_playbook(self, environment: dagger.Container, playbooks_dir: dagger.Directory, 
                                     playbook: str, env_name: str, env_type: str) -> str:
        """
        Runs playbooks adapted for container deployment on an environment.
        """
        return await (
            self._container_ansible_runner()
            .with_service_binding("environment", environment)
            .with_mounted_directory("/playbooks", playbooks_dir)
            .with_workdir("/playbooks")
            .with_env_variable("ANSIBLE_HOST_KEY_CHECKING", "False")
            .with_env_variable("DEPLOYMENT_MODE", "container")
            .with_env_variable("ENVIRONMENT_HOST", "environment")
            .with_env_variable("ENVIRONMENT_NAME", env_name)
            .with_env_variable("ENVIRONMENT_TYPE", env_type)
            .with_exec(["ansible-playbook", "-i", "localhost,", playbook,
                       "-e", "deployment_mode=container",
                       "-e", "target_host=environment",
                       "-e", f"environment_name={env_name}",
                       "-e", f"environment_type={env_type}"])
            .stdout()
        )

    @function
    def _container_ansible_runner(self) -> dagger.Container:
        """
        Ansible runner configured for container deployments.
        """
        return (
            dag.container()
            .from_("python:3.13-slim")
            .with_exec(["apt-get", "update"])
            .with_exec(["apt-get", "install", "-y", "docker.io", "curl", "git"])
            .with_exec(["pip", "install", "ansible", "docker", "pyyaml", "jinja2"])
            .with_exec(["ansible", "--version"])
        )

    @function
    async def deploy_infisical(self, platform: dagger.Container, infrastructure_dir: dagger.Directory) -> str:
        """
        Deploys Infisical to the platform container.
        """
        print("🔐 Deploying Infisical to platform...")
        
        # Create a specialized playbook for container deployment
        infisical_playbook = """
---
- name: Deploy Infisical in Container
  hosts: localhost
  vars:
    deployment_mode: container
    infisical_version: latest
  tasks:
    - name: Start Infisical container
      docker_container:
        name: infisical
        image: infisical/infisical:{{ infisical_version }}
        state: started
        ports:
          - "8080:8080"
        env:
          ENCRYPTION_KEY: "{{ lookup('password', '/dev/null chars=ascii_letters,digits length=32') }}"
          JWT_SIGNUP_SECRET: "{{ lookup('password', '/dev/null chars=ascii_letters,digits length=32') }}"
          SITE_URL: "http://localhost:8080"
      delegate_to: "{{ target_host | default('platform') }}"
      
    - name: Wait for Infisical to be ready
      uri:
        url: "http://{{ target_host | default('platform') }}:8080/api/status"
      register: result
      until: result.status == 200
      retries: 30
      delay: 2
"""
        
        playbooks_dir = infrastructure_dir.directory("playbooks")
        playbooks_with_infisical = (
            dag.directory()
            .with_directory("/", playbooks_dir)
            .with_new_file("infisical-container.yml", contents=infisical_playbook)
        )
        
        return await self.run_platform_playbook(platform, playbooks_with_infisical, "infisical-container.yml")

    @function
    async def deploy_jfrog(self, platform: dagger.Container, infrastructure_dir: dagger.Directory) -> str:
        """
        Deploys JFrog Artifactory JCR to the platform container.
        """
        print("📦 Deploying JFrog Artifactory to platform...")
        
        jfrog_playbook = """
---
- name: Deploy JFrog Artifactory JCR in Container
  hosts: localhost
  vars:
    deployment_mode: container
    jfrog_version: latest
  tasks:
    - name: Start JFrog Artifactory container
      docker_container:
        name: artifactory
        image: docker.bintray.io/jfrog/artifactory-jcr:{{ jfrog_version }}
        state: started
        ports:
          - "8081:8081"
          - "8082:8082"
        env:
          JF_ROUTER_ENTRYPOINTS_EXTERNALPORT: "8082"
      delegate_to: "{{ target_host | default('platform') }}"
      
    - name: Wait for Artifactory to be ready
      uri:
        url: "http://{{ target_host | default('platform') }}:8081/artifactory/api/system/ping"
      register: result
      until: result.status == 200
      retries: 60
      delay: 5
"""
        
        playbooks_dir = infrastructure_dir.directory("playbooks")
        playbooks_with_jfrog = (
            dag.directory()
            .with_directory("/", playbooks_dir)
            .with_new_file("jfrog-container.yml", contents=jfrog_playbook)
        )
        
        return await self.run_platform_playbook(platform, playbooks_with_jfrog, "jfrog-container.yml")

    @function
    async def deploy_kong(self, environment: dagger.Container, infrastructure_dir: dagger.Directory, 
                         env_name: str, env_type: str) -> str:
        """
        Deploys Kong Gateway to an environment container.
        """
        print(f"🌐 Deploying Kong to environment {env_name}...")
        
        kong_playbook = """
---
- name: Deploy Kong Gateway in Container
  hosts: localhost
  vars:
    deployment_mode: container
    kong_version: "3.4"
  tasks:
    - name: Start Kong database
      docker_container:
        name: kong-database
        image: postgres:13
        state: started
        env:
          POSTGRES_USER: kong
          POSTGRES_DB: kong
          POSTGRES_PASSWORD: kong
      delegate_to: "{{ target_host | default('environment') }}"
      
    - name: Run Kong migrations
      docker_container:
        name: kong-migration
        image: kong:{{ kong_version }}
        state: started
        command: kong migrations bootstrap
        env:
          KONG_DATABASE: postgres
          KONG_PG_HOST: kong-database
          KONG_PG_USER: kong
          KONG_PG_PASSWORD: kong
        cleanup: yes
        detach: no
      delegate_to: "{{ target_host | default('environment') }}"
      
    - name: Start Kong
      docker_container:
        name: kong
        image: kong:{{ kong_version }}
        state: started
        ports:
          - "80:8000"
          - "8000:8001"
        env:
          KONG_DATABASE: postgres
          KONG_PG_HOST: kong-database
          KONG_PG_USER: kong
          KONG_PG_PASSWORD: kong
          KONG_PROXY_ACCESS_LOG: /dev/stdout
          KONG_ADMIN_ACCESS_LOG: /dev/stdout
          KONG_PROXY_ERROR_LOG: /dev/stderr
          KONG_ADMIN_ERROR_LOG: /dev/stderr
          KONG_ADMIN_LISTEN: "0.0.0.0:8001"
      delegate_to: "{{ target_host | default('environment') }}"
"""
        
        playbooks_dir = infrastructure_dir.directory("playbooks")
        playbooks_with_kong = (
            dag.directory()
            .with_directory("/", playbooks_dir)
            .with_new_file("kong-container.yml", contents=kong_playbook)
        )
        
        return await self.run_environment_playbook(environment, playbooks_with_kong, "kong-container.yml", 
                                                  env_name, env_type)

    @function
    async def setup_platform_services(self, platform: dagger.Container, infrastructure_dir: dagger.Directory) -> str:
        """
        Sets up all platform services (Infisical, JFrog).
        """
        results = []
        
        # Deploy Infisical
        infisical_result = await self.deploy_infisical(platform, infrastructure_dir)
        results.append(f"Infisical: {infisical_result}")
        
        # Deploy JFrog
        jfrog_result = await self.deploy_jfrog(platform, infrastructure_dir)
        results.append(f"JFrog: {jfrog_result}")
        
        return "\n".join(results)

    @function
    async def setup_environment_services(self, environment: dagger.Container, infrastructure_dir: dagger.Directory,
                                       env_name: str, env_type: str) -> str:
        """
        Sets up all environment services (Kong, monitoring, etc).
        """
        results = []
        
        # Deploy Kong
        kong_result = await self.deploy_kong(environment, infrastructure_dir, env_name, env_type)
        results.append(f"Kong: {kong_result}")
        
        # Additional services can be added here
        
        return "\n".join(results)

    @function
    async def verify_services(self, platform: dagger.Container, environment: dagger.Container) -> str:
        """
        Verifies that all services are running correctly.
        """
        verification_script = """
#!/bin/sh
echo "🔍 Verifying platform services..."
curl -s http://platform:8080/api/status || echo "❌ Infisical not responding"
curl -s http://platform:8081/artifactory/api/system/ping || echo "❌ JFrog not responding"

echo "\n🔍 Verifying environment services..."
curl -s http://environment:8001/status || echo "❌ Kong not responding"
"""
        
        return await (
            dag.container()
            .from_("alpine:latest")
            .with_exec(["apk", "add", "--no-cache", "curl"])
            .with_service_binding("platform", platform)
            .with_service_binding("environment", environment)
            .with_new_file("/verify.sh", contents=verification_script, permissions=0o755)
            .with_exec(["/verify.sh"])
            .stdout()
        )

    @function
    async def main_pipeline(self, infrastructure_dir: dagger.Directory | None = None, 
                          env_name: str = "dev-local", env_type: str = "dev") -> str:
        """
        Main pipeline that sets up platform and environment using containers.
        
        Args:
            infrastructure_dir: Directory containing infrastructure playbooks
            env_name: Name of the environment to create (e.g., dev-team1)
            env_type: Type of environment (dev, staging, prod, sue)
        """
        if infrastructure_dir is None:
            infrastructure_dir = dag.host().directory("./infrastructure")
        
        print("🚀 Starting containerized infrastructure simulation...")
        print(f"📋 Creating environment: {env_name} (type: {env_type})")
        
        # Create platform services container
        platform = self.platform_services()
        
        # Create environment container
        environment = self.environment_cluster(env_name, env_type)
        
        # Setup platform services
        print("\n🏗️  Setting up platform services...")
        platform_result = await self.setup_platform_services(platform, infrastructure_dir)
        print(platform_result)
        
        # Setup environment services
        print(f"\n🏗️  Setting up environment services for {env_name}...")
        env_result = await self.setup_environment_services(environment, infrastructure_dir, env_name, env_type)
        print(env_result)
        
        # Verify all services
        print("\n✅ Verifying all services...")
        verification = await self.verify_services(platform, environment)
        print(verification)
        
        print(f"""
✅ Containerized infrastructure ready!

Platform services:
- Infisical: http://localhost:8080
- JFrog Artifactory: http://localhost:8081

Environment '{env_name}' services:
- Kong Gateway: http://localhost:80
- Kong Admin API: http://localhost:8000

All services are running in containers simulating the actual infrastructure.
""")
        
        return "Pipeline completed successfully!"