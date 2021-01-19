package havis.util.core.common.rmi;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Class loader for the RMI registry, holding a list of class loader to delegate
 * to when loading interfaces and stub classes
 */
public class RegistryClassLoader extends ClassLoader {

	private static class BundleClassLoader {
		String id;
		ClassLoader classLoader;

		public BundleClassLoader(String id, ClassLoader classLoader) {
			this.id = id;
			this.classLoader = classLoader;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((id == null) ? 0 : id.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof BundleClassLoader))
				return false;
			BundleClassLoader other = (BundleClassLoader) obj;
			if (id == null) {
				if (other.id != null)
					return false;
			} else if (!id.equals(other.id))
				return false;
			return true;
		}
	}

	private ClassLoader parentClassLoader;
	private List<BundleClassLoader> delegateClassLoaders = new CopyOnWriteArrayList<>();

	/**
	 * Creates a new class loader for the RMI registry
	 * 
	 * @param parent
	 *            the parent class loader
	 */
	public RegistryClassLoader(ClassLoader parent) {
		super(parent);
		this.parentClassLoader = parent;
	}

	/**
	 * Creates a new class loader from the specified source for the RMI registry
	 * 
	 * @param source
	 *            the source to copy from
	 */
	public RegistryClassLoader(RegistryClassLoader source) {
		super(source.parentClassLoader);
		this.delegateClassLoaders = new CopyOnWriteArrayList<>(source.delegateClassLoaders);
	}

	/**
	 * @return true if no delegating class loaders are registered, false
	 *         otherwise
	 */
	public boolean isEmpty() {
		return this.delegateClassLoaders.isEmpty();
	}

	/**
	 * Add a class loader to delegate to for a bundle
	 * 
	 * @param id
	 *            the ID the class loader is for
	 * @param loader
	 *            the class loader
	 */
	public void add(String id, ClassLoader loader) {
		this.delegateClassLoaders.add(new BundleClassLoader(id, loader));
	}

	/**
	 * Remove a class loader from the list of loaders to delegate to by
	 * specifying the ID
	 * 
	 * @param id
	 *            the ID of the class loader to remove
	 */
	public void remove(String id) {
		this.delegateClassLoaders.remove(new BundleClassLoader(id, null));
	}

	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		String path = name.replace('.', '/') + ".class";
		for (BundleClassLoader delegate : this.delegateClassLoaders) {
			URL resource = delegate.classLoader.getResource(path);
			if (resource != null) {
				return delegate.classLoader.loadClass(name);
			}
		}
		throw new ClassNotFoundException(name);
	}

	@Override
	protected URL findResource(String name) {
		for (BundleClassLoader delegate : this.delegateClassLoaders) {
			URL resource = delegate.classLoader.getResource(name);
			if (resource != null) {
				return resource;
			}
		}
		return null;
	}

	@Override
	protected Enumeration<URL> findResources(String name) throws IOException {
		List<URL> urls = new ArrayList<URL>();
		for (BundleClassLoader delegate : this.delegateClassLoaders) {
			Enumeration<URL> enumeration = delegate.classLoader.getResources(name);
			while (enumeration.hasMoreElements()) {
				urls.add(enumeration.nextElement());
			}
		}
		return Collections.enumeration(urls);
	}
}
