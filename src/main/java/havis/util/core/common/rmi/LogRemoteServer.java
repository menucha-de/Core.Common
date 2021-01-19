package havis.util.core.common.rmi;

import havis.util.core.rmi.LogRemote;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Server for logging remotely
 */
public class LogRemoteServer implements LogRemote {

	public final static String NAME = "LogServer";

	private Registry registry;

	/**
	 * Creates a new log remote server
	 * 
	 * @param registry
	 *            the registry to bind to
	 * @throws RemoteException
	 *             if binding fails
	 */
	public LogRemoteServer(Registry registry) throws RemoteException {
		super();
		if (registry == null) {
			throw new NullPointerException("registry must not be null");
		}
		this.registry = registry;
		this.registry.rebind(NAME, UnicastRemoteObject.exportObject(this, 0));
	}

	@Override
	public void log(LogRecord record) throws RemoteException {
		Logger.getLogger(record.getLoggerName() != null ? record.getLoggerName() : "").log(record);
	}

	@Override
	public Map<String, Level> getLevels() throws RemoteException {
		Map<String, Level> levels = new LinkedHashMap<>();
		LogManager logManager = LogManager.getLogManager();
		Enumeration<String> names = logManager.getLoggerNames();
		while (names.hasMoreElements()) {
			Logger logger = logManager.getLogger(names.nextElement());
			if (logger != null && logger.getLevel() != null) {
				levels.put(logger.getName(), logger.getLevel());
			}
		}
		return levels;
	}

	public void dispose() {
		try {
			this.registry.unbind(NAME);
		} catch (RemoteException | NotBoundException e) {
			// ignore
		}
	}
}
