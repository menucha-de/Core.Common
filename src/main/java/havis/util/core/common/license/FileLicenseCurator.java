package havis.util.core.common.license;

import havis.util.core.app.AppException;
import havis.util.core.app.AppInfo;
import havis.util.core.common.CommonAppInfo;
import havis.util.core.common.PropertyUtil;
import havis.util.core.common.SysInfo;
import havis.util.core.license.License;
import havis.util.core.license.LicenseCurator;
import havis.util.core.license.LicenseException;
import havis.util.core.license.LicenseState;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * License curator for containers only
 */
public class FileLicenseCurator implements LicenseCurator {

	private static final Logger log = Logger.getLogger(FileLicenseCurator.class.getName());
	private static final String LICENSE_PATH = "info/%s.license";
	private static final int DELAY = 6;
	private static final TimeUnit UNIT = TimeUnit.HOURS;

	static {
		SysInfo.init();
	}

	private ScheduledExecutorService executor;
	private AppInfo info = CommonAppInfo.getInfo();

	public FileLicenseCurator() {
		if (info != null && info.getProduct() != null && info.getLicense() == LicenseState.UNLICENSED) {
			this.executor = Executors.newSingleThreadScheduledExecutor();
			this.executor.schedule(new Runnable() {
				@Override
				public void run() {
					if (info.getLicense() == LicenseState.UNLICENSED) {
						try {
							exec("/sbin/halt");
						} catch (IOException | InterruptedException e) {
							log.log(Level.FINE, "Halting app failed!");
						}
					}
				}
			}, DELAY, UNIT);
		}
	}

	private void read(InputStream input, StringBuilder builder) throws UnsupportedEncodingException, IOException {
		int size = input.available();
		if (size > 0) {
			char[] b = new char[size];
			try (Reader reader = new InputStreamReader(input, "UTF-8")) {
				while ((size = reader.read(b)) > -1) {
					builder.append(b);
				}
			}
		}
	}

	private String exec(String... command) throws IOException, InterruptedException {
		ProcessBuilder ps = new ProcessBuilder(command);
		ps.redirectErrorStream(true);
		Process process = ps.start();
		InputStream is = process.getInputStream();
		byte[] data = new byte[16384];
		while (is.read(data, 0, data.length) != -1) {
		}
		int code = process.waitFor();
		// TODO why read the input (process output) and then read it again?!
		final StringBuilder builder = new StringBuilder();
		read(process.getInputStream(), builder);
		if (code != 0) {
			log.log(Level.WARNING, "Execution failed with code {0}", code);
			builder.append("Execution failed\n");
			read(process.getErrorStream(), builder);
			throw new IOException(builder.toString());
		}
		return builder.toString();
	}

	public static License getLicense(AppInfo info) throws LicenseException {
		if (info != null) {
			try {
				File localFile = new File(String.format(LICENSE_PATH, info.getName()));
				if (localFile.exists()) {
					StringBuilder builder = new StringBuilder();
					try (Scanner scanner = new Scanner(localFile)) {
						scanner.useDelimiter("\\A");
						while (scanner.hasNext())
							builder.append(scanner.next());
					}
					try {
						return Util.get(info.getProduct(), builder.toString());
					} catch (LicenseException e) {
						// bad file, ignore (read from property)
					}
				}

				return Util.get(info.getProduct(), PropertyUtil.getProperty(info.getUuid()));
			} catch (FileNotFoundException | AppException e) {
				log.log(Level.FINE, "Failed to get license", e);
				throw new LicenseException("Failed to get license", e);
			}
		} else {
			throw new LicenseException("Unknown app; AppInfo is null!");
		}
	}

	@Override
	public License getLicense() throws LicenseException {
		return getLicense(info);
	}

	@Override
	public License getLicenseRequest() throws LicenseException {
		if (info != null) {
			return new License(info.getProduct(), Util.getSerial());
		} else {
			log.log(Level.FINE, "Unknown app; AppInfo is null!");
			throw new LicenseException("Unknown app; AppInfo is null!");
		}
	}

	@Override
	public void setLicense(InputStream stream) throws LicenseException {
		if (info != null) {
			try {
				Path licensePath = Paths.get(String.format(LICENSE_PATH, info.getName()));
				File folder = licensePath.getParent().toFile();
				if (!folder.exists())
					folder.mkdirs();
				Files.copy(stream, licensePath, StandardCopyOption.REPLACE_EXISTING);
				String data = new String(Files.readAllBytes(licensePath));
				Util.get(info.getProduct(), data);
				info.setLicense(LicenseState.LICENSED);
				PropertyUtil.setProperty(info.getUuid(), data);
				if (executor != null) {
					executor.shutdownNow();
				}
			} catch (IOException | AppException e) {
				log.log(Level.FINE, "Failed to set license '" + info.getName() + "'", e);
				throw new LicenseException("Failed to set license '" + info.getName() + "'", e);
			}
		} else {
			log.log(Level.FINE, "Unknown app; AppInfo is null!");
			throw new LicenseException("Unknown app; AppInfo is null!");
		}
	}

	@Override
	public AppInfo getAppInfo() {
		return info;
	}
}
