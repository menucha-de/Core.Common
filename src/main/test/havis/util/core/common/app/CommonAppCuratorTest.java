package havis.util.core.common.app;

import havis.util.core.app.AppException;
import havis.util.core.app.AppInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collection;

import mockit.Deencapsulation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class CommonAppCuratorTest {

	static String URL = "http://localhost:PORT/cgi-bin/depot.sh/";

	private static HttpServer server;
	private static HttpContext context;

	@Before
	public void before() throws IOException, InterruptedException {
		System.setProperty("mica.device.serial_no", "46925873353");
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		context = server.createContext("/");
		server.start();
		Deencapsulation.setField(CommonAppCurator.class, "URL", URL.replace("PORT", "" + server.getAddress().getPort()));
	}

	@After
	public void after() {
		server.stop(0);
	}

	@Test
	public void getBackupsTest(@Mocked BundleContext context) throws AppException {
		CommonAppCuratorTest.context.setHandler(new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				if ("/cgi-bin/depot.sh/test.config$*?LIST".equals(exchange.getRequestURI().toASCIIString()))
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				else
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
				try (OutputStream stream = exchange.getResponseBody()) {
					stream.write("HELLO\nWORLD".getBytes());
				}
			}
		});
		CommonAppCurator app = new CommonAppCurator(context);
		Collection<String> expecteds = app.getBackups("test");
		Assert.assertArrayEquals(expecteds.toArray(), new String[] { "HELLO", "WORLD" });
	}

	@Test
	public void storeBackupTest(@Mocked BundleContext context) throws AppException, InterruptedException {
		CommonAppCuratorTest.context.setHandler(new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				byte[] bytes = new byte[8];
				int size = exchange.getRequestBody().read(bytes);
				if ("/cgi-bin/depot.sh/test.config$common".equals(exchange.getRequestURI().toASCIIString()) && size == 5
						&& "HELLO".equals(new String(Arrays.copyOf(bytes, size))))
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				else
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
				exchange.getResponseBody().close();
			}
		});
		CommonAppCurator app = new CommonAppCurator(context);
		new MockUp<CommonAppCurator>(app) {
			@Mock
			void getConfig(String name, OutputStream stream) throws AppException {
				try {
					stream.write("HELLO".getBytes());
				} catch (IOException e) {
					throw new AppException("Write failed", e);
				}
			}
		};
		app.storeBackup("test", "common");
	}

	@Test
	public void restoreBackupTest(@Mocked BundleContext context) throws AppException {
		CommonAppCuratorTest.context.setHandler(new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				if ("/cgi-bin/depot.sh/test.config$common".equals(exchange.getRequestURI().toASCIIString()))
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				else
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
				try (OutputStream stream = exchange.getResponseBody()) {
					stream.write("HELLO".getBytes());
				}
			}
		});
		CommonAppCurator app = new CommonAppCurator(context);
		new MockUp<CommonAppCurator>(app) {
			@Mock
			void setConfig(String name, InputStream stream) throws AppException {
				try {
					byte[] bytes = new byte[8];
					int size = stream.read(bytes);
					Assert.assertEquals(5, size);
					Assert.assertEquals("HELLO", new String(Arrays.copyOf(bytes, size)));
				} catch (IOException e) {
					throw new AppException("Write failed", e);
				}
			}
		};
		app.restoreBackup("test", "common");
	}

	@Test
	public void dropBackupTest(@Mocked BundleContext context) throws AppException {
		CommonAppCuratorTest.context.setHandler(new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				if ("/cgi-bin/depot.sh/test.config$common?DELETE".equals(exchange.getRequestURI().toASCIIString()))
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				else
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
				exchange.getResponseBody().close();
			}
		});
		CommonAppCurator app = new CommonAppCurator(context);
		app.dropBackup("test", "common");
	}
	
	@Test
	public void setLicenseTest(@Mocked BundleContext context, @Mocked final Bundle bundle) throws AppException {
		CommonAppCuratorTest.context.setHandler(new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				if ("/cgi-bin/depot.sh/havis.middleware.license".equals(exchange.getRequestURI().toASCIIString()))
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
				else
					exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, 0);
				exchange.getResponseBody().close();
			}
		});
		new NonStrictExpectations() {
			{
				bundle.getSymbolicName();
				result = "havis.middleware";
			}
		};
		CommonAppCurator app = new CommonAppCurator(context);
		AppInfo appInfo = new AppInfo();
		appInfo.setName("havis.middleware");
		appInfo.setLabel("Middleware");
		appInfo.setVersion("1.6.0");
		appInfo.setProduct("ALE 1.1 for RF-R3x0 (26 99 400 0000 02)");
		app.setEnable(bundle, appInfo, true);
		app.setLicense("havis.middleware", new ByteArrayInputStream("eyJsaWNlbnNlZSI6ICJEYXZpZCBHcmllc2VyIiwgInByb2R1Y3QiOiAiQUxFIDEuMSBmb3IgUkYtUjN4MCAoMjYgOTkgNDAwIDAwMDAgMDIpIiwgInNlcmlhbCI6ICI0NjkyNTg3MzM1MyJ9ALmU3g0ZjSLpuuJkKGEEnyWwxBGg/5ogYcUq0Erfn3j4qZ+upZVW1OtQ7w4JXccyK/ZcvdpdhMbmlTx7iizng69eMumFeZLT3Dz+sW2/OARGjIcC1JWgr/mUGH23N5QHGQPsOrPJuo8d4aB4nJeofe68yGu0IJQAK36MKBdS6N++HEoEYfT8HeT23F5ThhJlkX5rEbRtEaAcfTd6PA+xtRcuGVaTzjwg7fLmQj3XRDjSUlowHYcU9VllXfZ6akuOU9ojNx5jQKdu2jw1BE7XgFzdrZ6ghX5Cv05KpMkxT3uZUTovcY2Z/2sn9Hn3mlyCl1BSi699epSC0vO4aevTZVjD4ixYsSztDX93Cp6zDBNWlSWJV+KF2tvKXbyA+p7hhdT+Nn24+A4NO1rxOjks4ezm4uoN4yltX/KcPu2Kt+YD3x39tfQyEbsZGYOxdJ5TKqei/JmkWtx7HSOWbeHIjbWf+CEw4a2LS07TSS/urrpVbhpR6ezaCMp6Dv/7EXLUauJgS6izvD7LLv7YvOFmmOVdr/z+Mqmvgbo2WJdLa7jOdd0H7LE8Irj7bFbIl1cjnEej7SN0ApwXfau0TfFfrdrZ7pJ2XVzPhFcVgFR2VI1JL2NnmrhZacpQlI5qBUxENBHa8t3iirluPL0OG8rVifUAgnaQ6kPUb7UCPYmy2/1m".getBytes()));
	}
}