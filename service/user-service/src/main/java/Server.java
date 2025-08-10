import javax.annotation.Nonnull;

import io.vertx.core.Vertx;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class Server {
  public static synchronized void main(@Nonnull final String[] args) throws InterruptedException {
    log.info("Initializing application configuration...");
    final var component = DaggerAppComponent.create();

    // log.info("Database configuration loaded successfully");

    final var vertx = Vertx.vertx();

    log.info("Application initialized and ready");
  }
}
