package com.example.infisical;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Spark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Infisical Secret Subscriber using Kubernetes Native Auth
 * 
 * This implementation uses Kubernetes service account tokens for authentication,
 * eliminating the need for long-lived machine tokens.
 * 
 * Features:
 * - Kubernetes native authentication (Client JWT method)
 * - Automatic token renewal at 80% of TTL
 * - Service account token auto-refresh (handled by Kubernetes)
 * - Thread-safe secret access
 * - Webhook support for real-time updates
 */
public class JavaSecretSubscriber {
    private static final Logger logger = LoggerFactory.getLogger(JavaSecretSubscriber.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // Configuration
    private final String apiUrl;
    private final String identityId;
    private final String projectId;
    private final String environment;
    private final String secretPath;
    
    // Authentication
    private String accessToken;
    private long tokenExpiryTime;
    private final AtomicLong lastRenewalTime = new AtomicLong(0);
    
    // Secrets cache
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final Map<String, List<SecretUpdateCallback>> callbacks = new ConcurrentHashMap<>();
    
    // Executors
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // HTTP client
    private final OkHttpClient httpClient;
    
    // Constants
    private static final String SA_TOKEN_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";
    private static final long TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000L; // 7 days in milliseconds
    private static final double RENEWAL_THRESHOLD = 0.8; // Renew at 80% of TTL
    
    public JavaSecretSubscriber() {
        this.apiUrl = System.getenv().getOrDefault("INFISICAL_API_URL", 
            "http://infisical.infisical.svc.cluster.local:8080/api");
        this.identityId = System.getenv("INFISICAL_IDENTITY_ID");
        this.projectId = System.getenv("INFISICAL_PROJECT_ID");
        this.environment = System.getenv().getOrDefault("INFISICAL_ENVIRONMENT", "production");
        this.secretPath = System.getenv().getOrDefault("INFISICAL_SECRET_PATH", "/");
        
        if (identityId == null || identityId.isEmpty()) {
            throw new IllegalStateException("INFISICAL_IDENTITY_ID must be set");
        }
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
            
        logger.info("Initialized Infisical K8s Auth Subscriber");
        logger.info("API URL: {}", apiUrl);
        logger.info("Identity ID: {}", identityId);
        logger.info("Environment: {}", environment);
        logger.info("Secret Path: {}", secretPath);
    }
    
    /**
     * Start the subscriber with automatic authentication and renewal
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting Infisical K8s Auth Subscriber");
            
            // Initial authentication
            authenticate();
            
            // Load initial secrets
            loadSecrets();
            
            // Schedule token renewal only
            long renewalInterval = (long)(TOKEN_TTL_MS * RENEWAL_THRESHOLD);
            scheduler.scheduleAtFixedRate(this::renewTokenIfNeeded, 
                renewalInterval, renewalInterval, TimeUnit.MILLISECONDS);
            
            // Start webhook server for secret updates (no polling!)
            startWebhookServer();
            
            logger.info("Infisical K8s Auth Subscriber started successfully");
        }
    }
    
    /**
     * Authenticate using Kubernetes service account token
     */
    private void authenticate() {
        try {
            // Read service account token
            String saToken = new String(Files.readAllBytes(Paths.get(SA_TOKEN_PATH))).trim();
            
            // Prepare authentication request
            String authJson = String.format(
                "{\"identityId\": \"%s\", \"serviceAccountToken\": \"%s\"}",
                identityId, saToken
            );
            
            RequestBody body = RequestBody.create(
                authJson, MediaType.get("application/json"));
            
            Request request = new Request.Builder()
                .url(apiUrl + "/v1/auth/kubernetes/login")
                .post(body)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Authentication failed: " + response.code());
                }
                
                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);
                
                this.accessToken = json.get("accessToken").asText();
                long expiresIn = json.get("expiresIn").asLong();
                this.tokenExpiryTime = System.currentTimeMillis() + (expiresIn * 1000);
                this.lastRenewalTime.set(System.currentTimeMillis());
                
                logger.info("Successfully authenticated with Infisical using Kubernetes auth");
                logger.info("Token expires at: {}", new Date(tokenExpiryTime));
            }
        } catch (Exception e) {
            logger.error("Failed to authenticate with Infisical", e);
            throw new RuntimeException("Authentication failed", e);
        }
    }
    
    /**
     * Renew token if approaching expiry
     */
    private void renewTokenIfNeeded() {
        try {
            long now = System.currentTimeMillis();
            long timeUntilExpiry = tokenExpiryTime - now;
            long renewalThreshold = (long)(TOKEN_TTL_MS * RENEWAL_THRESHOLD);
            
            if (timeUntilExpiry < renewalThreshold) {
                logger.info("Token approaching expiry, renewing...");
                authenticate(); // Re-authenticate with fresh SA token
            }
        } catch (Exception e) {
            logger.error("Failed to renew token", e);
        }
    }
    
    /**
     * Load secrets from Infisical
     */
    private void loadSecrets() {
        try {
            Request request = new Request.Builder()
                .url(apiUrl + "/v3/secrets/raw")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Environment", environment)
                .header("X-Secret-Path", secretPath)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Failed to fetch secrets: " + response.code());
                }
                
                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);
                JsonNode secretsNode = json.get("secrets");
                
                if (secretsNode != null && secretsNode.isArray()) {
                    Map<String, String> newSecrets = new HashMap<>();
                    
                    for (JsonNode secretNode : secretsNode) {
                        String key = secretNode.get("secretKey").asText();
                        String value = secretNode.get("secretValue").asText();
                        newSecrets.put(key, value);
                        
                        // Check for changes
                        String oldValue = secrets.get(key);
                        if (oldValue == null || !oldValue.equals(value)) {
                            notifyCallbacks(key, value, oldValue);
                        }
                    }
                    
                    secrets.clear();
                    secrets.putAll(newSecrets);
                    
                    logger.info("Loaded {} secrets from Infisical", secrets.size());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load secrets", e);
        }
    }
    
    /**
     * Get a secret value
     */
    public String get(String key) {
        return secrets.get(key);
    }
    
    /**
     * Get all secrets
     */
    public Map<String, String> getAll() {
        return new HashMap<>(secrets);
    }
    
    /**
     * Subscribe to secret updates
     */
    public void subscribe(String key, SecretUpdateCallback callback) {
        callbacks.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(callback);
    }
    
    /**
     * Notify callbacks of secret changes
     */
    private void notifyCallbacks(String key, String newValue, String oldValue) {
        List<SecretUpdateCallback> keyCallbacks = callbacks.get(key);
        if (keyCallbacks != null) {
            for (SecretUpdateCallback callback : keyCallbacks) {
                try {
                    callback.onUpdate(key, newValue, oldValue);
                } catch (Exception e) {
                    logger.error("Callback error for key: " + key, e);
                }
            }
        }
    }
    
    /**
     * Start webhook server to receive secret updates
     */
    private void startWebhookServer() {
        int port = Integer.parseInt(System.getenv().getOrDefault("WEBHOOK_PORT", "8080"));
        
        Spark.port(port);
        
        // Health check endpoint
        Spark.get("/health", (req, res) -> {
            res.status(200);
            return "OK";
        });
        
        // Webhook endpoint for Infisical events
        Spark.post("/infisical/webhook", (req, res) -> {
            try {
                JsonNode payload = objectMapper.readTree(req.body());
                String event = payload.get("event").asText();
                JsonNode data = payload.get("payload");
                
                logger.info("Received webhook event: {}", event);
                
                switch (event) {
                    case "secret.created":
                    case "secret.updated":
                    case "secret.rotated":
                        String key = data.get("secretKey").asText();
                        String value = data.get("secretValue").asText();
                        String oldValue = secrets.get(key);
                        secrets.put(key, value);
                        notifyCallbacks(key, value, oldValue);
                        break;
                        
                    case "secret.deleted":
                        String deletedKey = data.get("secretKey").asText();
                        String deletedValue = secrets.remove(deletedKey);
                        notifyCallbacks(deletedKey, null, deletedValue);
                        break;
                }
                
                res.status(200);
                return "OK";
            } catch (Exception e) {
                logger.error("Error processing webhook", e);
                res.status(500);
                return "Error";
            }
        });
        
        logger.info("Webhook server started on port {}", port);
    }
    
    /**
     * Stop the subscriber
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping Infisical K8s Auth Subscriber");
            Spark.stop();
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Callback interface for secret updates
     */
    @FunctionalInterface
    public interface SecretUpdateCallback {
        void onUpdate(String key, String newValue, String oldValue);
    }
    
    /**
     * Example usage
     */
    public static void main(String[] args) {
        JavaSecretSubscriber subscriber = new JavaSecretSubscriber();
        
        // Subscribe to specific secrets
        subscriber.subscribe("DATABASE_URL", (key, newValue, oldValue) -> {
            System.out.println("Database URL updated: " + newValue);
            // Reconnect to database with new URL
        });
        
        subscriber.subscribe("API_KEY", (key, newValue, oldValue) -> {
            System.out.println("API key rotated");
            // Update API client configuration
        });
        
        // Start the subscriber
        subscriber.start();
        
        // Get secrets
        String dbUrl = subscriber.get("DATABASE_URL");
        String apiKey = subscriber.get("API_KEY");
        
        System.out.println("Database URL: " + dbUrl);
        System.out.println("API Key: " + apiKey);
        
        // Keep running
        Runtime.getRuntime().addShutdownHook(new Thread(subscriber::stop));
        
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}