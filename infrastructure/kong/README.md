# Kong API Gateway

Kong is deployed as a cross-service API gateway in the application cluster, providing centralized API management, routing, and security for all services.

## Architecture

- **Deployment**: In-cluster using Helm chart
- **Database**: PostgreSQL for configuration persistence
- **Node Type**: Runs on `general-public` nodes alongside applications
- **Load Balancer**: Vultr Load Balancer for external access

## Features

- **API Routing**: Centralized routing for all services
- **Rate Limiting**: Global and per-service rate limits
- **Authentication**: JWT, API Key, OAuth2 support
- **Monitoring**: Prometheus metrics integration
- **Health Checks**: Active and passive health checking
- **CORS**: Cross-Origin Resource Sharing support
- **Request/Response Transformation**: Header manipulation

## Service Configuration

Services are automatically discovered and configured through:

1. **Kubernetes Ingress**: Services create Ingress resources with `kubernetes.io/ingress.class: kong`
2. **Kong Ingress**: Advanced routing configuration via CRDs
3. **Kong Plugins**: Rate limiting, authentication, etc.

## Adding a New Service

To expose a service through Kong, create an Ingress in your service namespace:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: my-service
  namespace: my-service
  annotations:
    kubernetes.io/ingress.class: kong
    konghq.com/strip-path: "true"
    konghq.com/plugins: rate-limiting,cors
spec:
  rules:
  - http:
      paths:
      - path: /api/v1/my-service
        pathType: Prefix
        backend:
          service:
            name: my-service
            port:
              number: 80
```

## Available Plugins

### Global Plugins (applied to all routes)
- **rate-limiting**: 1000/minute, 100000/hour
- **prometheus**: Metrics collection
- **correlation-id**: Request tracking

### Per-Service Plugins
- **cors**: Cross-origin requests
- **request-transformer**: Header manipulation
- **jwt**: JWT authentication
- **key-auth**: API key authentication
- **oauth2**: OAuth2 authentication

## Management

### Access Kong Admin API
```bash
kubectl port-forward -n kong svc/kong-kong-admin 8001:8001
curl http://localhost:8001/status
```

### Access Kong Manager UI
```bash
kubectl port-forward -n kong svc/kong-kong-manager 8002:8002
# Open http://localhost:8002 in browser
```

### View Routes
```bash
curl http://localhost:8001/routes
```

### View Services
```bash
curl http://localhost:8001/services
```

## Monitoring

Kong exposes Prometheus metrics at `/metrics` endpoint:

```bash
kubectl port-forward -n kong svc/kong-kong-proxy 8000:80
curl http://localhost:8000/metrics
```

Key metrics:
- Request rate and latency
- Upstream health status
- Response status codes
- Bandwidth usage

## External Access

Kong proxy is exposed via Vultr Load Balancer. Get the external IP:

```bash
kubectl get svc -n kong kong-kong-proxy
```

All services are accessible through: `http://<EXTERNAL-IP>/api/v1/<service-path>`