package havis.util.core.common.rmi;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.PrototypeServiceFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Process handler to register and unregister process clients via OSGi
 */
public class BundleProcessHandler extends CommonProcessHandler {

	private BundleContext context;
	private Map<String, List<ServiceRegistration<?>>> registrations = new ConcurrentHashMap<>();

	public BundleProcessHandler(BundleContext context, ClassLoader loader) {
		setEnvironment();
		this.rmiClassloader = new RegistryClassLoader(loader);
		this.context = context;
	}

	public void setEnable(Bundle bundle, Properties properties, boolean enable) {
		if (enable) {
			String clazzes = properties.getProperty(CLAZZ);

			if (clazzes != null) {
				add(bundle, clazzes.split("\\s*,\\s*"));
			}
		} else {
			remove(bundle);
		}
	}

	private void add(Bundle bundle, String[] clazzes) {
		add(bundle.getSymbolicName(), bundle.adapt(BundleWiring.class).getClassLoader(), clazzes);
	}

	private void remove(Bundle bundle) {
		remove(bundle.getSymbolicName());
	}

	@Override
	protected void register(String id, String[] clazzes) {
		List<ServiceRegistration<?>> registrations = new ArrayList<>();
		for (final String clazz : clazzes) {
			Dictionary<String, String> properties = new Hashtable<String, String>();
			properties.put("class", clazz);
			registrations.add(this.context.registerService(Observable.class.getName(), new PrototypeServiceFactory<Observable>() {
				@Override
				public Observable getService(Bundle b, ServiceRegistration<Observable> registration) {
					startRegistry();
					return new ProcessClient(b, registry, null, clazz);
				}

				@Override
				public void ungetService(Bundle b, ServiceRegistration<Observable> registration, Observable service) {
					((ProcessClient) service).close();
				}
			}, properties));
			log.log(Level.FINE, "Added process {0} for bundle {1}", new Object[] { clazz, id });
		}
		this.registrations.put(id, registrations);
	}

	@Override
	protected void unregister(String id) {
		List<ServiceRegistration<?>> registrations = this.registrations.get(id);
		if (registrations != null) {
			for (ServiceRegistration<?> registration : registrations) {
				registration.unregister();
			}
		}
	}
}
