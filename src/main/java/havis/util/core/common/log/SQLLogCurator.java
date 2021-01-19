package havis.util.core.common.log;

import havis.util.core.common.CommonAppInfo;
import havis.util.core.log.LogConfiguration;
import havis.util.core.log.LogCurator;
import havis.util.core.log.LogEntry;
import havis.util.core.log.LogException;
import havis.util.core.log.LogLevel;
import havis.util.core.log.LogTarget;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SQLLogCurator implements LogCurator {

	private static final Logger log = Logger.getLogger(SQLLogCurator.class.getName());

	final static String NAME = "log.name";
	final static String LABEL = "log.label";
	final static String LEVEL = "log.level";

	private static SQLHandler handler = new SQLHandler();

	private Map<Long, LogTarget> targets = new LinkedHashMap<>();

	private static List<Logger> loggers = new ArrayList<>();

	static {
		Logger log = Logger.getLogger("");
		log.addHandler(handler);
		log.setLevel(Level.SEVERE);
	}

	public static LogCurator create() {
		SQLLogCurator logCurator = new SQLLogCurator(SQLLogCurator.class.getClassLoader());
		long id = 0;
		File path = new File(CommonAppInfo.INFO_PREFIX);
		for (File file : path.listFiles(CommonAppInfo.INFO_FILTER)) {
			Properties properties = new Properties();
			try {
				properties.load(new FileInputStream(file));
			} catch (IOException e) {
			}
			logCurator.setEnable(id++, properties, true);
		}
		return logCurator;
	}

	public SQLLogCurator(ClassLoader loader) {
		handler.init(loader);
	}

	@Override
	public List<LogLevel> getLevels() {
		return Arrays.asList(LogLevel.values());
	}

	@Override
	public Collection<LogTarget> getTargets() {
		return targets.values();
	}

	@Override
	public int clear(String target) throws LogException {
		try {
			return handler.clear(target);
		} catch (SQLException e) {
			throw new LogException("Failed to clear log target", e);
		}
	}

	@Override
	public LogLevel getLevel(String target) {
		return Util.valueOf(Logger.getLogger(target).getLevel());
	}

	@Override
	public void setLevel(String target, LogLevel level) {
		Logger.getLogger(target).setLevel(Util.valueOf(level));
	}

	@Override
	public int size(String target, LogLevel level) throws LogException {
		try {
			return handler.size(target, Util.valueOf(level).intValue());
		} catch (SQLException e) {
			throw new LogException("Failed to get log entry count", e);
		}
	}

	@Override
	public List<LogEntry> get(String target, LogLevel level, int limit, int offset, Locale locale) throws LogException {
		List<LogEntry> list = new ArrayList<>();
		try {
			for (LogEntry record : handler.get(target, Util.toInt(level), limit, offset, locale)) {
				list.add(record);
			}
		} catch (SQLException e) {
			throw new LogException("Failed to get log entries", e);
		}
		return list;
	}

	private boolean add(long id, LogTarget target, LogLevel level) {
		if (targets.containsKey(id))
			return false;
		targets.put(id, target);
		Logger logger = Logger.getLogger(target.getName());
		logger.setLevel(Util.valueOf(level));
		loggers.add(logger);
		return true;
	}

	private void remove(Long id) {
		LogTarget target = targets.remove(id);
		if (target != null) {
			Logger logger = Logger.getLogger(target.getName());
			if (logger != null)
				loggers.remove(logger);
		}
	}

	public void close() {
		if (handler != null) {
			handler.close();
		}
	}

	public void setEnable(long id, Properties properties, boolean enable) {
		if (enable) {
			String name = properties.getProperty(NAME);
			String label = properties.getProperty(LABEL);
			String level = properties.getProperty(LEVEL);

			if (name != null && label != null) {
				if (add(id, new LogTarget(name, label), Util.valueOf(level)))
					log.log(Level.FINE, "Added log target {0} alias {1}", new Object[] { name, label });
			}
		} else {
			remove(id);
		}
	}

	@Override
	public LogConfiguration getConfiguration() {
		return LogConfigManager.getInstance();
	}

	@Override
	public void setConfiguration(LogConfiguration configuration) throws LogException {
		LogConfigManager.setInstance(configuration);
		LogConfigManager.serialize();
	}
}