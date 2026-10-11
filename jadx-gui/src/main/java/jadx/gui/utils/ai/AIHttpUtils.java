package jadx.gui.utils.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.OpenAiClient;

public class AIHttpUtils {
	private static final Logger LOG = LoggerFactory.getLogger(AIHttpUtils.class);
	private final String BASE_URL;
	private static final int TIMEOUT_SECONDS = 60;

	private final String apiKey;
	private final HttpClient httpClient;
	private volatile AiCancellation cancellation = new AiCancellation();

	public AIHttpUtils(String baseUrl, String apiKey) {
		this.apiKey = apiKey;
		this.BASE_URL = baseUrl;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
				.build();
	}

	public void cancelAllRequests() {
		cancellation.cancel();
	}

	public void reset() {
		cancellation.cancel();
		cancellation = new AiCancellation();
	}

	/**
	 * 创建文本对话请求
	 *
	 * @param model       模型名称，如 "Qwen/Qwen2.5-72B-Instruct"
	 * @param messages    对话消息列表
	 * @param temperature 温度参数，控制随机性，默认0.7
	 * @param maxTokens   最大生成token数
	 * @return 模型生成的回复内容
	 * @throws IOException          网络请求异常
	 * @throws InterruptedException 请求被中断
	 */
	public String createChatCompletion(String model, List<Map<String, String>> messages,
			double temperature, int maxTokens) throws IOException, InterruptedException {
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", model);

		JsonArray messagesArray = new JsonArray();
		for (Map<String, String> message : messages) {
			JsonObject messageObj = new JsonObject();
			messageObj.addProperty("role", message.get("role"));
			messageObj.addProperty("content", message.get("content"));
			messagesArray.add(messageObj);
		}
		requestBody.add("messages", messagesArray);

		// 添加可选参数
		requestBody.addProperty("temperature", temperature);
		requestBody.addProperty("max_tokens", maxTokens);
		requestBody.addProperty("top_k", 50);
		requestBody.addProperty("top_p", 0.7);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/chat/completions"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			LOG.error("API请求失败: {}", response.body());
			throw new IOException("API请求失败，状态码: " + response.statusCode());
		}

		JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
		return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
	}

	/**
	 * 创建文本转语音请求
	 *
	 * @param model              语音模型，如 "FunAudioLLM/CosyVoice2-0.5B"
	 * @param text               要转换为语音的文本
	 * @param voice              音色，如 "FunAudioLLM/CosyVoice2-0.5B:alex"
	 * @param outaddPropertyPath 输出音频文件路径
	 * @throws IOException          网络请求异常
	 * @throws InterruptedException 请求被中断
	 */
	public void createSpeech(String model, String text, String voice, Path outaddPropertyPath)
			throws IOException, InterruptedException {
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", model);
		requestBody.addProperty("inaddProperty", text);
		requestBody.addProperty("voice", voice);
		requestBody.addProperty("response_format", "mp3");
		requestBody.addProperty("speed", 1.0);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/audio/speech"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
				.build();

		HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

		if (response.statusCode() != 200) {
			try (InputStream errorStream = response.body()) {
				byte[] errorBytes = errorStream.readAllBytes();
				String errorBody = new String(errorBytes);
				LOG.error("语音生成请求失败: {}", errorBody);
			}
			throw new IOException("语音生成请求失败，状态码: " + response.statusCode());
		}

		try (InputStream inputStream = response.body()) {
			Files.copy(inputStream, outaddPropertyPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * 创建嵌入向量请求
	 *
	 * @param model 嵌入模型，如 "BAAI/bge-large-zh-v1.5"
	 * @param text  要嵌入的文本
	 * @return 嵌入向量
	 * @throws IOException          网络请求异常
	 * @throws InterruptedException 请求被中断
	 */
	public List<Double> createEmbedding(String model, String text) throws IOException, InterruptedException {
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("model", model);
		requestBody.addProperty("inaddProperty", text);

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/embeddings"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			LOG.error("嵌入请求失败: {}", response.body());
			throw new IOException("嵌入请求失败，状态码: " + response.statusCode());
		}

		JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray embeddingArray = responseJson.getAsJsonArray("data")
				.get(0).getAsJsonObject().getAsJsonArray("embedding");

		List<Double> embeddings = new ArrayList<>();
		for (int i = 0; i < embeddingArray.size(); i++) {
			embeddings.add(embeddingArray.get(i).getAsDouble());
		}

		return embeddings;
	}

	/**
	 * 获取可用模型列表
	 *
	 * @return 模型列表
	 * @throws IOException          网络请求异常
	 * @throws InterruptedException 请求被中断
	 */
	public List<String> getModelList() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(BASE_URL + "/models"))
				.header("Authorization", "Bearer " + apiKey)
				.GET()
				.build();

		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			LOG.error("获取模型列表失败: {}", response.body());
			throw new IOException("获取模型列表失败，状态码: " + response.statusCode());
		}

		JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
		JsonArray modelsArray = responseJson.getAsJsonArray("data");

		List<String> models = new ArrayList<>();
		for (int i = 0; i < modelsArray.size(); i++) {
			models.add(modelsArray.get(i).getAsJsonObject().get("id").getAsString());
		}

		return models;
	}

	/**
	 * 创建简单的聊天消息
	 *
	 * @param role    角色，如 "user", "assistant", "system"
	 * @param content 消息内容
	 * @return 消息映射
	 */
	public static Map<String, String> createMessage(String role, String content) {
		Map<String, String> message = new HashMap<>();
		message.put("role", role);
		message.put("content", content);
		return message;
	}

	// Compatibility facade for existing AI search callers. Transport and cancellation
	// are shared with the workbench; no polling or secondary completion latch.
	public void createStreamingChatCompletion(String apiUrl, String model, List<Map<String, String>> messages,
			double temperature, int maxTokens, StreamResponseHandler handler) throws IOException, InterruptedException {
		try {
			createStreamingChatCompletionWithCallback(apiUrl, model, messages, temperature, maxTokens, handler::onContent);
		} catch (IOException e) {
			handler.onError(e);
			return;
		}
		handler.onComplete();
	}

	public void createStreamingChatCompletion(String model, List<Map<String, String>> messages,
			double temperature, int maxTokens, StreamResponseHandler handler) throws IOException, InterruptedException {
		createStreamingChatCompletion(null, model, messages, temperature, maxTokens, handler);
	}

	public String createStreamingChatCompletionBlocking(String apiUrl, String model, List<Map<String, String>> messages,
			double temperature, int maxTokens) throws IOException, InterruptedException {
		return createStreamingChatCompletionWithCallback(apiUrl, model, messages, temperature, maxTokens, chunk -> {
		});
	}

	public String createStreamingChatCompletionBlocking(String model, List<Map<String, String>> messages,
			double temperature, int maxTokens) throws IOException, InterruptedException {
		return createStreamingChatCompletionBlocking(null, model, messages, temperature, maxTokens);
	}

	public String createStreamingChatCompletionWithCallback(String apiUrl, String model, List<Map<String, String>> messages,
			double temperature, int maxTokens, Consumer<String> consumer) throws IOException, InterruptedException {
		AiCancellation token = cancellation;
		return new OpenAiClient(apiUrl == null ? BASE_URL : apiUrl, apiKey)
				.stream(model, messages, temperature, maxTokens, token, consumer);
	}

	public String createStreamingChatCompletionWithCallback(String model, List<Map<String, String>> messages,
			double temperature, int maxTokens, Consumer<String> consumer) throws IOException, InterruptedException {
		return createStreamingChatCompletionWithCallback(null, model, messages, temperature, maxTokens, consumer);
	}

	public interface StreamResponseHandler {
		void onContent(String contentChunk);

		void onError(Exception e);

		void onComplete();
	}
}
