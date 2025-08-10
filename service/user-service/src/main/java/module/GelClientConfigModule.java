package module;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import com.geldata.driver.ClientType;
import com.geldata.driver.ConnectionRetryMode;
import com.geldata.driver.GelClientConfig;
import com.geldata.driver.namingstrategies.NamingStrategy;
import dagger.Module;
import dagger.Provides;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class GelClientConfigModule {
  @Provides
  @Singleton
  public static GelClientConfig getClientConfigProvider(
      @Named("envConfig") final JsonObject config) {
    log.info("Building GelClientConfig");

    final var builder = GelClientConfig.builder();

    if (config.containsKey("GEL_POOL_SIZE")) {
      builder.withPoolSize(config.getInteger("GEL_POOL_SIZE"));
    }

    if (config.containsKey("GEL_RETRY_MODE")) {
      builder.withRetryMode(ConnectionRetryMode.valueOf(config.getString("GEL_RETRY_MODE")));
    }

    if (config.containsKey("GEL_MAX_CONNECTION_RETRIES")) {
      builder.withMaxConnectionRetries(config.getInteger("GEL_MAX_CONNECTION_RETRIES"));
    }

    if (config.containsKey("GEL_MESSAGE_TIMEOUT")) {
      builder.withMessageTimeout(config.getLong("GEL_MESSAGE_TIMEOUT"), TimeUnit.MILLISECONDS);
    }

    if (config.containsKey("GEL_CLIENT_AVAILABILITY")) {
      builder.withClientAvailability(config.getInteger("GEL_CLIENT_AVAILABILITY"));
    }

    if (config.containsKey("GEL_CLIENT_MAX_AGE")) {
      builder.withClientMaxAge(
          Duration.of(config.getInstant("GEL_CLIENT_MAX_AGE").toEpochMilli(), ChronoUnit.MILLIS));
    }

    return builder
        .withExplicitObjectIds(true)
        .withImplicitTypeIds(false)
        .withNamingStrategy(NamingStrategy.snakeCase())
        .useFieldSetters(false)
        .withClientType(ClientType.TCP)
        .build();
  }
}
