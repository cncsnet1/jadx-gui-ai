package jadx.plugins.deobf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class JavaArchive {
	private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
	private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;

	private JavaArchive() {
	}

	static void wrapClass(Path input, Path output) throws IOException {
		byte[] bytes;
		try (InputStream stream = Files.newInputStream(input)) {
			bytes = readClass(stream);
		}
		String name = className(bytes);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
			zip.putNextEntry(new ZipEntry(name + ".class"));
			zip.write(bytes);
			zip.closeEntry();
		}
	}

	static Map<String, String> inspect(Path file) throws IOException {
		Map<String, String> classes = new LinkedHashMap<>();
		long total = 0;
		int count = 0;
		try (ZipFile zip = new ZipFile(file.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > 100_000) {
					throw new IOException("Archive has too many entries");
				}
				String path = entry.getName();
				String lower = path.toLowerCase(Locale.ROOT);
				if (lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".dex")
						|| path.startsWith("META-INF/versions/") || path.startsWith("BOOT-INF/") || path.startsWith("WEB-INF/")) {
					throw new IOException(
							"Nested, multi-release and Android archives are not supported; open the relevant JAR/CLASS directly");
				}
				if (entry.isDirectory() || !path.endsWith(".class")) {
					continue;
				}
				byte[] bytes;
				try (InputStream stream = zip.getInputStream(entry)) {
					bytes = readClass(stream);
				}
				total += bytes.length;
				if (total > MAX_TOTAL_BYTES) {
					throw new IOException("Archive class data exceeds 256 MiB");
				}
				String name = className(bytes);
				if (!path.equals(name + ".class") || classes.put(name, digest(bytes)) != null) {
					throw new IOException("Nonstandard or duplicate class entry: " + path);
				}
			}
		}
		if (classes.isEmpty()) {
			throw new IOException("Archive contains no Java classes");
		}
		return classes;
	}

	private static byte[] readClass(InputStream stream) throws IOException {
		byte[] bytes = stream.readNBytes(MAX_CLASS_BYTES + 1);
		if (bytes.length > MAX_CLASS_BYTES) {
			throw new IOException("Class exceeds 16 MiB");
		}
		return bytes;
	}

	private static String className(byte[] bytes) throws IOException {
		if (bytes.length < 10 || bytes[0] != (byte) 0xCA || bytes[1] != (byte) 0xFE
				|| bytes[2] != (byte) 0xBA || bytes[3] != (byte) 0xBE) {
			throw new IOException("Invalid Java class header");
		}
		try {
			ClassReader reader = new ClassReader(bytes);
			reader.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
					};
				}
			}, 0);
			return reader.getClassName();
		} catch (RuntimeException e) {
			throw new IOException("Invalid or unsupported class file", e);
		}
	}

	static String digest(byte[] data) {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
