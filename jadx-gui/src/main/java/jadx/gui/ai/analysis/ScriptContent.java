package jadx.gui.ai.analysis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Byte-level format guard shared by inventory and text analysis; it never decodes bytecode. */
final class ScriptContent {
	private static final long HERMES_MAGIC = 0x1F1903C103BC1FC6L;

	private ScriptContent() {
	}

	static boolean isHermes(byte[] bytes) {
		if (bytes.length < 8) {
			return false;
		}
		long magic = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
		return magic == HERMES_MAGIC || magic == ~HERMES_MAGIC;
	}

	static String hermesDescription(byte[] bytes) {
		ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		String form = header.getLong() == HERMES_MAGIC ? "execution" : "delta";
		return "Hermes " + form + " bytecode magic observed; version="
				+ (bytes.length >= 12 ? Integer.toUnsignedString(header.getInt()) : "unread")
				+ ". Header recognition only; bytecode validity, functions and JavaScript source have not been recovered.";
	}

	static boolean isText(byte[] bytes) {
		if (isHermes(bytes)) {
			return false;
		}
		for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
			int value = Byte.toUnsignedInt(bytes[i]);
			if (value < 32 && value != '\n' && value != '\r' && value != '\t' || value == 127) {
				return false;
			}
		}
		return true;
	}
}
