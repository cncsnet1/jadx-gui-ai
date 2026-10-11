package jadx.gui.ai;

import java.util.ArrayList;
import java.util.List;

/** Lossless, bounded chunks: an oversized line is split rather than skipped. */
public final class AiCodeChunks {
	private AiCodeChunks() {
	}

	public static String prefix(String code, int limit) {
		if (limit < 2) {
			throw new IllegalArgumentException("Chunk limit must be at least 2");
		}
		return code.substring(0, end(code, 0, limit));
	}

	public static List<String> split(String code, int limit) {
		if (limit < 2) {
			throw new IllegalArgumentException("Chunk limit must be at least 2");
		}
		List<String> chunks = new ArrayList<>();
		int start = 0;
		while (start < code.length()) {
			int end = end(code, start, limit);
			chunks.add(code.substring(start, end));
			start = end;
		}
		return chunks;
	}

	private static int end(String code, int start, int limit) {
		int end = start + Math.min(code.length() - start, limit);
		if (end < code.length()) {
			int newline = code.lastIndexOf('\n', end - 1);
			if (newline >= start) {
				end = newline + 1;
			} else if (Character.isHighSurrogate(code.charAt(end - 1)) && Character.isLowSurrogate(code.charAt(end))) {
				end--;
			}
		}
		return end;
	}
}
