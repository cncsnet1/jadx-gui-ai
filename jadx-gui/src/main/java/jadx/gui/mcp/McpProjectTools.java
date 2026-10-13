package jadx.gui.mcp;

import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.utils.CodeUtils;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.analysis.ApkProfile;
import jadx.gui.ai.analysis.ApkProfileScanner;

/** Read-only tools pinned to the project explicitly shared by the user. */
public final class McpProjectTools {

	private final JadxDecompiler decompiler;
	private volatile boolean revoked;
	private final AiCancellation profileCancellation = new AiCancellation();
	private ApkProfile apkProfile;

	public McpProjectTools(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
	}

	public void revoke() {
		revoked = true;
		profileCancellation.cancel();
	}

	public static JsonArray list() {
		JsonArray tools = new JsonArray();
		tools.add(tool("get_project", "Describe the project currently shared in JADX. No filesystem paths or credentials.", ""));
		tools.add(tool("get_apk_profile", "Read local APK protection and framework indicators, code visibility and native ELF symbols. "
				+ "No unpacking, disassembly or device actions. Native libraries are paginated; symbol samples are bounded.",
				"offset:integer,limit:integer"));
		tools.add(tool("list_classes", "Find classes by name substring. Includes inner classes. Paginated.",
				"query:string,offset:integer,limit:integer"));
		tools.add(tool("get_class_code", "Read decompiled Java source. Use nextOffset for long classes; offsets count UTF-16 characters.",
				"className:string!,offset:integer,limit:integer"));
		tools.add(tool("list_methods", "List methods and stable methodId values in a class. Paginated.",
				"className:string!,offset:integer,limit:integer"));
		tools.add(tool("get_method_code", "Read one method using the exact methodId returned by list_methods. Paginated source.",
				"className:string!,methodId:string!,offset:integer,limit:integer"));
		tools.add(tool("find_usages", "Find references to a class or method. Omit methodId for class references. Paginated.",
				"className:string!,methodId:string,offset:integer,limit:integer"));
		tools.add(tool("search_code", "Literal case-insensitive source search, scanning at most 20 top-level classes per call. "
				+ "Continue using nextOffset even when matches is empty. Class names are returned once per matching class.",
				"query:string!,offset:integer"));
		tools.add(tool("trace_calls",
				"Read up to 32 outgoing bytecode call targets for an exact methodId. Static edges only; no runtime reachability claim.",
				"className:string!,methodId:string!"));
		tools.add(tool("inspect_api",
				"Trace a Retrofit declaration to client construction, base URL, registered interceptors and up to four levels of incoming bytecode calls. "
						+ "Call edges include exact methods, instruction offsets and coverage limits; not runtime reachability. Does not send requests.",
				"className:string!,methodId:string!"));
		return tools;
	}

	private static JsonObject tool(String name, String description, String fields) {
		JsonObject properties = new JsonObject();
		JsonArray required = new JsonArray();
		if (!fields.isEmpty()) {
			for (String field : fields.split(",")) {
				String[] parts = field.split(":");
				JsonObject property = new JsonObject();
				property.addProperty("type", parts[1].replace("!", ""));
				properties.add(parts[0], property);
				if (parts[1].endsWith("!")) {
					required.add(parts[0]);
				}
			}
		}
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		schema.add("required", required);
		schema.addProperty("additionalProperties", false);
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", schema);
		JsonObject annotations = new JsonObject();
		annotations.addProperty("readOnlyHint", true);
		annotations.addProperty("destructiveHint", false);
		annotations.addProperty("openWorldHint", false);
		tool.add("annotations", annotations);
		return tool;
	}

	public JsonObject call(String name, JsonObject args) {
		try {
			check();
			validate(name, args);
			JsonObject data = execute(name, args);
			check(); // Never return a result after a project switch/revocation.
			String payload = data.toString();
			if (payload.length() > 200_000) {
				return McpJson.text("Result exceeds size limit; use a smaller page", true);
			}
			JsonObject result = McpJson.text(payload, false);
			result.add("structuredContent", data);
			return result;
		} catch (IllegalArgumentException e) {
			return McpJson.text(e.getMessage(), true);
		} catch (Exception e) {
			return McpJson.text("Project unavailable, sharing revoked, or decompilation failed", true);
		}
	}

	private static void validate(String name, JsonObject args) {
		JsonObject schema = null;
		for (JsonElement tool : list()) {
			if (tool.getAsJsonObject().get("name").getAsString().equals(name)) {
				schema = tool.getAsJsonObject().getAsJsonObject("inputSchema");
				break;
			}
		}
		if (schema == null) {
			throw new IllegalArgumentException("Unknown tool");
		}
		for (String key : args.keySet()) {
			if (!schema.getAsJsonObject("properties").has(key)) {
				throw new IllegalArgumentException("Unexpected argument");
			}
		}
		for (JsonElement key : schema.getAsJsonArray("required")) {
			String value = McpJson.string(args, key.getAsString(), "");
			if (value.isBlank() || value.length() > 1000) {
				throw new IllegalArgumentException("Missing or oversized argument: " + key.getAsString());
			}
		}
	}

	private JsonObject execute(String name, JsonObject args) throws InterruptedException {
		int offset = McpJson.integer(args, "offset", 0, 0, Integer.MAX_VALUE);
		int limit = McpJson.integer(args, "limit", name.endsWith("_code") ? 20_000 : 100, 1,
				name.endsWith("_code") ? 40_000 : 500);
		JsonObject result = new JsonObject();
		switch (name) {
			case "get_apk_profile":
				return profile(offset, McpJson.integer(args, "limit", 6, 1, 20));
			case "get_project":
				JsonArray files = new JsonArray();
				decompiler.getArgs().getInputFiles().forEach(file -> files.add(file.getName()));
				result.add("files", files);
				result.addProperty("classCount", decompiler.getClassesWithInners().size());
				return result;
			case "list_classes":
				String query = McpJson.string(args, "query", "").toLowerCase(Locale.ROOT);
				List<JavaClass> classes = new java.util.ArrayList<>();
				for (JavaClass cls : decompiler.getClassesWithInners()) {
					check();
					if (cls.getFullName().toLowerCase(Locale.ROOT).contains(query)
							|| cls.getRawName().toLowerCase(Locale.ROOT).contains(query)) {
						classes.add(cls);
					}
				}
				JsonArray items = new JsonArray();
				for (int i = Math.min(offset, classes.size()); i < end(offset, limit, classes.size()); i++) {
					JsonObject item = new JsonObject();
					item.addProperty("className", classes.get(i).getRawName());
					item.addProperty("displayName", classes.get(i).getFullName());
					items.add(item);
				}
				result.add("classes", items);
				page(result, offset, limit, classes.size());
				return result;
			case "search_code":
				String needle = McpJson.string(args, "query", "").toLowerCase(Locale.ROOT);
				List<JavaClass> all = decompiler.getClasses();
				JsonArray matches = new JsonArray();
				for (int i = Math.min(offset, all.size()); i < end(offset, 20, all.size()); i++) {
					check();
					JavaClass cls = all.get(i);
					if (cls.getCode().toLowerCase(Locale.ROOT).contains(needle)) {
						matches.add(cls.getRawName());
					}
				}
				result.add("matches", matches);
				page(result, offset, 20, all.size());
				return result;
			default:
				return classTool(name, args, offset, limit);
		}
	}

	private JsonObject classTool(String name, JsonObject args, int offset, int limit) throws InterruptedException {
		String className = McpJson.string(args, "className", "");
		JavaClass cls = decompiler.getClassesWithInners().stream()
				.filter(c -> c.getRawName().equals(className)).findFirst().orElse(null);
		if (cls == null) {
			cls = decompiler.searchJavaClassByAliasFullName(className);
		}
		if (cls == null) {
			throw new IllegalArgumentException("Class not found");
		}
		cls.getCode();
		JsonObject result = new JsonObject();
		if (name.equals("get_class_code")) {
			return code(cls.getCode(), offset, limit);
		}
		List<JavaMethod> methods = cls.getMethods();
		if (name.equals("list_methods")) {
			JsonArray items = new JsonArray();
			for (int i = Math.min(offset, methods.size()); i < end(offset, limit, methods.size()); i++) {
				JavaMethod method = methods.get(i);
				JsonObject item = new JsonObject();
				item.addProperty("methodId", method.getMethodNode().getMethodInfo().getShortId());
				item.addProperty("name", method.getFullName());
				items.add(item);
			}
			result.add("methods", items);
			page(result, offset, limit, methods.size());
			return result;
		}
		String methodId = McpJson.string(args, "methodId", "");
		JavaNode node = cls;
		if (!methodId.isEmpty()) {
			node = methods.stream().filter(m -> m.getMethodNode().getMethodInfo().getShortId().equals(methodId))
					.findFirst().orElseThrow(() -> new IllegalArgumentException("Method not found"));
		}
		if (name.equals("get_method_code")) {
			return code(CodeUtils.extractMethodCode(((JavaMethod) node).getMethodNode(), cls.getCodeInfo()), offset, limit);
		}
		if (name.equals("trace_calls") || name.equals("inspect_api")) {
			var local = new jadx.gui.ai.analysis.LocalAnalysisTools(decompiler);
			var token = new jadx.gui.ai.AiCancellation();
			if (name.equals("inspect_api")) {
				return jadx.core.utils.GsonUtils.defaultGsonBuilder().create().toJsonTree(local.api((JavaMethod) node, token))
						.getAsJsonObject();
			}
			var targets = local.calls(jadx.api.data.impl.JadxNodeRef.forJavaNode(node), token);
			JsonArray edges = new JsonArray();
			for (var target : targets.subList(0, Math.min(32, targets.size()))) {
				JsonObject edge = new JsonObject();
				edge.addProperty("className", target.getDeclaringClass());
				edge.addProperty("methodId", target.getShortId());
				edge.addProperty("resolved", local.resolve(target) != null);
				edges.add(edge);
			}
			result.add("targets", edges);
			result.addProperty("truncated", targets.size() > 32);
			result.addProperty("coverage",
					"One level of declared bytecode calls; reflection, native code and runtime dispatch unresolved.");
			return result;
		}
		JsonArray items = new JsonArray();
		List<JavaNode> usages = node.getUseIn();
		for (int i = Math.min(offset, usages.size()); i < end(offset, limit, usages.size()); i++) {
			JsonObject item = new JsonObject();
			JavaNode usage = usages.get(i);
			item.addProperty("name", usage.getFullName());
			item.addProperty("className", usage.getDeclaringClass().getRawName());
			items.add(item);
		}
		result.add("usages", items);
		page(result, offset, limit, usages.size());
		return result;
	}

	private synchronized JsonObject profile(int offset, int limit) throws InterruptedException {
		check();
		if (apkProfile == null) {
			apkProfile = ApkProfileScanner.scan(decompiler.getArgs().getInputFiles().stream().map(java.io.File::toPath)
					.collect(java.util.stream.Collectors.toList()),
					decompiler.getClassesWithInners().stream().limit(200_000).map(JavaClass::getRawName)
							.collect(java.util.stream.Collectors.toList()),
					profileCancellation);
		}
		check();
		var gson = jadx.core.utils.GsonUtils.defaultGsonBuilder().create();
		JsonObject result = new JsonObject();
		result.addProperty("scope", "Static artifact indicators only; no unpacking, runtime validation or native decompilation");
		result.addProperty("complete", apkProfile.isComplete());
		result.addProperty("dexCount", apkProfile.getDexCount());
		result.addProperty("javaScriptCount", apkProfile.getJavaScriptCount());
		result.addProperty("nativeLibraryCount", apkProfile.getNativeLibraryCount());
		result.add("facts", gson.toJsonTree(apkProfile.getFacts()));
		result.add("gaps", gson.toJsonTree(apkProfile.getGaps()));
		result.add("recommendations", gson.toJsonTree(apkProfile.getRecommendations()));
		JsonArray libraries = new JsonArray();
		List<ApkProfile.NativeLibrary> all = apkProfile.getNativeLibraries();
		for (int i = Math.min(offset, all.size()); i < end(offset, limit, all.size()); i++) {
			ApkProfile.NativeLibrary library = all.get(i);
			JsonObject item = new JsonObject();
			item.addProperty("path", library.getPath());
			item.addProperty("abi", library.getAbi());
			item.addProperty("architecture", library.getArchitecture());
			item.addProperty("bitness", library.getBitness());
			item.addProperty("elf", library.isElf());
			item.add("jniSymbols",
					gson.toJsonTree(library.getJniSymbols().stream().limit(20).collect(java.util.stream.Collectors.toList())));
			item.add("importedSymbols",
					gson.toJsonTree(library.getImportedSymbols().stream().limit(20).collect(java.util.stream.Collectors.toList())));
			item.add("exportedSymbols",
					gson.toJsonTree(library.getExportedSymbols().stream().limit(20).collect(java.util.stream.Collectors.toList())));
			item.addProperty("symbolsTruncated", library.getJniSymbols().size() > 20 || library.getImportedSymbols().size() > 20
					|| library.getExportedSymbols().size() > 20);
			item.add("limitations", gson.toJsonTree(library.getLimitations()));
			libraries.add(item);
		}
		result.add("nativeLibraries", libraries);
		page(result, offset, limit, all.size());
		return result;
	}

	private static JsonObject code(String code, int offset, int limit) {
		JsonObject result = new JsonObject();
		result.addProperty("code", code.substring(Math.min(offset, code.length()), end(offset, limit, code.length())));
		page(result, offset, limit, code.length());
		return result;
	}

	private static int end(int offset, int limit, int total) {
		return (int) Math.min((long) offset + limit, total);
	}

	private static void page(JsonObject result, int offset, int limit, int total) {
		result.addProperty("total", total);
		if (end(offset, limit, total) < total) {
			result.addProperty("nextOffset", offset + limit);
		}
	}

	private void check() {
		if (revoked || Thread.currentThread().isInterrupted()) {
			throw new IllegalStateException("Sharing revoked");
		}
	}
}
