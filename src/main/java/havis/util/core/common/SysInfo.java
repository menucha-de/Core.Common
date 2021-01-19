package havis.util.core.common;

import java.util.logging.Level;
import java.util.logging.Logger;

public class SysInfo {

	private final static Logger log = Logger.getLogger(SysInfo.class.getName());

	static {
		try {
			System.setProperty("mica.device.name", getEnv("MICA_NAME", "mica-0000"));
			System.setProperty("mica.device.serial_no", getEnv("MICA_SERIAL", ""));
			System.setProperty("mica.device.hw_revision", getEnv("MICA_REVISION", ""));
			System.setProperty("mica.firmware.base_version", getEnv("MICA_VERSION", ""));
		} catch (Exception e) {
			log.log(Level.FINE, "Failed to read mica sysinfo", e);
		}
	}

	private static String getEnv(String variable, String defaultValue) {
		String value = System.getenv(variable);
		if (value != null) {
			return value;
		}
		return defaultValue;
	}

	public static void init() {
	}
}