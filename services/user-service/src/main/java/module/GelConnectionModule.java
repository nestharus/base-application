package module;

import javax.inject.Named;
import javax.inject.Singleton;

import com.geldata.driver.*;
import dagger.Module;
import dagger.Provides;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Module
public class GelConnectionModule {
  @Provides
  @Singleton
  public static GelConnection gelConnectionProvider(@Named("envConfig") final JsonObject config) {
    log.info("Building GelConnection");

    final var builder = GelConnection.builder();

    if (config.containsKey("GEL_HOST")) {
      builder.withHost(config.getString("GEL_HOST"));
    }

    if (config.containsKey("GEL_PORT")) {
      builder.withPort(config.getInteger("GEL_PORT"));
    }

    if (config.containsKey("GEL_BRANCH")) {
      builder.withBranch(config.getString("GEL_BRANCH"));
    }

    // todo secret
    if (config.containsKey("GEL_USER")) {
      builder.withUser(config.getString("GEL_USER"));
    }

    // todo secret
    if (config.containsKey("GEL_PASSWORD")) {
      builder.withPassword(config.getString("GEL_PASSWORD"));
    }

    // todo secret
    if (config.containsKey("GEL_TLS_SECURITY")) {
      builder.withTLSSecurity(TLSSecurityMode.valueOf(config.getString("GEL_TLS_SECURITY")));
    }

    // todo secret
    if (config.containsKey("GEL_TLS_CERTIFICATE_AUTHORITY")) {
      builder.withTLSCertificateAuthority(config.getString("GEL_TLS_CERTIFICATE_AUTHORITY"));
    }

    // todo secret
    if (config.containsKey("GEL_TLS_SERVER_NAME")) {
      builder.withTLSServerName(config.getString("GEL_TLS_SERVER_NAME"));
    }

    try {
      return builder.build();
    } catch (final Exception exception) {
      log.error("Failed to build GelConnection: {}", exception.getMessage());
      throw new RuntimeException("Failed to build GelConnection", exception);
    }
  }
}
