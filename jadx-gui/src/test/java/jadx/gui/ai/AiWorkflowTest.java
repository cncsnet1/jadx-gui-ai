package jadx.gui.ai;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.Gson;

import jadx.api.data.IJavaNodeRef.RefType;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.settings.data.ProjectData;

import static jadx.core.utils.GsonUtils.defaultGsonBuilder;
import static org.assertj.core.api.Assertions.assertThat;

class AiWorkflowTest {
	@Test
	void chunksPreserveLongLinesAndUnicode() {
		String code = "header\n" + "x".repeat(40001) + "😀\r\n尾部";
		for (int limit : List.of(2, 7, 1024, 12000)) {
			List<String> chunks = AiCodeChunks.split(code, limit);
			assertThat(String.join("", chunks)).isEqualTo(code);
			assertThat(AiCodeChunks.prefix(code, limit)).isEqualTo(chunks.get(0));
			for (String chunk : chunks) {
				assertThat(chunk.length()).isBetween(1, limit);
				assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
				assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
			}
		}
		assertThat(AiCodeChunks.split("", 10)).isEmpty();
	}

	@Test
	void projectRecordsRoundTripAndOldProjectsRemainCompatible() {
		Gson gson = defaultGsonBuilder().create();
		ProjectData original = new ProjectData();
		JadxNodeRef ref = new JadxNodeRef(RefType.METHOD, "sample.A", "a()V");
		AiEvidence source = new AiEvidence(ref, "sample.A.a", "void a() {}", AiEvidence.fingerprint("original source"), false);
		original.getAiRecords().add(new AiRecord("Analysis", "Observed [S1]", List.of(source)));
		ProjectData restored = gson.fromJson(gson.toJson(original), ProjectData.class);
		AiRecord record = restored.getAiRecords().get(0);
		assertThat(record.getAnswer()).isEqualTo("Observed [S1]");
		assertThat(record.getEvidence().get(0).getNode()).isEqualTo(ref);
		assertThat(record.getEvidence().get(0).getFingerprint()).isEqualTo(source.getFingerprint());
		assertThat(AiEvidence.fingerprint("changed source")).isNotEqualTo(source.getFingerprint());
		assertThat(gson.fromJson("{\"projectVersion\":2}", ProjectData.class).getAiRecords()).isEmpty();
		assertThat(gson.fromJson("{\"aiRecords\":null}", ProjectData.class).getAiRecords()).isEmpty();
	}

	@Test
	void exportPreservesMarkdownAndCapturedCode() {
		AiEvidence source = new AiEvidence(JadxNodeRef.forCls("A"), "A", "// ```\nclass A {}", "hash", true);
		AiRecord record = new AiRecord("Audit", "**Finding** [S1]", List.of(source));
		String report = AiReport.markdown(record);
		assertThat(report).contains("**Finding** [S1]", "## [S1] A", "SHA-256: hash", "````java\n// ```\nclass A {}\n````");
	}

	@Test
	void htmlReportEscapesModelMarkupAndDoesNotFetchImages() {
		AiRecord record = new AiRecord("Report", "<script>alert(1)</script>\n\n![remote](https://example.com/pixel)", List.of());
		assertThat(AiReport.html(record)).doesNotContain("<script>", "<img").contains("&lt;script&gt;");
	}

	@Test
	@Timeout(10)
	void replacementRejectsLateResultAndRunsNewestTask() throws Exception {
		List<String> delivered = new CopyOnWriteArrayList<>();
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(1);
		try (AiTaskRunner runner = new AiTaskRunner()) {
			runner.submit(token -> {
				started.countDown();
				// Simulate a decompiler stage which does not terminate immediately on interrupt.
				while (release.getCount() != 0) {
					try {
						release.await();
					} catch (InterruptedException ignored) {
						// Deliberately deliver a late completion.
					}
				}
				return "stale";
			}, delivered::add, e -> delivered.add("stale failure"));
			assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
			runner.submit(token -> "new", value -> {
				delivered.add(value);
				completed.countDown();
			}, e -> completed.countDown());
			release.countDown();
			assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(delivered).containsExactly("new");
		} finally {
			release.countDown();
		}
	}

	@Test
	void translationsHaveMatchingKeys() throws Exception {
		Properties english = new Properties();
		Properties chinese = new Properties();
		try (InputStreamReader en =
				new InputStreamReader(getClass().getResourceAsStream("/ai/messages.properties"), StandardCharsets.UTF_8);
				InputStreamReader zh =
						new InputStreamReader(getClass().getResourceAsStream("/ai/messages_zh.properties"), StandardCharsets.UTF_8)) {
			english.load(en);
			chinese.load(zh);
		}
		assertThat(chinese.keySet()).isEqualTo(english.keySet());
	}
}
