#!/usr/bin/env python3
"""
Infisical Secret Subscriber using Kubernetes Native Auth

This implementation uses Kubernetes service account tokens for authentication,
eliminating the need for long-lived machine tokens.

Features:
- Kubernetes native authentication (Client JWT method)
- Automatic token renewal at 80% of TTL
- Service account token auto-refresh (handled by Kubernetes)
- Thread-safe secret access
- Webhook support for real-time updates
"""

import os
import json
import time
import logging
import threading
import requests
from datetime import datetime, timedelta
from typing import Dict, Optional, Callable, Any
from pathlib import Path

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class InfisicalK8sAuthSubscriber:
    """Infisical client with Kubernetes native authentication"""
    
    SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token"
    TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60  # 7 days
    RENEWAL_THRESHOLD = 0.8  # Renew at 80% of TTL
    
    def __init__(self):
        """Initialize the subscriber with environment configuration"""
        self.api_url = os.getenv(
            "INFISICAL_API_URL", 
            "http://infisical.infisical.svc.cluster.local:8080/api"
        )
        self.identity_id = os.getenv("INFISICAL_IDENTITY_ID")
        self.project_id = os.getenv("INFISICAL_PROJECT_ID")
        self.environment = os.getenv("INFISICAL_ENVIRONMENT", "production")
        self.secret_path = os.getenv("INFISICAL_SECRET_PATH", "/")
        
        if not self.identity_id:
            raise ValueError("INFISICAL_IDENTITY_ID must be set")
        
        # Authentication state
        self.access_token = None
        self.token_expiry = None
        self.last_renewal = None
        
        # Secrets cache
        self.secrets = {}
        self.callbacks = {}
        self.lock = threading.RLock()
        
        # Background threads
        self.running = False
        self.renewal_thread = None
        self.refresh_thread = None
        
        logger.info(f"Initialized Infisical K8s Auth Subscriber")
        logger.info(f"API URL: {self.api_url}")
        logger.info(f"Identity ID: {self.identity_id}")
        logger.info(f"Environment: {self.environment}")
        logger.info(f"Secret Path: {self.secret_path}")
    
    def _read_service_account_token(self) -> str:
        """Read the Kubernetes service account token"""
        try:
            with open(self.SA_TOKEN_PATH, 'r') as f:
                return f.read().strip()
        except FileNotFoundError:
            # For local development, try environment variable
            token = os.getenv("KUBERNETES_SERVICE_ACCOUNT_TOKEN")
            if token:
                return token
            raise RuntimeError(f"Service account token not found at {self.SA_TOKEN_PATH}")
    
    def authenticate(self):
        """Authenticate using Kubernetes service account token"""
        try:
            sa_token = self._read_service_account_token()
            
            # Authenticate with Infisical
            response = requests.post(
                f"{self.api_url}/v1/auth/kubernetes/login",
                json={
                    "identityId": self.identity_id,
                    "serviceAccountToken": sa_token
                },
                timeout=10
            )
            response.raise_for_status()
            
            data = response.json()
            self.access_token = data["accessToken"]
            expires_in = data.get("expiresIn", self.TOKEN_TTL_SECONDS)
            
            self.token_expiry = datetime.now() + timedelta(seconds=expires_in)
            self.last_renewal = datetime.now()
            
            logger.info("Successfully authenticated with Infisical using Kubernetes auth")
            logger.info(f"Token expires at: {self.token_expiry}")
            
        except Exception as e:
            logger.error(f"Failed to authenticate: {e}")
            raise
    
    def _renew_token_if_needed(self):
        """Check and renew token if approaching expiry"""
        try:
            if not self.token_expiry:
                self.authenticate()
                return
            
            time_until_expiry = (self.token_expiry - datetime.now()).total_seconds()
            renewal_threshold = self.TOKEN_TTL_SECONDS * self.RENEWAL_THRESHOLD
            
            if time_until_expiry < renewal_threshold:
                logger.info("Token approaching expiry, renewing...")
                self.authenticate()
        except Exception as e:
            logger.error(f"Failed to renew token: {e}")
    
    def load_secrets(self):
        """Load secrets from Infisical"""
        try:
            if not self.access_token:
                self.authenticate()
            
            response = requests.get(
                f"{self.api_url}/v3/secrets/raw",
                headers={
                    "Authorization": f"Bearer {self.access_token}",
                    "X-Environment": self.environment,
                    "X-Secret-Path": self.secret_path
                },
                timeout=10
            )
            response.raise_for_status()
            
            data = response.json()
            new_secrets = {}
            
            for secret in data.get("secrets", []):
                key = secret["secretKey"]
                value = secret["secretValue"]
                new_secrets[key] = value
                
                # Check for changes
                with self.lock:
                    old_value = self.secrets.get(key)
                    if old_value != value:
                        self._notify_callbacks(key, value, old_value)
            
            with self.lock:
                self.secrets = new_secrets
            
            logger.info(f"Loaded {len(new_secrets)} secrets from Infisical")
            
        except Exception as e:
            logger.error(f"Failed to load secrets: {e}")
    
    def get(self, key: str) -> Optional[str]:
        """Get a secret value"""
        with self.lock:
            return self.secrets.get(key)
    
    def get_all(self) -> Dict[str, str]:
        """Get all secrets"""
        with self.lock:
            return self.secrets.copy()
    
    def subscribe(self, key: str, callback: Callable[[str, str, Optional[str]], None]):
        """Subscribe to secret updates"""
        with self.lock:
            if key not in self.callbacks:
                self.callbacks[key] = []
            self.callbacks[key].append(callback)
    
    def _notify_callbacks(self, key: str, new_value: str, old_value: Optional[str]):
        """Notify callbacks of secret changes"""
        callbacks = self.callbacks.get(key, [])
        for callback in callbacks:
            try:
                callback(key, new_value, old_value)
            except Exception as e:
                logger.error(f"Callback error for key {key}: {e}")
    
    def _renewal_worker(self):
        """Background worker for token renewal only"""
        while self.running:
            try:
                self._renew_token_if_needed()
                # Check token expiry every hour
                time.sleep(3600)
            except Exception as e:
                logger.error(f"Renewal worker error: {e}")
                time.sleep(60)  # Retry after 1 minute
    
    def _webhook_handler(self, environ, start_response):
        """WSGI webhook handler for Infisical events"""
        from wsgiref.simple_server import make_server
        import json
        
        path = environ.get('PATH_INFO', '')
        method = environ.get('REQUEST_METHOD', '')
        
        if path == '/health' and method == 'GET':
            start_response('200 OK', [('Content-Type', 'text/plain')])
            return [b'OK']
        
        if path == '/infisical/webhook' and method == 'POST':
            try:
                content_length = int(environ.get('CONTENT_LENGTH', 0))
                body = environ['wsgi.input'].read(content_length).decode('utf-8')
                data = json.loads(body)
                
                event = data.get('event')
                payload = data.get('payload', {})
                
                logger.info(f"Received webhook event: {event}")
                
                if event in ['secret.created', 'secret.updated', 'secret.rotated']:
                    key = payload.get('secretKey')
                    value = payload.get('secretValue')
                    
                    with self.lock:
                        old_value = self.secrets.get(key)
                        self.secrets[key] = value
                        self._notify_callbacks(key, value, old_value)
                
                elif event == 'secret.deleted':
                    key = payload.get('secretKey')
                    with self.lock:
                        old_value = self.secrets.pop(key, None)
                        self._notify_callbacks(key, None, old_value)
                
                start_response('200 OK', [('Content-Type', 'text/plain')])
                return [b'OK']
                
            except Exception as e:
                logger.error(f"Webhook processing error: {e}")
                start_response('500 Internal Server Error', [('Content-Type', 'text/plain')])
                return [b'Error']
        
        start_response('404 Not Found', [('Content-Type', 'text/plain')])
        return [b'Not Found']
    
    def start(self, webhook_port=8080):
        """Start the subscriber with webhook server"""
        if self.running:
            return
        
        logger.info("Starting Infisical K8s Auth Subscriber")
        
        # Initial authentication and load
        self.authenticate()
        self.load_secrets()  # Load initial secrets once
        
        # Start background workers
        self.running = True
        
        # Token renewal thread only
        self.renewal_thread = threading.Thread(target=self._renewal_worker, daemon=True)
        self.renewal_thread.start()
        
        # Start webhook server for secret updates
        from wsgiref.simple_server import make_server
        logger.info(f"Starting webhook server on port {webhook_port}")
        
        self.webhook_thread = threading.Thread(
            target=lambda: make_server('', webhook_port, self._webhook_handler).serve_forever(),
            daemon=True
        )
        self.webhook_thread.start()
        
        logger.info("Infisical K8s Auth Subscriber started with webhook server")
    
    def stop(self):
        """Stop the subscriber"""
        if not self.running:
            return
        
        logger.info("Stopping Infisical K8s Auth Subscriber")
        self.running = False
        
        if self.renewal_thread:
            self.renewal_thread.join(timeout=5)
        if self.webhook_thread:
            self.webhook_thread.join(timeout=5)
        
        logger.info("Infisical K8s Auth Subscriber stopped")


# Example usage
if __name__ == "__main__":
    subscriber = InfisicalK8sAuthSubscriber()
    
    # Subscribe to specific secrets
    def on_database_update(key, new_value, old_value):
        print(f"Database URL updated: {new_value}")
        # Reconnect to database with new URL
    
    def on_api_key_update(key, new_value, old_value):
        print(f"API key rotated")
        # Update API client configuration
    
    subscriber.subscribe("DATABASE_URL", on_database_update)
    subscriber.subscribe("API_KEY", on_api_key_update)
    
    # Start the subscriber
    subscriber.start()
    
    # Get secrets
    db_url = subscriber.get("DATABASE_URL")
    api_key = subscriber.get("API_KEY")
    
    print(f"Database URL: {db_url}")
    print(f"API Key: {api_key}")
    
    # Keep running
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        subscriber.stop()