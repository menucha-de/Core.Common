package havis.util.core.common.log;

import havis.util.core.common.Environment;
import havis.util.core.log.LogConfiguration;
import havis.util.core.log.LogException;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

public class LogConfigManager {

	private final static Logger LOG = Logger.getLogger(LogConfigManager.class.getName());

	private final static ObjectMapper MAPPER = new ObjectMapper();

	private static LogConfiguration instance;

	public synchronized static LogConfiguration getInstance() {
		if (instance == null) {
			try {
				instance = deserialize();
			} catch (LogException e) {
				LOG.log(Level.SEVERE, "Failed to deserialize log config.", e);
				instance = setDefaults(new LogConfiguration());
			}
		}
		return instance;
	}

	private static LogConfiguration setDefaults(LogConfiguration configuration) {
		configuration.setPersistent(false);
		configuration.setMaxEntries(Environment.MAX);
		return configuration;
	}

	synchronized static void setInstance(LogConfiguration configuration) throws LogException {
		if (configuration == null || configuration.getMaxEntries() < 10)
			throw new LogException("Invalid log configuration");
		instance = configuration;
	}

	private LogConfigManager() {
	}

	public static synchronized void serialize() throws LogException {
		File tmpFile = null;
		try {
			String filename = Environment.LOG_CONFIG;
			File destination = new File(filename);
			File parent = destination.getParentFile();
			if (parent != null) {
				// create parent directory
				if (!parent.mkdirs() && !parent.exists()) {
					LOG.warning("Failed to create parent directory '" + parent.getAbsolutePath() + "'.");
				}
			}

			// creation of temporary backup file
			tmpFile = File.createTempFile(filename, ".bak", parent);
			LOG.fine("Created temporary file '" + tmpFile.getAbsolutePath() + "'.");

			// writing configuration to temporary backup file
			MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmpFile, instance);

			// Replacing deprecated configuration by new configuration file
			if (tmpFile.renameTo(destination)) {
				LOG.fine("Replaced configuration file.");
			} else {
				throw new Exception("Replacing " + destination.getAbsolutePath() + " with '" + tmpFile.getAbsolutePath() + "' failed.");
			}
		} catch (Exception e) {
			LOG.log(Level.SEVERE, "Failed to persist config", e);
			// delete temporary file
			if (tmpFile != null && tmpFile.exists()) {
				tmpFile.delete();
			}
			throw new LogException("Failed to persist config", e);
		}
	}

	private static synchronized LogConfiguration deserialize() throws LogException {
		String filename = Environment.LOG_CONFIG;
		File configFile = new File(filename);
		if (configFile.exists()) {
			try {
				LogConfiguration config = MAPPER.readValue(configFile, LogConfiguration.class);
				return config;
			} catch (Exception e) {
				throw new LogException("Failed to load config", e);
			}
		} else {
			LOG.fine("Config '" + filename + "' does not exist.");
		}
		return setDefaults(new LogConfiguration());
	}
}
