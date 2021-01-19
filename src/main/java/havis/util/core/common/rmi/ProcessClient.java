package havis.util.core.common.rmi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Observable;
import java.util.Observer;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;

/**
 * Process client which handles the starting, stopping and watching of the
 * process and initiates the communication over RMI
 */
public class ProcessClient extends Observable {

	private static final String MODULE_NAME = "havis.util.core.common";
	private static final String ADDITIONAL_ARGUMENTS_PROPERTY_KEY = "havis.util.core.common.rmi.AdditionalArgumentsFile";
	private static final int MAX_CONSOLE_LINES = 100;

	private Logger log;
	private Registry registry;
	private String serverModuleName;
	private String serverClass;
	private List<String> jvmArguments;
	private List<String> additionalJvmArguments;
	private String classPath;
	private String libraryPath;

	private volatile boolean running;
	private String name;
	private String displayName;
	private Process process;
	private Remote remoteStub;
	private ExecutorService watcherExecutorService;
	private ExecutorService consoleExecutorService;
	private List<String> consoleLines = new CopyOnWriteArrayList<>();

	private RemoteConnectionListener listener;

	// Lock to prevent a race condition between an automatic process restart
	// initiated by the watcher and a manual dispose call initiated by a user of
	// this class
	private Lock processLock = new ReentrantLock();

	private Runnable watcher = new Runnable() {
		@Override
		public void run() {
			while (process != null) {
				int exitCode = 0;
				try {
					exitCode = process.waitFor();
				} catch (InterruptedException e) {
					// stop waiting
					return;
				}

				log.log(Level.SEVERE, "Process \"" + displayName + "\" ended unexpectedly with exit code " + exitCode
						+ ", process will be restarted. \nConsole Output:\n" + getConsoleMessages());

				try {
					restart();
				} catch (InterruptedException e) {
					// stop restarting
					return;
				} catch (Exception e) {
					log.log(Level.SEVERE, "Failed to restart process \"" + displayName + "\", connection is irrecoverably lost.", e);
					return;
				}

				// check if the process is actually running
				try {
					process.exitValue();
					log.log(Level.SEVERE, "Failed to restart process \"" + displayName + "\", connection is irrecoverably lost.");
					return;
				} catch (IllegalThreadStateException e) {
					// expected case, this is the only way to check if the
					// process is running
				}
			}
		}
	};

	private Runnable consoleReader = new Runnable() {
		@Override
		public void run() {
			consoleLines.clear();
			InputStream input;
			if (process != null && (input = process.getInputStream()) != null) {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
					String line = null;
					while ((line = reader.readLine()) != null) {
						consoleLines.add(line);
						if (consoleLines.size() > MAX_CONSOLE_LINES) {
							consoleLines.remove(0);
						}
					}
				} catch (IOException e) {
					// process ended
				}
			}
		}
	};

	private String getConsoleMessages() {
		StringBuilder b = new StringBuilder();
		for (String line : consoleLines) {
			b.append(line);
		}
		return b.toString();
	}

	/**
	 * Creates a new process client
	 * 
	 * @param bundle
	 *            the bundle containing the classes for the process, or null if
	 *            the JVM arguments should be reused
	 * @param registry
	 *            the RMI registry
	 * @param serverModuleName
	 *            the server module name containing the server class
	 * @param serverClass
	 *            the server class implementing the {@link Remote} interface
	 */
	ProcessClient(Bundle bundle, Registry registry, String serverModuleName, String serverClass) {
		this.log = Logger.getLogger(serverClass);
		this.registry = registry;
		this.serverModuleName = serverModuleName;
		this.serverClass = Objects.requireNonNull(serverClass, "serverClass must not be null");
		this.displayName = createDisplayName(serverClass);
		this.additionalJvmArguments = getAdditionalJvmArguments(System.getProperty(ADDITIONAL_ARGUMENTS_PROPERTY_KEY));
		if (bundle != null)
			createPaths(bundle);
		else
			copyJvmArguments();
	}

	private List<String> getAdditionalJvmArguments(String file) {
		List<String> result = new ArrayList<>();
		if (file != null) {
			File f = new File(file);
			if (f.exists()) {
				try {
					result.addAll(Files.readAllLines(f.toPath()));
				} catch (IOException e) {
					log.severe("Failed to read file specified with property \"" + ADDITIONAL_ARGUMENTS_PROPERTY_KEY + "\": " + e.getMessage());
				}
			} else
				log.severe("Failed to find file specified with property \"" + ADDITIONAL_ARGUMENTS_PROPERTY_KEY + "\"");
		}
		return result;
	}

	private String createDisplayName(String serverClass) {
		int index = serverClass.lastIndexOf('.');
		if (index > -1) {
			return serverClass.substring(index + 1);
		}
		return serverClass;
	}

	private void copyJvmArguments() {
		List<String> arguments = new ArrayList<>();
		String mainClass = System.getProperty("sun.java.command");
		try (Scanner scan = new Scanner(new File("/proc/self/cmdline"))) {
			scan.useDelimiter("\0");
			while (scan.hasNext()) {
				String argument = scan.next();
				if (argument != null
						&& (argument.equals("-m") || argument.startsWith("-Xdebug") || argument.startsWith("-agentlib:jdwp") || argument
								.startsWith("-Xrunjdwp")))
					continue;
				if (mainClass != null && mainClass.length() > 0 && mainClass.equals(argument))
					break;
				arguments.add(argument);
			}
		} catch (FileNotFoundException e) {
			throw new IllegalStateException("Failed to retrieve JVM arguments: " + e.getMessage());
		}
		this.jvmArguments = arguments;
	}

	private void createPaths(Bundle bundle) {
		StringBuilder classPath = new StringBuilder();
		StringBuilder libraryPath = new StringBuilder();
		Set<Long> bundleIds = new HashSet<>();
		appendBundleDependencies(FrameworkUtil.getBundle(this.getClass()), bundleIds, classPath, libraryPath);
		appendBundleDependencies(bundle, bundleIds, classPath, libraryPath);
		this.classPath = classPath.toString();
		this.libraryPath = libraryPath.toString();
	}

	private void appendBundleDependencies(Bundle bundle, Set<Long> bundleIds, StringBuilder classPath, StringBuilder libraryPath) {
		if (bundle != null) {
			BundleWiring wiring = bundle.adapt(BundleWiring.class);
			if (wiring != null) {
				List<BundleWire> wires = wiring.getRequiredWires(null);
				if (wires != null) {
					for (BundleWire wire : wires) {
						// skip system bundle
						Bundle wiredBundle = wire.getProvider().getBundle();
						if (wiredBundle.getBundleId() > 0) {
							Long id = Long.valueOf(wiredBundle.getBundleId());
							// add fragments first
							BundleWiring subWiring = wiredBundle.adapt(BundleWiring.class);
							if (subWiring != null) {
								List<BundleWire> subWires = subWiring.getRequiredWires(null);
								if (subWires != null) {
									for (BundleWire subWire : subWires) {
										if ((subWire.getRequirement().getRevision().getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
											Bundle fragment = subWire.getRequirement().getRevision().getBundle();
											Long fragmentId = Long.valueOf(fragment.getBundleId());
											if (!bundleIds.contains(fragmentId)) {
												bundleIds.add(fragmentId);
												appendBundle(fragment, classPath, libraryPath);
											}
										}
									}
								}
							}
							if (!bundleIds.contains(id)) {
								bundleIds.add(id);
								appendBundle(wiredBundle, classPath, libraryPath);
							}
						}
					}
				}
			}
			// append self
			Long id = Long.valueOf(bundle.getBundleId());
			if (!bundleIds.contains(id)) {
				bundleIds.add(id);
				appendBundle(bundle, classPath, libraryPath);
			}
		}
	}

	private void appendBundle(Bundle bundle, StringBuilder classPath, StringBuilder libraryPath) {
		try {
			String jarPath = new URI(bundle.getLocation()).getPath();
			classPath.append(jarPath);
			classPath.append(File.pathSeparatorChar);
		} catch (URISyntaxException e) {
			// ignore
		}
		// unpack native libraries
		String nativeCode = bundle.getHeaders().get("Bundle-NativeCode");
		if (nativeCode != null) {
			String[] libraries = nativeCode.split(",");
			for (String library : libraries) {
				String[] parts = library.split(";");
				if (parts.length >= 1) {
					String file = parts[0];
					File target = new File(bundle.getDataFile("").getAbsoluteFile(), file);
					if (!target.exists()) {
						URL entry = bundle.getEntry("/" + file);
						if (entry != null) {
							try {
								try (InputStream stream = entry.openStream()) {
									Files.copy(stream, target.toPath());
								}
							} catch (IOException e) {
								throw new IllegalStateException("Failed to unpack native library '" + file + "' from jar file '" + bundle.getLocation() + "': "
										+ e.getMessage());
							}
						}
					}
					libraryPath.append(target.getParent());
					libraryPath.append(File.pathSeparatorChar);
				}
			}
		}

	}

	/**
	 * Open the client
	 * 
	 * @param listener
	 *            the listener to retrieve the remote object
	 * @throws RemoteException
	 *             if starting of the process failed
	 */
	public synchronized void open(RemoteConnectionListener listener) throws RemoteException {
		this.listener = Objects.requireNonNull(listener, "listener must not be null");
		try {
			init();
		} catch (Exception e) {
			throw new RemoteException(e.getMessage());
		}
	}

	@Override
	public synchronized void addObserver(Observer o) {
		super.addObserver(o);
		init();
	}

	private void init() {
		if (this.watcherExecutorService == null) {
			try {
				start();
			} catch (InterruptedException e) {
				throw new IllegalStateException("Interrupted while starting process \"" + this.displayName + "\"");
			}
			notifyStarted();
			this.watcherExecutorService = Executors.newSingleThreadExecutor();
			this.watcherExecutorService.execute(watcher);
		}
	}

	private int getJavaVersion() {
		String version = System.getProperty("java.specification.version");
		if (version != null && version.length() > 0) {
			int dotIndex = version.indexOf('.');
			if (dotIndex > -1) {
				version = version.substring(dotIndex + 1);
			}
			try {
				return Integer.parseInt(version);
			} catch (NumberFormatException e) {
				// ignore
			}
		}
		return 8; // failed to detect, so we assume Java 8
	}

	private void start() throws InterruptedException {
		if (!this.running) {
			this.name = UUID.randomUUID().toString();
			long start = System.currentTimeMillis();
			try {
				List<String> commands = new ArrayList<>();
				if (this.jvmArguments != null)
					commands.addAll(this.jvmArguments);
				else {
					File java = new File(new File(System.getProperty("java.home"), "bin"), "java");
					commands.add(java.getAbsolutePath());
					commands.add("-Djava.library.path=" + this.libraryPath);
					commands.add("-cp");
					commands.add(this.classPath);
				}
				commands.add("-D" + ProcessServer.NAME_PROPERTY_KEY + "=" + this.name);
				commands.add("-D" + ProcessServer.CLASS_PROPERTY_KEY + "=" + this.serverClass);
				commands.add("-Djava.security.egd=file:/dev/./urandom");
				commands.addAll(this.additionalJvmArguments);
				if (getJavaVersion() >= 9) {
					if (this.serverModuleName != null) {
						// we won't have access to the server class,
						// first add the module, then add an export
						commands.add("--add-modules");
						commands.add(this.serverModuleName);
						commands.add("--add-exports");
						String serverPackage = this.serverClass.substring(0, this.serverClass.lastIndexOf('.'));
						commands.add(this.serverModuleName + "/" + serverPackage + "=" + MODULE_NAME);
					}
					// set main
					commands.add("-m");
					commands.add(MODULE_NAME + "/" + ProcessServer.class.getName());
				} else {
					// traditional main
					commands.add(ProcessServer.class.getName());
				}
				log.log(Level.FINE, "Process " + this.displayName + " will be started with the following arguments: " + commands.toString());
				this.process = new ProcessBuilder(commands).redirectErrorStream(true).start();
				this.consoleExecutorService = Executors.newSingleThreadExecutor();
				this.consoleExecutorService.execute(consoleReader);
			} catch (IOException e) {
				throw new IllegalStateException("Failed to start process \"" + this.displayName + "\"");
			}

			try {
				int tries = 0;
				while (this.process.isAlive() && !Arrays.asList(this.registry.list()).contains(this.name)
						&& tries++ < (ProcessServer.MAX_PROCESS_TIMEOUT_MS / ProcessServer.PROCESS_POLL_INTERVAL_MS)) {
					Thread.sleep(ProcessServer.PROCESS_POLL_INTERVAL_MS);
				}
				this.remoteStub = this.registry.lookup(this.name);
				this.running = true;
				log.log(Level.FINE, "Process " + this.displayName + " started in " + Long.toString(System.currentTimeMillis() - start) + "ms");
			} catch (RemoteException | NotBoundException e) {
				if (this.process.isAlive())
					throw new IllegalStateException("Failed to retrieve running process instance \"" + this.displayName + "\": " + e.getMessage()
							+ "\nConsole Ouptut:\n" + getConsoleMessages());
				else
					throw new IllegalStateException("Failed to retrieve process instance \"" + this.displayName + "\": " + e.getMessage() + "\nExit code: "
							+ this.process.exitValue() + "\nConsole Output:\n" + getConsoleMessages());
			} finally {
				if (!running) {
					if (this.consoleExecutorService != null) {
						this.consoleExecutorService.shutdownNow();
						this.consoleExecutorService = null;
					}
					// starting failed, kill non working process
					this.process.destroy();
				}
			}
		}

	}

	private void notifyStarted() {
		setChanged();
		notifyObservers(this.remoteStub);
		if (this.listener != null)
			this.listener.connected(this.remoteStub);
	}

	private void stop() {
		if (this.running) {
			if (this.process != null) {
				try {
					// make sure we unbind if the process crashed
					this.registry.unbind(this.name);
				} catch (RemoteException | NotBoundException e) {
					// ignore
				}
				if (this.consoleExecutorService != null) {
					this.consoleExecutorService.shutdownNow();
					this.consoleExecutorService = null;
				}
				this.process.destroyForcibly();
			}
			this.running = false;
		}
	}

	private void restart() throws InterruptedException {
		// if we are here while dispose() is in progress, we will be interrupted
		this.processLock.lockInterruptibly();
		try {
			stop();
			start();
		} finally {
			this.processLock.unlock();
		}
		notifyStarted();
	}

	/**
	 * Close the process
	 */
	public void close() {
		this.processLock.lock();
		try {
			if (this.watcherExecutorService != null) {
				this.watcherExecutorService.shutdownNow();
			}
			stop();
		} finally {
			this.processLock.unlock();
		}
	}
}
