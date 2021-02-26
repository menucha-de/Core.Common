package havis.util.core.common;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import havis.util.core.app.AppInfo;
import havis.util.core.common.app.CommonAppCurator;
import havis.util.core.common.license.FileLicenseCurator;
import havis.util.core.license.LicenseException;
import havis.util.core.license.LicenseState;

public class CommonAppInfo {

	private final static Logger log = Logger.getLogger(CommonAppCurator.class.getName());

	final static String NAME = "app.name";
	final static String LABEL = "app.label";
	final static String PATH = "app.path";
	final static String VERSION = "app.version";
	final static String CONFIG = "app.config";
	final static String BUNDLE = "app.bundle";
	final static String PRODUCT = "app.product";
	final static String UUID = "app.uuid";
	final static String SECTION = "app.section";
	final static String RESET = "app.reset";
	final static String LOG = "log.name";

	public final static String INFO_PREFIX = "info";
	public final static String INFO_SUFFIX = ".properties";
	public final static FileFilter INFO_FILTER = new FileFilter() {
		@Override
		public boolean accept(File pathname) {
			return pathname.getName().endsWith(INFO_SUFFIX);
		}
	};

	private final static boolean LICENSING_ENABLED;
	private final static String LICENSING = "havis.util.core.app.licensing";

	static {
		String licensing = System.getProperty(LICENSING);
		boolean enabled = Boolean.parseBoolean(licensing);
		if (licensing != null) {
			log.log(Level.FINE, "Changing licensing mode to {0}.", enabled);
			LICENSING_ENABLED = enabled;
		} else {
			LICENSING_ENABLED = false;
		}
	}

	private static String[] split(String list) {
		if (list != null)
			return list.split("\\s*,\\s*");
		return new String[0];
	}

	public static AppInfo get(Properties properties) {
		String name = properties.getProperty(NAME);
		if (name != null) {
			String label = properties.getProperty(LABEL);
			String path = properties.getProperty(PATH);
			String version = properties.getProperty(VERSION);
			String[] config = split(properties.getProperty(CONFIG));
			String product = LICENSING_ENABLED ? properties.getProperty(PRODUCT) : null;
			String uuid = properties.getProperty(UUID);
			String section = properties.getProperty(SECTION);
			String reset = properties.getProperty(RESET);
			String logName = properties.getProperty(LOG);

			if (label != null && version != null) {
				AppInfo info = new AppInfo(name, label, path, version, config, product, uuid, section, reset, logName);
				String[] bundles = split(properties.getProperty(BUNDLE));
				if (bundles != null)
					info.setBundles(new HashSet<String>(Arrays.asList(bundles)));
				if (product != null) {
					try {
						FileLicenseCurator.getLicense(info);
						info.setLicense(LicenseState.LICENSED);
					} catch (LicenseException e) {
						info.setLicense(LicenseState.UNLICENSED);
					}
				}
				return info;
			}
		}
		return null;
	}

	public static AppInfo getInfo() {
		try {
			File path = new File(INFO_PREFIX);
			for (File file : path.listFiles(INFO_FILTER)) {
				Properties properties = new Properties();
				properties.load(new FileInputStream(file));
				if (properties.getProperty("app.name") != null) {
					AppInfo info = get(properties);
					return info;
				}
			}
		} catch (IOException e) {
		}
		return null;
	}
}
