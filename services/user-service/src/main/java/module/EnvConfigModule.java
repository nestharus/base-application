package module;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class EnvConfigModule {
  @Provides
  @Singleton
  @Named("envConfig")
  public JsonObject envConfigProvider() {
    final var vertx = Vertx.vertx();

    try {
      final var envStore = new ConfigStoreOptions().setType("env").setFormat("properties");
      final var options = new ConfigRetrieverOptions().addStore(envStore);
      final var retriever = ConfigRetriever.create(vertx, options);
      final var latch = new CountDownLatch(1);
      final var configRef = new AtomicReference<JsonObject>();
      final var errorRef = new AtomicReference<Throwable>();

      retriever
          .getConfig()
          .onSuccess(configRef::set)
          .onFailure(errorRef::set)
          .onComplete(_ -> latch.countDown());

      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          log.error("Configuration loading timed out after 10 seconds");
          throw new RuntimeException("Configuration loading timed out");
        }
      } catch (final InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for configuration", exception);
      }

      if (errorRef.get() != null) {
        throw new RuntimeException("Failed to load configuration", errorRef.get());
      }

      final var config = configRef.get();
      log.info("Configuration loaded successfully from environment variables");
      return config;
    } finally {
      vertx.close();
      log.debug("Temporary Vertx instance closed");
    }
  }
}
