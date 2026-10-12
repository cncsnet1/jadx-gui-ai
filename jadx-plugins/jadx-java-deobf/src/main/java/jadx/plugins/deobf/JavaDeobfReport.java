package jadx.plugins.deobf;

final class JavaDeobfReport {
	private static final int MAX_SUMMARY = 64 * 1024;
	private static final int MAX_DETAILS = 192 * 1024;
	private final StringBuilder summary = new StringBuilder();
	private final StringBuilder details = new StringBuilder();

	synchronized void clear() {
		summary.setLength(0);
		details.setLength(0);
	}

	synchronized void add(String message) {
		append(summary, message, MAX_SUMMARY);
	}

	synchronized void addDetails(String message) {
		append(details, message, MAX_DETAILS);
	}

	private static void append(StringBuilder text, String message, int limit) {
		int remaining = limit - text.length();
		if (remaining > 0) {
			String entry = message + "\n\n";
			text.append(entry, 0, Math.min(remaining, entry.length()));
		}
	}

	synchronized String getText() {
		if (summary.length() == 0 && details.length() == 0) {
			return "No Java inputs have been processed.";
		}
		return "Summary / 处理结果\n\n" + summary
				+ (summary.length() >= MAX_SUMMARY ? "\n[Summary truncated]\n" : "")
				+ "\nEngine details / 引擎日志\n\n" + details
				+ (details.length() >= MAX_DETAILS ? "\n[Details truncated]" : "");
	}
}
