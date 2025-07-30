import javax.inject.Singleton;

import module.EnvConfigModule;
import module.GelClientConfigModule;
import module.GelConnectionModule;
import dagger.Component;

@Singleton
@Component(modules = {
  EnvConfigModule.class,
  GelConnectionModule.class,
  GelClientConfigModule.class
})
public interface AppComponent {}
