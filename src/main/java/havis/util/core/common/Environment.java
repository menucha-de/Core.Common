package havis.util.core.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Environment {

	private static final String driver = "havis.util.core.common.driver";
	private static final String url = "havis.util.core.common.url";
	private static final String urlPersistent = "havis.util.core.common.urlPersistent";
	private static final String path = "havis.util.core.common.path";
	private static final String max = "havis.util.core.common.max";
	private static final String wsUri = "havis.util.core.common.wsUri";
	private static final String rpcTool = "havis.util.core.common.rpcTool";
	private static final String logConfig = "havis.util.core.common.logConfig";

	private static final Logger log = Logger.getLogger(Environment.class.getName());

	private static final Properties properties = new Properties();

	static {
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream("Environment.properties")) {
			if (stream != null)
				properties.load(stream);
		} catch (IOException e) {
			log.log(Level.SEVERE, "Failed to load environment properties", e);
		}

		String url = System.getProperty(Environment.url);
		if (url != null)
			properties.setProperty(Environment.url, url);
	}

	public static final String DRIVER = properties.getProperty(driver, "org.h2.Driver");
	public final static String PATH = properties.getProperty(path, "/opt/havis-apps");
	public final static String URL = properties.getProperty(url, "jdbc:h2:mem:log");
	public final static String URL_PERSISTENT = properties.getProperty(urlPersistent, "jdbc:h2:" + PATH + "/log");
	public final static Integer MAX = Integer.valueOf(properties.getProperty(max, "500"));
	public final static String WS_URI = properties.getProperty(wsUri, "https://mica/ws/");
	public final static String RPC_TOOL = properties.getProperty(rpcTool, "mica-rpc");
	public final static String LOG_CONFIG = properties.getProperty(logConfig, "conf/logging.json");
}