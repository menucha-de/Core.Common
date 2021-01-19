package havis.util.core.common.log;

import havis.util.core.common.Environment;
import havis.util.core.log.LogConfiguration;
import havis.util.core.log.LogEntry;
import havis.util.core.log.LogUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class SQLHandler extends Handler implements Runnable {

	private final static Logger log = Logger.getLogger(SQLHandler.class.getName());

	final static String CREATE = "CREATE TABLE IF NOT EXISTS record (id SERIAL NOT NULL PRIMARY KEY, millis DATETIME, logger TEXT, class TEXT, method TEXT, level INT, message TEXT, parameters TEXT, thrown TEXT, thread INT)";
	final static String INSERT = "INSERT INTO record (millis, logger, class, method, level, message, parameters, thrown, thread) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
	final static String SIZE = "SELECT COUNT(id) AS size FROM record WHERE logger like ? and level >= ?";
	final static String SELECT = "SELECT id, millis, logger, class, method, level, message, parameters, thrown, thread FROM record WHERE logger LIKE ? AND level >= ? ORDER BY id ASC LIMIT ? OFFSET ?";
	final static String CLEAR = "DELETE FROM record WHERE logger like ?";
	final static String TRUNC = "DELETE FROM record WHERE id < ?";

	private Connection connection;
	private Thread thread;
	private BlockingQueue<LogRecord> queue = new LinkedBlockingQueue<>();
	private Boolean enable;
	private int maxEntries;
	private String url;

	/**
	 * Creates and starts the worker thread.
	 */
	public void init(ClassLoader loader) {
		LogConfiguration config = LogConfigManager.getInstance();
		maxEntries = config.getMaxEntries();
		url = config.isPersistent() ? Environment.URL_PERSISTENT : Environment.URL;

		thread = new Thread(this);
		thread.setContextClassLoader(loader);
		thread.start();
	}

	@Override
	/**
	 * Adds the log record to the queue
	 */
	public void publish(LogRecord record) {
		if (enable != Boolean.FALSE)
			queue.add(record);
	}

	@Override
	/**
	 * Does nothing
	 */
	public void flush() {
	}

	/**
	 * Disables the log handler. Waits for termination of the worker thread.
	 */
	@Override
	public void close() {
		if (enable == Boolean.TRUE) {
			enable = false;

			if (thread != null) {
				try {
					thread.join();
				} catch (InterruptedException e) {
				}
				thread = null;
			}
		}
	}

	/**
	 * Returns the enable state
	 */
	boolean isEnable() {
		return enable == Boolean.TRUE;
	}

	/**
	 * Establishes a database connection and creates the database schema.
	 * Enables the log handler. Waits for log entries on the queue and inserts
	 * each entry into the database.
	 */
	@Override
	public void run() {
		try {
			try (Statement statement = getConnection().createStatement()) {
				statement.execute(CREATE);
			}
			enable = true;
		} catch (SQLException e) {
			enable = false;
			log.log(Level.SEVERE, "Failed to initializes logging", e);
		}

		while (enable) {
			try {
				LogRecord record = queue.poll(100, TimeUnit.MILLISECONDS);
				if (record != null) {
					long id = 0;
					try (PreparedStatement statement = getConnection().prepareStatement(INSERT)) {

						statement.setTimestamp(1, new Timestamp(record.getMillis()));
						statement.setString(2, record.getLoggerName());
						statement.setString(3, record.getSourceClassName());
						statement.setString(4, record.getSourceMethodName());
						statement.setInt(5, Util.toInt(record.getLevel()));

						if (Util.isSerializable(record.getParameters())) {
							statement.setString(6, record.getMessage());
							statement.setString(7, Util.toJson(record.getParameters()));
						} else {
							statement.setString(6, Util.format(record.getMessage(), Locale.getDefault(), record.getParameters()));
							statement.setString(7, null);
						}

						statement.setString(8, Util.toString(record.getThrown()));
						statement.setLong(9, record.getThreadID());
						statement.execute();
						try (ResultSet rs = statement.getGeneratedKeys()) {
							if (rs.next())
								id = rs.getLong(1);
						}
					}
					if (id > maxEntries)
						try (PreparedStatement statement = getConnection().prepareStatement(TRUNC)) {
							statement.setLong(1, id - maxEntries + 1);
							statement.execute();
						}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		try {
			if (connection != null) {
				connection.close();
				connection = null;
			}
		} catch (SQLException e) {
			log.log(Level.SEVERE, "Failed to close database connection", e);
		}
	}

	/**
	 * Returns an existing or a new connection, if connection does not exists or
	 * is closed. Sets the new created connection to auto commit.
	 * 
	 * @return The open connection
	 * @throws SQLException
	 *             If connection creation fails
	 */
	Connection getConnection() throws SQLException {
		if (connection == null || connection.isClosed()) {
			connection = DriverManager.getConnection(url);
			connection.setAutoCommit(true);
		}
		return connection;
	}

	/**
	 * Returns the number of selected rows.
	 * 
	 * @param name
	 * @param level
	 * @return The number of selected log rows
	 * @throws SQLException
	 */
	int size(String name, int level) throws SQLException {
		try (PreparedStatement statement = getConnection().prepareStatement(SIZE)) {
			statement.setString(1, name + "%");
			statement.setInt(2, level);
			try (ResultSet resultSet = statement.executeQuery()) {
				if (resultSet.next())
					return resultSet.getInt("SIZE");
			}
		}
		return 0;
	}

	public static void main(String[] args) throws Exception {
		if (args == null || args.length != 1) {
			System.err.println("Usage: SQLHandler <log file path>");
			return;
		}
		String file = args[0];
		if (file.endsWith(".mv.db")) {
			file = file.substring(0, file.length() - 6);
		}

		SQLHandler handler = new SQLHandler();
		handler.url = "jdbc:h2:" + file;
		handler.maxEntries = Integer.MAX_VALUE;
		
		for (LogEntry entry : handler.getAll()) {
			System.out.println(LogUtil.format(entry));
		}
	}

	private List<LogEntry> getAll() throws SQLException {
		List<LogEntry> entries = new ArrayList<>();

		try (PreparedStatement statement = getConnection().prepareStatement("SELECT id, millis, logger, class, method, level, message, parameters, thrown, thread FROM record ORDER BY id ASC")) {
			statement.execute();
			try (ResultSet resultSet = statement.getResultSet()) {
				while (resultSet.next()) {
					String message = resultSet.getString("message");
					String parameters = resultSet.getString("parameters");
					if (message != null && parameters != null)
						message = Util.format(message, Locale.US, Util.fromJson(parameters));
					entries.add(new LogEntry(resultSet.getLong("id"), resultSet.getTimestamp("millis").getTime(), resultSet.getString("logger"), resultSet.getString("class"),
							resultSet.getString("method"), Util.valueOf(resultSet.getInt("level")), message, parameters, resultSet.getString("thrown"), resultSet.getInt("thread")));
				}
			}
		}

		return entries;
	}

	/**
	 * Selects a list of entries from the database by the given parameters.
	 * 
	 * @param logger
	 * @param level
	 * @param offset
	 * @param limit
	 * @return a list of log entries
	 * @throws SQLException
	 */
	public List<LogEntry> get(String logger, int level, int limit, int offset, Locale locale) throws SQLException {
		List<LogEntry> entries = new ArrayList<>();

		try (PreparedStatement statement = getConnection().prepareStatement(SELECT)) {

			statement.setString(1, logger + "%");
			statement.setInt(2, level);
			statement.setInt(3, limit);
			statement.setInt(4, offset);

			statement.execute();
			try (ResultSet resultSet = statement.getResultSet()) {
				while (resultSet.next()) {
					String message = resultSet.getString("message");
					String parameters = resultSet.getString("parameters");
					if (message != null && parameters != null)
						message = Util.format(message, locale, Util.fromJson(parameters));
					entries.add(new LogEntry(resultSet.getLong("id"), resultSet.getTimestamp("millis").getTime(), resultSet.getString("logger"), resultSet
							.getString("class"), resultSet.getString("method"), Util.valueOf(resultSet.getInt("level")), message, parameters, resultSet
							.getString("thrown"), resultSet.getInt("thread")));
				}
			}
		}

		return entries;
	}

	public int clear(String logger) throws SQLException {
		try (PreparedStatement statement = getConnection().prepareStatement(CLEAR)) {
			statement.setString(1, logger + "%");
			return statement.executeUpdate();
		}
	}
}
