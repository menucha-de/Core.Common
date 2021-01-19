package havis.util.core.common.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;

import havis.util.core.app.AppCurator;
import havis.util.core.app.AppException;
import havis.util.core.app.AppInfo;
import havis.util.core.app.AppState;
import havis.util.core.common.Environment;
import havis.util.core.common.PropertyUtil;
import havis.util.core.common.license.Util;
import havis.util.core.license.License;
import havis.util.core.license.LicenseException;
import havis.util.core.license.LicenseState;

public class CommonAppCurator implements AppCurator {

	private final static Logger log = Logger.getLogger(CommonAppCurator.class.getName());

	private final static int HOURS = 6;

	private final static String URL;
	private final static String DEPOT = "havis.util.core.app.depot";

	static {
		String depot = System.getProperty(DEPOT);
		if (depot != null) {
			log.log(Level.FINE, "Changing default hostname of depot {0}.", depot);
		} else {
			depot = "depot";
		}
		URL = "http://" + depot + "/cgi-bin/depot.sh/";
	}

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	private BundleContext context;
	private boolean started;
	private final Lock lock = new ReentrantLock();

	/**
	 * Map of bundle symbolic name (of the bundle which registered the app(s))
	 * to a list of app.name
	 */
	private final ConcurrentHashMap<String, Set<String>> bundleIds = new ConcurrentHashMap<>();

	/**
	 * Map of app.name and AppInfo
	 */
	private final Map<String, AppInfo> apps = new LinkedHashMap<>();

	/**
	 * Map of app.name and bundle which registered the app
	 */
	private final Map<String, Bundle> bundles = new LinkedHashMap<>();

	public CommonAppCurator(BundleContext context) {
		this.context = context;
		this.context.addFrameworkListener(new FrameworkListener() {
			@Override
			public void frameworkEvent(FrameworkEvent event) {
				if (event != null && event.getType() == FrameworkEvent.STARTED) {
					started = true;
				}
			}
		});
		this.executor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				Set<Entry<String, AppInfo>> entries;
				lock.lock();
				try {
					entries = new LinkedHashSet<>(apps.entrySet());
				} finally {
					lock.unlock();
				}
				for (Entry<String, AppInfo> entry : entries) {
					if (entry.getValue().getLicense() == LicenseState.UNLICENSED) {
						try {
							setState(entry.getKey(), AppState.STOP);
						} catch (AppException e) {
							LogRecord record = new LogRecord(Level.FINE, "Failed to stop app ''{0}''");
							record.setLoggerName(log.getName());
							record.setParameters(new Object[] { entry.getKey() });
							record.setThrown(e);
							log.log(record);
						}
					}
				}
			}
		}, HOURS, HOURS, TimeUnit.HOURS);
	}

	private static AppState valueOf(int state) {
		switch (state) {
		case Bundle.RESOLVED:
			return AppState.STOPPED;
		case Bundle.STARTING:
			return AppState.STARTING;
		case Bundle.ACTIVE:
			return AppState.STARTED;
		case Bundle.STOPPING:
			return AppState.STOPPING;
		}
		return null;
	}

	private AppInfo getAppInfo(String name) {
		lock.lock();
		try {
			return apps.get(name);
		} finally {
			lock.unlock();
		}
	}

	private void putAppInfo(String name, AppInfo info) {
		lock.lock();
		try {
			apps.put(name, info);
		} finally {
			lock.unlock();
		}
	}

	private AppInfo removeAppInfo(String name) {
		lock.lock();
		try {
			return apps.remove(name);
		} finally {
			lock.unlock();
		}
	}

	private void read(InputStream input, StringBuilder builder) throws UnsupportedEncodingException, IOException {
		int size = input.available();
		if (size > 0) {
			char[] b = new char[size];
			try (Reader reader = new InputStreamReader(input, "UTF-8")) {
				while ((size = reader.read(b)) > -1) {
					builder.append(b);
				}
			}
		}
	}

	private String exec(String input, String... command) throws IOException, InterruptedException {
		Process process = Runtime.getRuntime().exec(command);
		if (input != null) {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
				writer.write(input);
				writer.flush();
			}
		}
		int code = process.waitFor();
		final StringBuilder builder = new StringBuilder();
		read(process.getInputStream(), builder);
		if (code != 0) {
			builder.append("Execution failed\n");
			read(process.getErrorStream(), builder);
			String errorMessage = builder.toString().trim().replace("\n", "; ");
			log.log(Level.SEVERE, "Execution failed with code {0}: {1}", new Object[] { code, errorMessage });
			throw new IOException(errorMessage);
		}
		return builder.toString();
	}

	@Override
	public boolean isStarted() {
		return started;
	}

	@Override
	public void install(String name, InputStream input) throws AppException {
		try {
			File file = File.createTempFile("/tmp/havis.", ".app");
			try {
				Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				input.close();
				name = exec(null, "sh", file.getPath(), "name");
				try {
					exec(null, "sh", file.getPath(), "install");
				} catch (IOException e) {
					throw new AppException("Failed to install app, maybe a dependency is missing?", e);
				}
				try {
					URL url = new URL(URL + name);
					HttpURLConnection connection = (HttpURLConnection) url.openConnection();
					connection.setDoOutput(true);
					connection.setRequestMethod("POST");
					connection.setRequestProperty("Content-type", "application/octet-stream");
					connection.connect();
					try {
						try (OutputStream stream = connection.getOutputStream()) {
							Files.copy(file.toPath(), stream);
						}
						int code = connection.getResponseCode();
						if (code != HttpURLConnection.HTTP_OK)
							log.log(Level.WARNING, "Uploading failed with code {0}", code);
					} finally {
						connection.disconnect();
					}
				} catch (IOException e) {
					log.log(Level.FINE, "Failed to upload app to depot", e);
					throw new AppException("Failed to upload app to depot", e);
				}
			} finally {
				file.delete();
			}
		} catch (IOException | InterruptedException e) {
			log.log(Level.FINE, "Failed to install app", e);
			throw new AppException("Failed to install app", e);
		}
	}

	@Override
	public void remove(String name) throws AppException {
		try {
			URL url = new URL(URL + name + ".app" + "?DELETE");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			try {
				exec(null, "sh", Paths.get(Environment.PATH, "info", name + ".app").toString(), "remove");
			} finally {
				connection.connect();
				try {
					int code = connection.getResponseCode();
					if (code != HttpURLConnection.HTTP_OK)
						log.log(Level.WARNING, "Deleting failed with code {0}", code);
				} finally {
					connection.disconnect();
				}
			}
		} catch (IOException | InterruptedException e) {
			log.log(Level.FINE, "Failed remove app", e);
			throw new AppException("Failed remove app", e);
		}
	}

	private void refresh(BundleRevision revision) {
		Bundle system = context.getBundle(0);
		FrameworkWiring wiring = system.adapt(FrameworkWiring.class);
		for (BundleRequirement requirement : revision.getDeclaredRequirements(BundleRevision.HOST_NAMESPACE)) {
			try {
				Filter filter = context.createFilter(requirement.getDirectives().get(Constants.FILTER_DIRECTIVE));
				for (Bundle bundle : context.getBundles()) {
					Dictionary<String, Object> map = new Hashtable<>();
					map.put(BundleRevision.HOST_NAMESPACE, bundle.getSymbolicName());
					map.put(Constants.BUNDLE_VERSION_ATTRIBUTE, bundle.getVersion());
					if (filter.match(map)) {
						log.log(Level.FINE, "Refreshing bundle ''{0}''", bundle.getSymbolicName());
						wiring.refreshBundles(Arrays.asList(bundle));
						break;
					}
				}
			} catch (InvalidSyntaxException e) {
				log.log(Level.SEVERE, "Failed to create filter", e);
			}
		}
	}

	@Override
	public void plug(String name) throws AppException {
		try {
			log.log(Level.FINE, "Installing bundle ''{0}''", name);
			Bundle bundle = context.installBundle("file:" + Paths.get(Environment.PATH, "bundle", name).toString());
			BundleRevision revision = bundle.adapt(BundleRevision.class);
			if ((revision.getTypes() & BundleRevision.TYPE_FRAGMENT) == 0) {
				log.log(Level.FINE, "Starting bundle ''{0}''", name);
				bundle.start();
			} else {
				refresh(revision);
			}
		} catch (BundleException e) {
			if (log.isLoggable(Level.FINE))
				log.log(Level.FINE, "Failed to install bundle '" + name + "'", e);
			throw new AppException("Failed to install bundle '" + name + "'", e);
		}
	}

	@Override
	public void unplug(String name) throws AppException {
		Bundle bundle = context.getBundle("file:" + Paths.get(Environment.PATH, "bundle", name).toString());
		if (bundle != null) {
			try {
				BundleRevision revision = bundle.adapt(BundleRevision.class);
				if ((revision.getTypes() & BundleRevision.TYPE_FRAGMENT) == 0) {
					log.log(Level.FINE, "Stopping bundle ''{0}''", name);
					bundle.stop();
				}
				log.log(Level.FINE, "Uninstalling bundle ''{0}''", name);
				bundle.uninstall();
				if ((revision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
					refresh(revision);
				}
			} catch (BundleException e) {
				if (log.isLoggable(Level.FINE))
					log.log(Level.FINE, "Failed to uninstall bundle '" + name + "'", e);
				throw new AppException("Failed to uninstall bundle '" + name + "'", e);
			}
		} else {
			throw new AppException("Bundle '" + name + "' is not installed");
		}
	}

	@Override
	public Collection<AppInfo> get() {
		lock.lock();
		try {
			for (Entry<String, AppInfo> entry : apps.entrySet())
				entry.getValue().setState(valueOf(bundles.get(entry.getKey()).getState()));
			return new ArrayList<>(apps.values());
		} finally {
			lock.unlock();
		}
	}

	@Override
	public AppState getState(String name) throws AppException {
		Bundle bundle = this.bundles.get(name);
		if (bundle != null)
			return valueOf(bundle.getState());
		throw new AppException("Unknown app '" + name + "'");
	}

	@Override
	public void setState(String name, AppState state) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			switch (state) {
			case START:
				try {
					Set<String> bundles = info.getBundles();
					if (bundles != null) {
						for (Bundle bundle : context.getBundles()) {
							String id = bundle.getSymbolicName();
							if (bundles.contains(id)) {
								log.log(Level.FINE, "Starting bundle ''{0}''", id);
								bundle.start();
							}
						}
					}
				} catch (BundleException e) {
					if (log.isLoggable(Level.FINE))
						log.log(Level.FINE, "Failed to start app '" + name + "'", e);
					throw new AppException("Failed to start app '" + name + "'", e);
				}
				break;
			case STOP:
				try {
					Set<String> bundles = info.getBundles();
					if (bundles != null) {
						for (Bundle bundle : context.getBundles()) {
							String id = bundle.getSymbolicName();
							if (bundles.contains(id)) {
								log.log(Level.FINE, "Stopping bundle ''{0}''", id);
								bundle.stop();
							}
						}
					}
				} catch (BundleException e) {
					if (log.isLoggable(Level.FINE))
						log.log(Level.FINE, "Failed to stop app '" + name + "'", e);
					throw new AppException("Failed to stop app '" + name + "'", e);
				}
				break;
			default:
				throw new AppException("State '" + state + "' is invalid for app '" + name + "'");
			}
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public void getConfig(String name, OutputStream stream) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			try (ZipOutputStream output = new ZipOutputStream(stream)) {
				for (String config : info.getConfig()) {
					output.putNextEntry(new ZipEntry(config));
					Files.copy(Paths.get(Environment.PATH, "conf", config), output);
				}
			} catch (IOException e) {
				throw new AppException("Failed to get config '" + name + "'", e);
			}
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public void setConfig(String name, InputStream stream) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			try (ZipInputStream input = new ZipInputStream(stream)) {
				List<String> list = Arrays.asList(info.getConfig());
				ZipEntry entry;
				while ((entry = input.getNextEntry()) != null)
					if (list.contains(entry.getName()))
						Files.copy(input, Paths.get(Environment.PATH, "conf", entry.getName()), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new AppException("Failed to set config '" + name + "'", e);
			}
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public void resetConfig(String name) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			for (String config : info.getConfig())
				Paths.get(Environment.PATH, "conf", config).toFile().delete();
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public License getLicense(String name) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			try {
				String data = PropertyUtil.getProperty(info.getUuid());
				return Util.get(info.getProduct(), data);
			} catch (AppException | LicenseException e) {
				log.log(Level.FINE, "Failed to get license", e);
				throw new AppException("Failed to get license", e);
			}
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public License getLicenseRequest(String name) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			return new License(info.getProduct(), Util.getSerial());
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public void setLicense(String name, InputStream stream) throws AppException {
		AppInfo info = getAppInfo(name);
		if (info != null) {
			StringBuilder s = new StringBuilder();
			try (Scanner scanner = new Scanner(stream)) {
				scanner.useDelimiter("\\A");
				while (scanner.hasNext())
					s.append(scanner.next());
			}
			String data = s.toString();
			try {
				Util.get(info.getProduct(), data);
				info.setLicense(LicenseState.LICENSED);
				PropertyUtil.setProperty(info.getUuid(), data);
			} catch (AppException | LicenseException e) {
				throw new AppException("Could not license app '" + name + "'", e);
			}
		} else {
			throw new AppException("Unknown app '" + name + "'");
		}
	}

	@Override
	public Collection<String> getBackups(String name) throws AppException {
		try {
			URL url = new URL(URL + name + ".config$*?LIST");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestProperty("Accept", "application/octet-stream");
			connection.connect();
			try {
				int code = connection.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK)
					throw new AppException("Listing configuration backup failed with code '" + code + "'");
				try (InputStream stream = connection.getInputStream()) {
					List<String> list = new ArrayList<>();
					try (Scanner scanner = new Scanner(stream)) {
						scanner.useDelimiter("\n");
						while (scanner.hasNext())
							list.add(scanner.next());
					}
					return list;
				}
			} finally {
				connection.disconnect();
			}
		} catch (IOException e) {
			log.log(Level.FINE, "Failed to delete config backup", e);
			throw new AppException("Failed to delete config backup", e);
		}
	}

	@Override
	public void storeBackup(String name, String label) throws AppException {
		try {
			URL url = new URL(URL + name + ".config$" + label);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setDoOutput(true);
			connection.setRequestMethod("POST");
			connection.setRequestProperty("Content-type", "application/octet-stream");
			connection.connect();
			try {
				try (OutputStream stream = connection.getOutputStream()) {
					getConfig(name, stream);
				}
				int code = connection.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK)
					throw new AppException("Uploading configuration backup failed with code '" + code + "'");
			} finally {
				connection.disconnect();
			}
		} catch (IOException e) {
			e.printStackTrace();
			log.log(Level.FINE, "Failed to store config backup", e);
			throw new AppException("Failed to store config backup", e);
		}
	}

	@Override
	public void restoreBackup(String name, String label) throws AppException {
		try {
			URL url = new URL(URL + name + ".config$" + label);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("Accept", "application/octet-stream");
			connection.connect();
			try {
				int code = connection.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK)
					throw new AppException("Downloading configuration backup failed with code '" + code + "'");
				try (InputStream stream = connection.getInputStream()) {
					setConfig(name, stream);
				}
			} finally {
				connection.disconnect();
			}
		} catch (IOException e) {
			log.log(Level.FINE, "Failed to restore config backup", e);
			throw new AppException("Failed to restore config backup", e);
		}
	}

	@Override
	public void dropBackup(String name, String label) throws AppException {
		try {
			URL url = new URL(URL + name + ".config$" + label + "?DELETE");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.connect();
			try {
				int code = connection.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK)
					throw new AppException("Deleting configuration backup failed with code '" + code + "'");
			} finally {
				connection.disconnect();
			}
		} catch (IOException e) {
			log.log(Level.FINE, "Failed to delete config backup", e);
			throw new AppException("Failed to delete config backup", e);
		}
	}

	private Set<String> getBundles(Bundle appBundle, Set<String> bundles) {
		String id = appBundle.getSymbolicName();
		if ((appBundle.adapt(BundleRevision.class).getTypes() & BundleRevision.TYPE_FRAGMENT) == 0 && !bundles.contains(id))
			bundles.add(id);
		return bundles;
	}

	private Bundle findStateBundle(Bundle appBundle, Set<String> bundles) {
		if (bundles.size() > 0 && (appBundle.adapt(BundleRevision.class).getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
			// if the app bundle is a fragment, we try to find a different
			// bundle from the list of other bundles, which is also not a
			// fragment, to read the state from
			for (Bundle bundle : context.getBundles()) {
				String id = bundle.getSymbolicName();
				if (bundles.contains(id) && (bundle.adapt(BundleRevision.class).getTypes() & BundleRevision.TYPE_FRAGMENT) == 0) {
					return bundle;
				}
			}
		}
		return appBundle;
	}

	public void setEnable(Bundle bundle, AppInfo info, boolean enable) {
		String id = bundle.getSymbolicName();
		if (enable) {
			if (info != null) {
				String name = info.getName();
				this.bundleIds.putIfAbsent(id, new LinkedHashSet<String>());
				this.bundleIds.get(id).add(name);
				Bundle stateBundle = findStateBundle(bundle, info.getBundles());
				info.setState(valueOf(stateBundle.getState()));
				info.setBundles(getBundles(bundle, info.getBundles()));
				putAppInfo(name, info);
				this.bundles.put(name, stateBundle);
				log.log(Level.FINE, "App ''{0}'' added", name);
			} else {
				log.log(Level.WARNING, "Missing required app info");
			}
		} else {
			Set<String> names = this.bundleIds.remove(id);
			if (names != null) {
				for (String name : names) {
					if ((info = removeAppInfo(name)) != null) {
						log.log(Level.FINE, "App ''{0}'' removed", info.getName());
						bundles.remove(info.getName());
					}
				}
			}
		}
	}

	public void close() {
		executor.shutdownNow();
	}
}