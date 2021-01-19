package havis.util.core.common.rmi;

import havis.util.core.rmi.ProcessHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMIServerSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Process handler to register and unregister process clients
 */
public class CommonProcessHandler implements ProcessHandler {

	protected final static String RMI_RANDOM_IDS = "java.rmi.server.randomIDs";
	protected final static String RMI_HOSTNAME = "java.rmi.server.hostname";

	/**
	 * Set environment
	 */
	static void setEnvironment() {
		System.setProperty(RMI_RANDOM_IDS, Boolean.FALSE.toString());
		if (System.getProperty(RMI_HOSTNAME) == null) {
			System.setProperty(RMI_HOSTNAME, InetAddress.getLoopbackAddress().getHostAddress());
		}
	}

	protected static final Logger log = Logger.getLogger(CommonProcessHandler.class.getName());

	protected final static String CLAZZ = "rmi.class";

	protected RegistryClassLoader rmiClassloader;
	protected AtomicBoolean started = new AtomicBoolean(false);
	protected Registry registry;
	protected LogRemoteServer logRemoteServer;

	private Map<String, String[]> registrations = new HashMap<>();

	/**
	 * Create a new CommonProcessHandler using its own class loader
	 */
	public CommonProcessHandler() {
		this(CommonProcessHandler.class.getClassLoader());
	}

	/**
	 * Creates a new CommonProcessHandler
	 * 
	 * @param loader
	 *            the class loader to use
	 */
	public CommonProcessHandler(ClassLoader loader) {
		setEnvironment();
		this.rmiClassloader = new RegistryClassLoader(loader);
	}

	protected void startRegistry() {
		if (started.compareAndSet(false, true)) {
			try {
				ClassLoader current = Thread.currentThread().getContextClassLoader();
				try {
					Thread.currentThread().setContextClassLoader(this.rmiClassloader);
					this.registry = LocateRegistry.createRegistry(Registry.REGISTRY_PORT, RMISocketFactory.getDefaultSocketFactory(),
							new RMIServerSocketFactory() {
								@Override
								public ServerSocket createServerSocket(int port) throws IOException {
									return new ServerSocket(port, 0, InetAddress.getLoopbackAddress());
								}
							});
				} finally {
					Thread.currentThread().setContextClassLoader(current);
				}
				this.logRemoteServer = new LogRemoteServer(this.registry);
			} catch (RemoteException e) {
				throw new IllegalStateException("Failed to initialize RMI registry", e);
			}
		}
	}

	private void stopRegistry() {
		if (started.compareAndSet(true, false)) {
			if (this.logRemoteServer != null) {
				this.logRemoteServer.dispose();
				this.logRemoteServer = null;
			}
			if (this.registry != null) {
				try {
					UnicastRemoteObject.unexportObject(this.registry, false);
				} catch (NoSuchObjectException e) {
					// ignore
				}
			}
			registry = null;
		}
	}

	/**
	 * Adds a registration for the specified classes
	 * 
	 * @param id
	 *            the ID of the registration
	 * @param clazzes
	 *            the classes to register
	 */
	public void add(String id, String[] clazzes) {
		add(id, this.getClass().getClassLoader(), clazzes);
	}

	/**
	 * Adds a registration for the specified classes
	 * 
	 * @param id
	 *            the ID of the registration
	 * @param classLoder
	 *            the classLoader to load the classes from
	 * @param clazzes
	 *            the classes to register
	 */
	public void add(String id, ClassLoader classLoder, String[] clazzes) {
		this.rmiClassloader.add(id, classLoder);
		register(id, clazzes);
	}

	protected void register(String id, String[] clazzes) {
		for (String c : Objects.requireNonNull(clazzes, "clazzes must not be null"))
			Objects.requireNonNull(c, "class must not be null");
		registrations.put(id, clazzes);
	}

	/**
	 * Remove a registration for the specified ID
	 * 
	 * @param id
	 *            the ID of the registration
	 */
	public void remove(String id) {
		unregister(id);
		this.rmiClassloader.remove(id);

		if (this.rmiClassloader.isEmpty()) {
			// actually we should reset on all remove calls, but it's unclear
			// whether it is a good idea to remove the class loader of classes
			// which still might have live instances
			this.rmiClassloader = new RegistryClassLoader(this.rmiClassloader);
			// we have to stop the registry, to actually use the changed class
			// loader, otherwise it will keep using stub classes from the old
			// class loader
			stopRegistry();
		}
	}

	protected void unregister(String id) {
		registrations.remove(id);
	}

	/**
	 * Retrieves a new process client which can be used to start a process (call
	 * {@link ProcessClient#close()} to stop the process).
	 * 
	 * @param clazz
	 *            the class to run (must be registered)
	 * @return the process client
	 */
	public ProcessClient getProcessClient(String clazz) {
		return getProcessClient(null, clazz);
	}

	/**
	 * Retrieves a new process client which can be used to start a process (call
	 * {@link ProcessClient#close()} to stop the process).
	 * 
	 * @param moduleName
	 *            the name of the module containing clazz
	 * @param clazz
	 *            the class to run (must be registered)
	 * @return the process client
	 */
	public ProcessClient getProcessClient(String moduleName, String clazz) {
		for (Entry<String, String[]> entry : this.registrations.entrySet()) {
			for (String c : entry.getValue()) {
				if (c.equals(clazz)) {
					startRegistry();
					return new ProcessClient(null, this.registry, moduleName, clazz);
				}
			}
		}
		throw new IllegalArgumentException(clazz + " not registered");
	}

	/**
	 * Close the process handler
	 */
	public void close() {
		stopRegistry();
	}
}
