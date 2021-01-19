package havis.util.core.common.rmi;

import java.rmi.Remote;

public interface RemoteConnectionListener {
	void connected(Remote remote);
}
