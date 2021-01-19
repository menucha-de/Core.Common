package havis.util.core.common;

import java.util.Properties;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import havis.util.core.Core;
import havis.util.core.app.AppCurator;
import havis.util.core.app.AppInfo;
import havis.util.core.common.app.CommonAppCurator;
import havis.util.core.common.log.SQLLogCurator;
import havis.util.core.common.rmi.BundleProcessHandler;
import havis.util.core.log.LogCurator;
import havis.util.core.rmi.ProcessHandler;

public class CommonCore implements Core {

	private CommonAppCurator app;
	private SQLLogCurator log;
	private BundleProcessHandler rmi;

	public CommonCore(BundleContext context, ClassLoader loader) {
		app = new CommonAppCurator(context);
		log = new SQLLogCurator(loader);
		rmi = new BundleProcessHandler(context, loader);
	}

	@Override
	public AppCurator getApp() {
		return app;
	}

	@Override
	public LogCurator getLog() {
		return log;
	}

	@Override
	public ProcessHandler getRmi() {
		return rmi;
	}

	public void close() {
		app.close();
		log.close();
		rmi.close();
	}

	public void setEnable(Bundle bundle, Properties properties, boolean enable) {
		AppInfo info = (properties == null) ? null : CommonAppInfo.get(properties);
		log.setEnable(bundle.getBundleId(), properties, enable);
		app.setEnable(bundle, info, enable);
		rmi.setEnable(bundle, properties, enable);
	}
}