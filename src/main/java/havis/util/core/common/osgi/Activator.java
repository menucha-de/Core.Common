package havis.util.core.common.osgi;

import havis.util.core.Core;
import havis.util.core.common.CommonCore;
import havis.util.core.common.SysInfo;

import java.io.IOException;
import java.net.URL;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.util.tracker.BundleTracker;

/**
 * The OSGi activator class of the log manager bundle.
 * 
 */
public class Activator implements BundleActivator {

	private static final Logger log = Logger.getLogger(Activator.class.getName());

	final static String PATHS = "Havis-Bundle";

	private CommonCore core;
	private ServiceRegistration<?> registration;
	private BundleTracker<Bundle> tracker;

	static {
		SysInfo.init();
	}

	/**
	 * Starts the core service. Also, the bundle listener to track state changes
	 * of required bundles is registered. This method is called by the OSGi
	 * framework and should not be invoked directly.
	 * 
	 * @param context
	 *            the OSGi bundle context
	 */
	@Override
	public void start(BundleContext context) throws Exception {
		core = new CommonCore(context, Activator.class.getClassLoader());

		// enable self
		setEnable(context.getBundle(), true);

		registration = context.registerService(Core.class, core, null);

		tracker = new BundleTracker<Bundle>(context, Bundle.RESOLVED | Bundle.INSTALLED | Bundle.UNINSTALLED, null) {
			@Override
			public void modifiedBundle(Bundle bundle, BundleEvent event, Bundle object) {
				switch (bundle.getState()) {
				case Bundle.RESOLVED:
					log.log(Level.FINE, "Bundle ''{0}'' resolved", bundle.getSymbolicName());
					setEnable(bundle, true);
					break;
				case Bundle.UNINSTALLED:
					log.log(Level.FINE, "Bundle ''{0}'' uninstalled", bundle.getSymbolicName());
					setEnable(bundle, false);
					break;
				}
			}
		};
		tracker.open();
	}

	/**
	 * Stops the log service bundle and unregisters the log service, also
	 * disabling the log service relevant console commands. Also, the bundle
	 * listener is unregistered. This method is called by the OSGi framework and
	 * should not be invoked directly.
	 * 
	 * @param context
	 *            the OSGi bundle context
	 */
	@Override
	public void stop(BundleContext context) throws Exception {
		if (tracker != null) {
			tracker.close();
			tracker = null;
		}
		if (registration != null) {
			registration.unregister();
			registration = null;
		}

		if (core != null) {
			// disable self
			setEnable(context.getBundle(), false);
			core.close();
		}
	}

	private void setEnable(Bundle bundle, boolean enable) {
		String paths = bundle.getHeaders().get(PATHS);
		if (paths != null) {
			if (enable) {
				for (String path : paths.split("\\s*,\\s*")) {
					Properties properties = new Properties();
					try {
						URL url = bundle.getEntry(path);
						if (url != null) {
							properties.load(url.openStream());
							core.setEnable(bundle, properties, enable);
						} else {
							log.log(Level.WARNING, "Bundle properties file ''{0}'' does not exists", path);
						}
					} catch (IOException e) {
						log.log(Level.WARNING, "Failed to read property file from bundle", e);
					}
				}
			} else {
				core.setEnable(bundle, null, enable);
			}
		}
	}
}
