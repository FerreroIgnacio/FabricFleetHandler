//DEBUG PARA HOTREALOAD
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;

/**
 * //DEBUG PARA HOTREALOAD
 * Not part of the mod nor of any source set: run with the JDK's source launcher,
 *   java src/debug/hotswap/Hotswap.java PORT STAMP CLASSES_DIR...
 * Attaches over JDWP to the running client (runClient opens PORT, see build.gradle), and redefines
 * every class compiled since STAMP that the client has loaded; then touches STAMP. A class the client
 * has not loaded yet needs nothing: it loads the new one when it comes to it.
 * Plain HotSpot only takes changed method bodies: a new or removed method or field (a new lambda is a
 * new method) fails the whole redefine, and the client has to be restarted. Mixins never reload.
 */
public class Hotswap { //DEBUG PARA HOTREALOAD
	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			throw new IllegalArgumentException("Hotswap PORT STAMP CLASSES_DIR...");
		}

		final Path stamp = Paths.get(args[1]);

		if (!Files.isRegularFile(stamp)) {
			throw new IllegalStateException("no stamp " + stamp + ": touch it when the client is launched");
		}

		final FileTime since = Files.getLastModifiedTime(stamp);
		final Map<String, Path> changed = new HashMap<String, Path>();

		for (int i = 2; i < args.length; i++) {
			final Path root = Paths.get(args[i]);

			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
					if (Files.getLastModifiedTime(file).compareTo(since) > 0) {
						final String name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
						changed.put(name, file);
					}
				}
			}
		}

		final VirtualMachine vm = attach(Integer.parseInt(args[0]));
		final Map<ReferenceType, byte[]> redefine = new HashMap<ReferenceType, byte[]>();

		try {
			for (Map.Entry<String, Path> entry : changed.entrySet()) {
				final List<ReferenceType> loaded = vm.classesByName(entry.getKey());

				for (ReferenceType type : loaded) {
					redefine.put(type, Files.readAllBytes(entry.getValue()));
				}
			}

			if (!redefine.isEmpty()) {
				vm.redefineClasses(redefine);
			}
		} finally {
			vm.dispose();
		}

		Files.setLastModifiedTime(stamp, FileTime.fromMillis(System.currentTimeMillis()));
		System.out.println("hotswap: " + changed.size() + " compiled since the last, " + redefine.size() + " redefined");

		for (ReferenceType type : redefine.keySet()) {
			System.out.println("  " + type.name());
		}
	}

	private static VirtualMachine attach(int port) throws IOException {
		for (AttachingConnector connector : Bootstrap.virtualMachineManager().attachingConnectors()) {
			if (!connector.name().equals("com.sun.jdi.SocketAttach")) {
				continue;
			}

			final Map<String, Connector.Argument> arguments = connector.defaultArguments();
			arguments.get("hostname").setValue("127.0.0.1");
			arguments.get("port").setValue(Integer.toString(port));

			try {
				return connector.attach(arguments);
			} catch (IllegalConnectorArgumentsException e) {
				throw new IllegalStateException("bad JDWP arguments", e);
			}
		}

		throw new UncheckedIOException(new IOException("this JDK has no com.sun.jdi.SocketAttach"));
	}
}
