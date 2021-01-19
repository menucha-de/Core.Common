package havis.util.core.common;

import havis.util.core.app.AppException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PropertyUtil {

	private final static Logger log = Logger.getLogger(PropertyUtil.class.getName());

	private static final String RESULT_GROUP = "result";
	private static final Pattern RESULT_PATTERN = Pattern.compile("^\\{\"result\":\\s*\"(?<" + RESULT_GROUP + ">[^\"]*)\".+\"id\":\\s*2[,|}].*");

	public static void setProperty(String uuid, String data) throws AppException {
		StringBuilder command = new StringBuilder();
		command.append("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"sign_in\", \"params\":{ }}\n");
		command.append("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"set_base\", \"params\":{ \"selector\": \".data_slot.").append(uuid)
				.append("\", \"value\": \"").append(data).append("\" }}");
		String result;
		try {
			result = exec(command.toString(), Environment.RPC_TOOL, Environment.WS_URI);
		} catch (IOException | InterruptedException e) {
			log.log(Level.FINE, "Failed to store persistent key", e);
			throw new AppException("Failed to store persistent key", e);
		}
		if (result == null) {
			throw new AppException("Failed to store persistent key, process returned no output");
		} else if (!result.contains("set_data_slot successful")) {
			throw new AppException("Failed to store persistent key, process did not report success: " + result.replace("\n", "; "));
		}
	}

	public static String getProperty(String uuid) throws AppException {
		StringBuilder command = new StringBuilder();
		command.append("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"sign_in\", \"params\":{ }}\n");
		command.append("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"get_base\", \"params\":{ \"selector\": \".data_slot.").append(uuid).append("\" }}");
		String result;
		try {
			result = exec(command.toString(), Environment.RPC_TOOL, Environment.WS_URI);
		} catch (IOException | InterruptedException e) {
			log.log(Level.FINE, "Failed to retrieve persistent key", e);
			throw new AppException("Failed to retrieve persistent key", e);
		}
		if (result == null) {
			throw new AppException("Failed to retrieve persistent key, process returned no output");
		}
		String[] lines = result.split("\\n");
		for (String line : lines) {
			Matcher m;
			if ((m = RESULT_PATTERN.matcher(line)).matches()) {
				String data = m.group(RESULT_GROUP);
				if (data != null)
					return data;
			}
		}
		throw new AppException("Failed to retrieve persistent key, process did not report a property value: " + result.replace("\n", "; "));
	}

	private static String exec(String input, String... command) throws IOException, InterruptedException {
		Process process = Runtime.getRuntime().exec(command);
		if (input != null) {
			try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
				writer.write(input);
				writer.flush();
			}
		}
		int code = process.waitFor();
		final StringBuilder builder = new StringBuilder();
		read(process.getInputStream(), builder);
		if (code != 0) {
			builder.append("Execution failed\n");
			read(process.getErrorStream(), builder);
			String errorMessage = builder.toString().trim().replace("\n", "; ");
			log.log(Level.SEVERE, "Execution failed with code {0}: {1}", new Object[] { code, errorMessage });
			throw new IOException(errorMessage);
		}
		return builder.toString();
	}

	private static void read(InputStream input, StringBuilder builder) throws UnsupportedEncodingException, IOException {
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
}
