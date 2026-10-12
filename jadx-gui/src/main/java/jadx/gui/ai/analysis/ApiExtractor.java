package jadx.gui.ai.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationMethodParamsAttr;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.core.dex.nodes.MethodNode;

/** Structural annotation extraction. Values are never paired with unrelated base URLs. */
public final class ApiExtractor {
	private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "HTTP");
	private static final Set<String> PARAMS = Set.of("Path", "Query", "QueryName", "QueryMap", "Field", "FieldMap",
			"Part", "PartMap", "Header", "HeaderMap", "Body", "Url");
	private static final Pattern NETWORK_CALL =
			Pattern.compile("L?(?:okhttp3\\.Request\\$Builder|com\\.squareup\\.okhttp\\.Request\\$Builder"
					+ "|java\\.net\\.HttpURLConnection|javax\\.net\\.ssl\\.HttpsURLConnection)\\..+");

	private ApiExtractor() {
	}

	public static ApiEndpoint method(MethodNode method, String title, JadxNodeRef ref) {
		AnnotationsAttr annotations = method.get(JadxAttrType.ANNOTATION_LIST);
		if (annotations == null) {
			return null;
		}
		AnnotationMethodParamsAttr params = method.get(JadxAttrType.ANNOTATION_MTH_PARAMETERS);
		return declaration(List.copyOf(annotations.getAll()),
				params == null ? List.of() : params.getParamList(),
				method.getArgTypes().stream().map(Object::toString).collect(java.util.stream.Collectors.toList()), title, ref);
	}

	static ApiEndpoint declaration(List<IAnnotation> annotations, List<AnnotationsAttr> params, List<String> types,
			String title, JadxNodeRef ref) {
		ApiEndpoint endpoint = null;
		for (IAnnotation annotation : annotations) {
			String name = retrofitName(annotation);
			if (VERBS.contains(name)) {
				String method = name.equals("HTTP") ? string(annotation.getValues(), "method") : name;
				String path = string(annotation.getValues(), name.equals("HTTP") ? "path" : "value");
				endpoint = new ApiEndpoint(ApiEndpoint.Origin.DECLARATION, method, path, title, ref);
				if (name.equals("HTTP") && Boolean.TRUE.equals(value(annotation.getValues(), "hasBody"))) {
					endpoint.body("HTTP hasBody=true");
				}
				break;
			}
		}
		if (endpoint == null) {
			return null;
		}
		for (IAnnotation annotation : annotations) {
			String name = retrofitName(annotation);
			if (name.equals("FormUrlEncoded") || name.equals("Multipart")) {
				endpoint.body(name);
			} else if (name.equals("Headers")) {
				Object headers = value(annotation.getValues(), "value");
				if (headers instanceof List<?>) {
					List<?> entries = (List<?>) headers;
					if (entries.size() > 80) {
						endpoint.truncated();
					}
					for (Object entry : entries.subList(0, Math.min(80, entries.size()))) {
						if (entry instanceof EncodedValue && ((EncodedValue) entry).getValue() instanceof String) {
							endpoint.header((String) ((EncodedValue) entry).getValue());
						}
					}
				} else if (headers instanceof String) {
					endpoint.header((String) headers);
				}
			}
		}
		for (int i = 0; i < Math.min(params.size(), 80); i++) {
			AnnotationsAttr parameter = params.get(i);
			if (parameter == null) {
				continue;
			}
			for (IAnnotation annotation : parameter.getAll()) {
				String name = retrofitName(annotation);
				if (!PARAMS.contains(name)) {
					continue;
				}
				String paramName = string(annotation.getValues(), "value");
				String type = i < types.size() ? types.get(i) : "";
				endpoint.parameter(name, paramName.isEmpty() ? "arg" + (i + 1) : paramName, type);
				if (name.equals("Body")) {
					endpoint.body("Body: " + type);
				} else if (name.equals("Url")) {
					endpoint.dynamicUrl();
				}
			}
		}
		if (params.size() > 80) {
			endpoint.truncated();
		}
		return endpoint;
	}

	static String retrofitName(IAnnotation annotation) {
		String name = annotation.getAnnotationClass().replace('/', '.');
		if (name.startsWith("L") && name.endsWith(";")) {
			name = name.substring(1, name.length() - 1);
		}
		if (name.startsWith("retrofit2.http.")) {
			return name.substring("retrofit2.http.".length());
		}
		if (name.startsWith("retrofit.http.")) {
			return name.substring("retrofit.http.".length());
		}
		return "";
	}

	private static Object value(Map<String, EncodedValue> values, String name) {
		EncodedValue value = values.get(name);
		return value == null ? null : value.getValue();
	}

	private static String string(Map<String, EncodedValue> values, String name) {
		Object value = value(values, name);
		return value instanceof String ? (String) value : "";
	}

	static ApiEndpoint networkContext(String calls, String title, JadxNodeRef ref) {
		String normalized = calls.replace('/', '.').replace(";.", ".");
		if (normalized.lines().anyMatch(line -> NETWORK_CALL.matcher(line).matches())) {
			return new ApiEndpoint(ApiEndpoint.Origin.CODE_CONTEXT, "", "", title, ref);
		}
		return null;
	}
}
