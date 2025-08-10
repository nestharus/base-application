# Infisical Secret Management Integration

This is the authoritative guide for integrating services with Infisical using Kubernetes native authentication.

## Architecture Overview

```
┌──────────────────┐
│    Infisical     │ ← Stores secrets, validates K8s tokens
└────────┬─────────┘
         │ Kubernetes Auth (Client JWT)
         ↓
┌──────────────────┐
│   K8s Service    │ ← Service Account Token (auto-refreshed)
│     Account      │    Mounted at /var/run/secrets/...
└────────┬─────────┘
         │ No long-lived tokens!
         ↓
┌──────────────────┐
│   Application    │ ← Uses SA token for auth, auto-renews
└──────────────────┘
```

## How It Works

### 1. Automatic Secret Rotation in Infisical

**Infisical automatically rotates secrets** with our configuration:
- **Auto-rotation**: Enabled with 30-day interval
- **Webhook events**: Sent when secrets are rotated
- **Services handle rotation**: Reconnect to DB, reload certs, etc.

When Infisical rotates a secret:
1. Updates the secret value automatically
2. Sends webhook event to all registered services
3. Services handle the rotation gracefully

### 2. Kubernetes Native Authentication

**No more long-lived tokens!** Applications use Kubernetes service account tokens:
- **Service Account Token**: Auto-refreshed every hour by Kubernetes
- **Access Token**: Valid for 7 days, auto-renewed at 80% TTL (5.6 days)
- **Max Renewal**: Can continue renewing for up to 180 days

### 2. Authentication Flow

1. Pod uses its service account token (mounted automatically)
2. Authenticates with Infisical using Kubernetes auth (Client JWT method)
3. Receives access token valid for 7 days
4. SDK/client auto-renews token before expiry
5. Kubernetes refreshes SA token hourly (transparent to app)

### 3. Secret Updates via Webhooks (NO POLLING!)

**Critical**: We use webhooks for real-time updates, NOT polling:

1. **Initial Load**: Fetch secrets ONCE on startup
2. **Webhook Server**: Each pod runs webhook server (port 8080)
3. **Real-time Updates**: Infisical pushes changes via webhooks
4. **No Polling**: We NEVER poll for secret changes
5. **Token Renewal Only**: Background task only for auth token renewal

### 4. Application Flow

Applications:
1. Read service account token from mounted path
2. Authenticate with Infisical using K8s auth
3. Load secrets ONCE initially
4. Start webhook server for updates
5. Receive real-time secret changes via webhooks
6. Auto-renew auth tokens (NOT polling for secrets)

## Deployment Instructions

### Step 1: Deploy Your Service

Add these annotations to your deployment:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: my-service-sa
  namespace: my-namespace
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: my-service-auth-delegator
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: system:auth-delegator
subjects:
- kind: ServiceAccount
  name: my-service-sa
  namespace: my-namespace
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-service
  namespace: my-namespace
  annotations:
    infisical.com/auth: "kubernetes"         # Use Kubernetes auth
    infisical.com/auto-renewal: "true"       # Enable auto-renewal
    infisical.com/environment: "production"   # Environment
    infisical.com/secret-path: "/services/my-service"  # Secret path
spec:
  replicas: 3
  template:
    spec:
      serviceAccountName: my-service-sa  # Use the service account
      containers:
      - name: app
        image: my-app:latest
        ports:
        - containerPort: 8080
        env:
        - name: INFISICAL_API_URL
          value: "http://infisical.infisical.svc.cluster.local:8080/api"
        - name: INFISICAL_IDENTITY_ID
          valueFrom:
            configMapKeyRef:
              name: my-service-infisical-config
              key: identity-id
        - name: INFISICAL_PROJECT_ID
          value: "my-project"
        - name: INFISICAL_ENVIRONMENT
          value: "production"
        - name: INFISICAL_SECRET_PATH
          value: "/services/my-service"
        # Service account token is auto-mounted at:
        # /var/run/secrets/kubernetes.io/serviceaccount/token
---
apiVersion: v1
kind: Service
metadata:
  name: my-service
  namespace: my-namespace
spec:
  selector:
    app: my-service
  ports:
  - port: 8080
    targetPort: 8080
```

### Step 2: Use the Code Examples

#### Python (`python_subscriber.py`)

```python
from python_subscriber import InfisicalK8sAuthSubscriber

# Initialize with Kubernetes auth
subscriber = InfisicalK8sAuthSubscriber()

# Subscribe to updates
subscriber.subscribe("DATABASE_URL", on_database_update)

# Start (handles auth and renewal automatically)
subscriber.start()

# Get secrets
db_url = subscriber.get("DATABASE_URL")
api_key = subscriber.get("API_KEY")
```

#### Java (`JavaSecretSubscriber.java`)

```java
import com.example.infisical.JavaSecretSubscriber;

public class MyApp {
    public static void main(String[] args) {
        JavaSecretSubscriber subscriber = new JavaSecretSubscriber();
        
        // Subscribe to updates
        subscriber.subscribe("DATABASE_URL", (key, newValue, oldValue) -> {
            // Reconnect with new credentials
            reconnectDatabase(newValue);
        });
        
        // Start (handles auth and renewal automatically)
        subscriber.start();
        
        // Get secrets
        String dbUrl = subscriber.get("DATABASE_URL");
    }
}
```

## Webhook Events

Your application will receive these webhook events:

### `secret.created`
```json
{
  "event": "secret.created",
  "payload": {
    "secretKey": "NEW_SECRET",
    "secretValue": "value123"
  }
}
```

### `secret.updated`
```json
{
  "event": "secret.updated",
  "payload": {
    "secretKey": "EXISTING_SECRET",
    "secretValue": "newValue456"
  }
}
```

### `secret.rotated`
```json
{
  "event": "secret.rotated",
  "payload": {
    "secretKey": "DATABASE_PASSWORD",
    "secretValue": "rotated789",
    "rotation": true
  }
}
```

### Token Renewal
Tokens are automatically renewed:
- **Service Account Token**: Refreshed hourly by Kubernetes (transparent)
- **Access Token**: Renewed at 80% of TTL by the SDK/client
- **No pod restarts needed** for token renewal

## Secret Rotation Handling

When Infisical rotates a secret, your application receives a `secret.rotated` event. You should:

1. **Database Credentials**: Reconnect with new credentials
2. **API Keys**: Update client configuration
3. **Certificates**: Reload TLS configuration

Example:
```python
def _handle_rotation(self, key: str, new_value: str):
    if key == "DATABASE_URL":
        # Close old connection
        self.db.close()
        # Connect with new credentials
        self.db = connect(new_value)
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `INFISICAL_API_URL` | Infisical API endpoint | `http://infisical.infisical.svc.cluster.local:8080/api` |
| `INFISICAL_ENVIRONMENT` | Environment to fetch secrets from | `dev` |
| `INFISICAL_SECRET_PATH` | Path to secrets in Infisical | `/` |
| `INFISICAL_IDENTITY_ID` | Kubernetes auth identity ID | Required |
| `INFISICAL_PROJECT_ID` | Project ID in Infisical | Required |

## Files in This Directory

- `python_subscriber.py` - Python implementation with Kubernetes auth and webhook server
- `JavaSecretSubscriber.java` - Java implementation with Kubernetes auth and webhook server
- `README.md` - This file

## Troubleshooting

### Authentication Failed
- Check service account has `system:auth-delegator` ClusterRoleBinding
- Verify `INFISICAL_IDENTITY_ID` is set correctly
- Check identity exists in Infisical with Kubernetes auth configured
- Verify service account token is mounted at `/var/run/secrets/kubernetes.io/serviceaccount/token`

### Webhooks Not Received
- Verify Service is exposed on port 8080
- Check webhook registration in Infisical UI
- Ensure `/infisical/webhook` endpoint is accessible

### Secrets Not Loading
- Verify machine token is valid
- Check network connectivity to Infisical
- Verify secret path and environment are correct

## Important Notes

1. **No long-lived tokens** - Uses Kubernetes native auth
2. **Automatic renewal** - Tokens renewed before expiry
3. **Persistent access** - Can renew for up to 180 days
4. **No pod restarts** - Token renewal is seamless
5. **Service account tokens** - Refreshed hourly by Kubernetes
6. **Secure by default** - No secrets stored in K8s secrets