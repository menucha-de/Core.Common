package havis.util.core.common.log;

import havis.util.core.log.LogLevel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Util {

	private final static Logger log = Logger.getLogger(Util.class.getName());

	private final static ObjectMapper mapper = new ObjectMapper().enableDefaultTyping(ObjectMapper.DefaultTyping.NON_FINAL);

	static LogLevel valueOf(Level level) {
		if (level != null) {
			return valueOf(level.intValue());
		}
		return LogLevel.ALL;
	}

	static LogLevel valueOf(int level) {
		if (level >= Level.OFF.intValue())
			return LogLevel.OFF;
		if (level >= Level.SEVERE.intValue())
			return LogLevel.ERROR;
		if (level >= Level.WARNING.intValue())
			return LogLevel.WARNING;
		if (level >= Level.INFO.intValue())
			return LogLevel.INFO;
		if (level >= Level.FINE.intValue())
			return LogLevel.DEBUG;
		if (level >= Level.FINER.intValue())
			return LogLevel.TRACE;
		return LogLevel.ALL;
	}

	static Level valueOf(LogLevel level) {
		if (level != null) {
			switch (level) {
			case OFF:
				return Level.OFF;
			case ERROR:
				return Level.SEVERE;
			case WARNING:
				return Level.WARNING;
			case INFO:
				return Level.INFO;
			case DEBUG:
				return Level.FINE;
			case TRACE:
				return Level.FINER;
			case ALL:
				return Level.ALL;
			}
		}
		return Level.SEVERE;
	}

	public static LogLevel valueOf(String value) {
		if (value != null)
			try {
				return LogLevel.valueOf(LogLevel.class, value);
			} catch (IllegalArgumentException e) {
				log.log(Level.WARNING, "Unknown log level", e);
			}
		return LogLevel.ERROR;
	}

	static int toInt(Level level) {
		return level == null ? 0 : level.intValue();
	}

	static int toInt(LogLevel level) {
		return valueOf(level).intValue();
	}

	static String format(String pattern, Locale locale, Object obj) {
		if (pattern != null && obj != null) {
			if (pattern.indexOf("{0") >= 0 || pattern.indexOf("{1") >= 0 || pattern.indexOf("{2") >= 0 || pattern.indexOf("{3") >= 0) {
				try {
					return new MessageFormat(pattern, locale).format(obj);
				} catch (IllegalArgumentException e) {
					// ignore
				}
			}
		}
		return pattern;
	}

	/**
	 * Returns the stack trace of an exception as string
	 * 
	 * @param e
	 *            The exception
	 * @return The stack trace as string
	 */
	static String toString(Throwable e) {
		if (e != null) {
			StringWriter writer = new StringWriter();
			e.printStackTrace(new PrintWriter(writer));
			return writer.toString();
		}
		return null;
	}

	static boolean isSerializable(Object[] objs) {
		if (objs == null)
			return false;

		for (Object obj : objs) {
			if (obj != null) {
				Class<?> type = obj.getClass();
				if (!mapper.canSerialize(type))
					return false;
				if (!mapper.canDeserialize(mapper.constructType(type)))
					return false;
			}
		}
		return true;
	}

	/**
	 * Converts an object to a JSON string.
	 * 
	 * @param object
	 *            The object to convert
	 * @return The JSON string representation of the object
	 */
	static String toJson(Object obj) {
		if (obj != null) {
			try {
				return mapper.writeValueAsString(obj);
			} catch (JsonProcessingException e) {
				log.log(Level.FINE, "Failed to write value to JSON with default typing", e);
			}
		}
		return null;
	}

	/**
	 * Converts a JSON string to an object. If converting with default typing
	 * fails the converting without default typing is used.
	 * 
	 * @param json
	 *            The JSON string to convert
	 * @return The object
	 */
	@SuppressWarnings("unchecked")
	static Object fromJson(String json) {
		if (json == null || json.isEmpty()) {
			return null;
		}

		try {
			return mapper.readValue(json, Object.class);
		} catch (Exception e) {
			log.log(Level.FINE, "Failed to read from JSON string with default typing", e);
		}

		ObjectMapper mapper = new ObjectMapper();
		try {
			List<Object> list = (List<Object>) mapper.readValue(json, Object.class);
			if (list.size() > 1)
				return ((List<Object>) list.get(1)).toArray();
		} catch (Exception e) {
			log.log(Level.FINE, "Failed to read from JSON string without default typing", e);
		}
		return null;
	}
}