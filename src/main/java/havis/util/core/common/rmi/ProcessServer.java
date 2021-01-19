package havis.util.core.common.rmi;

import havis.util.core.rmi.LogRemote;

import java.net.InetAddress;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Process server with the main class
 */
public class ProcessServer {

	private static final String ROOT_LOGGER_NAME = "";

	public static final long MAX_PROCESS_TIMEOUT_MS = 30000;
	public static final long PROCESS_POLL_INTERVAL_MS = 100;

	public static final String NAME_PROPERTY_KEY = ProcessServer.class.getName() + ".Name";
	public static final String CLASS_PROPERTY_KEY = ProcessServer.class.getName() + ".Class";

	public static void main(String[] args) throws Exception {
		final String name = System.getProperty(NAME_PROPERTY_KEY);
		Class<?> serverClass = Class.forName(System.getProperty(CLASS_PROPERTY_KEY));

		CommonProcessHandler.setEnvironment();

		final Registry registry = LocateRegistry.getRegistry(InetAddress.getLoopbackAddress().getHostAddress());

		// initialize logging
		LogManager.getLogManager().reset();
		final LogRemote logRemote = (LogRemote) registry.lookup(LogRemoteServer.NAME);

		Logger rootLogger = Logger.getLogger(ROOT_LOGGER_NAME);

		Map<String, Level> levels = logRemote.getLevels();
		for (Entry<String, Level> level : levels.entrySet()) {
			Logger.getLogger(level.getKey()).setLevel(level.getValue());
		}

		rootLogger.addHandler(new Handler() {
			@Override
			public void publish(LogRecord record) {
				String name = record.getLoggerName() != null ? record.getLoggerName() : ROOT_LOGGER_NAME;
				if (name.startsWith("sun.rmi."))
					return; // skip to avoid stack overflow
				if (Logger.getLogger(name).isLoggable(record.getLevel())) {
					try {
						logRemote.log(record);
					} catch (RemoteException e) {
						// ignore
					}
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() throws SecurityException {
			}
		});

		registry.rebind(name, UnicastRemoteObject.exportObject((Remote) serverClass.newInstance(), 0));

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				// if we receive a kill signal from the main process,
				// wait a maximum of 30s for unbind on the registry before
				// actually terminating
				try {
					int tries = 0;
					while (Arrays.asList(registry.list()).contains(name) && tries++ < (MAX_PROCESS_TIMEOUT_MS / PROCESS_POLL_INTERVAL_MS)) {
						Thread.sleep(PROCESS_POLL_INTERVAL_MS);
					}
				} catch (Exception e) {
					return;
				}
			}
		});
	}
}
