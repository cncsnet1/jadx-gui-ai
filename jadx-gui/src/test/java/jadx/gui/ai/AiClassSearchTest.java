package jadx.gui.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClassSearchTest {
	@Test
	void packageFilterRespectsNamespaceBoundary() {
		assertThat(AiClassSearch.inPackage("com.app.auth.Login", " com.app. ")).isTrue();
		assertThat(AiClassSearch.inPackage("com.apple.Login", "com.app")).isFalse();
		assertThat(AiClassSearch.inPackage("DefaultClass", "")).isTrue();
	}

	@Test
	void batchesBoundCountAndEncodedSizeWithoutDroppingClasses() {
		List<String> names = IntStream.range(0, 500).mapToObj(i -> "com.example.C" + i).collect(Collectors.toList());
		List<List<String>> batches = AiClassSearch.batches(names);
		assertThat(batches).hasSize(4);
		assertThat(batches.stream().flatMap(List::stream).collect(Collectors.toList())).containsExactlyElementsOf(names);
		assertThat(batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(160));
		String longName = "x".repeat(10000);
		assertThat(AiClassSearch.batches(List.of(longName, longName, longName + 'y'))).hasSize(2);
		assertThatThrownBy(() -> AiClassSearch.batches(List.of("x".repeat(18000))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void responseMustUseExactNamesAndNonemptyReasons() throws Exception {
		String response = "```json\n{\"matches\":["
				+ "{\"class\":\"auth.Login\",\"reason\":\"Possible login handler\"},"
				+ "{\"class\":\"auth.Login\",\"reason\":\"Duplicate\"},"
				+ "{\"class\":\"auth.Imagined\",\"reason\":\"Invented\"},"
				+ "{\"class\":\"Login\",\"reason\":\"Short name\"},"
				+ "{\"class\":\"auth.Empty\",\"reason\":\" \"},"
				+ "{\"class\":42,\"reason\":true},null]}\n```";
		List<AiClassSearch.Match> matches = AiClassSearch.parse(response, Set.of("auth.Login", "auth.Empty"));
		assertThat(matches).hasSize(1);
		assertThat(matches.get(0).getClassName()).isEqualTo("auth.Login");
		assertThat(matches.get(0).getReason()).isEqualTo("Possible login handler");
	}

	@Test
	void invalidOrTruncatedResponseIsNotReportedAsNoResults() {
		for (String response : List.of("explanation", "{\"matches\":[", "{}", "{\"matches\":null}")) {
			assertThatThrownBy(() -> AiClassSearch.parse(response, Set.of("A"))).isInstanceOf(IOException.class);
		}
	}

	@Test
	void whitelistIsScopedToEachRequestAndProgressAccumulates() throws Exception {
		List<String> names = IntStream.range(0, 161).mapToObj(i -> "C" + i).collect(Collectors.toList());
		AtomicInteger calls = new AtomicInteger();
		List<AiClassSearch.Progress> states = new ArrayList<>();
		AiClient client = (model, messages, temperature, maxTokens, token, content) -> {
			int call = calls.incrementAndGet();
			String input = messages.get(1).get("content");
			assertThat(JsonParser.parseString(input).getAsJsonObject().get("query").getAsString()).isEqualTo("find login");
			assertThat(maxTokens).isEqualTo(2048);
			return call == 1
					? "{\"matches\":[{\"class\":\"C160\",\"reason\":\"Not in this batch\"},"
							+ "{\"class\":\"C0\",\"reason\":\"First\"}]}"
					: "{\"matches\":[{\"class\":\"C160\",\"reason\":\"Last\"}]}";
		};
		List<AiClassSearch.Match> matches = AiClassSearch.search(client, profile(), "find login", names,
				new AiCancellation(), states::add);
		assertThat(calls).hasValue(2);
		assertThat(matches).extracting(AiClassSearch.Match::getClassName).containsExactly("C0", "C160");
		assertThat(states.get(0).getMatches()).isEmpty();
		assertThat(states.get(states.size() - 1).getCompleted()).isEqualTo(2);
		assertThat(states.get(states.size() - 1).getMatches()).hasSize(2);
	}

	@Test
	void cancellationPreventsNextRequest() {
		List<String> names = IntStream.range(0, 161).mapToObj(i -> "C" + i).collect(Collectors.toList());
		AiCancellation token = new AiCancellation();
		AtomicInteger calls = new AtomicInteger();
		AiClient client = (model, messages, temperature, maxTokens, cancellation, content) -> {
			calls.incrementAndGet();
			return "{\"matches\":[]}";
		};
		assertThatThrownBy(() -> AiClassSearch.search(client, profile(), "find", names, token, state -> {
			if (state.getCompleted() == 1) {
				token.cancel();
			}
		})).isInstanceOf(InterruptedException.class);
		assertThat(calls).hasValue(1);
	}

	@Test
	void completedBatchesRemainAvailableWhenLaterRequestFails() {
		List<String> names = IntStream.range(0, 161).mapToObj(i -> "C" + i).collect(Collectors.toList());
		AtomicInteger calls = new AtomicInteger();
		List<AiClassSearch.Progress> states = new ArrayList<>();
		AiClient client = (model, messages, temperature, maxTokens, token, content) -> {
			if (calls.incrementAndGet() == 2) {
				throw new IOException("Unavailable");
			}
			return "{\"matches\":[{\"class\":\"C0\",\"reason\":\"Relevant\"}]}";
		};
		assertThatThrownBy(() -> AiClassSearch.search(client, profile(), "find", names, new AiCancellation(), states::add))
				.isInstanceOf(IOException.class);
		assertThat(states.get(states.size() - 1).getMatches()).extracting(AiClassSearch.Match::getClassName)
				.containsExactly("C0");
	}

	@Test
	void emptyScopeDoesNotCallProvider() throws Exception {
		AiClient client = (model, messages, temperature, maxTokens, token, content) -> {
			throw new AssertionError("Unexpected request");
		};
		assertThat(AiClassSearch.search(client, profile(), "find", List.of(), new AiCancellation(), state -> {
		})).isEmpty();
		assertThatThrownBy(() -> AiClassSearch.search(client, profile(), " ", List.of(), new AiCancellation(), state -> {
		}))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static AiProfile profile() {
		AiProfile profile = new AiProfile();
		profile.setModel("test-model");
		profile.setOutputTokens(2048);
		return profile;
	}
}
