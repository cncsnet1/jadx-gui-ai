package jadx.gui.ai.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.ai.AiCancellation;
import jadx.gui.ai.AiCodeChunks;
import jadx.gui.ai.AiContext;
import jadx.gui.ai.AiEvidence;
import jadx.gui.ai.AiText;

/** Conservative interpretation of straight-line Retrofit / OkHttp builder expressions. */
public final class ApiRelationResolver {
	private final JadxDecompiler decompiler;

	public ApiRelationResolver(JadxDecompiler decompiler) {
		this.decompiler = decompiler;
	}

	public ApiDossier resolve(ApiEndpoint endpoint, AiCancellation token) throws InterruptedException {
		ApiDossier dossier = new ApiDossier(endpoint.getId());
		if (endpoint.getOrigin() == ApiEndpoint.Origin.ADDRESS || endpoint.getSources().get(0).getNode() == null) {
			dossier.gap(AiText.str("api.relation.declaration_required"));
			return dossier;
		}
		JavaNode declaration = AiContext.resolve(decompiler, endpoint.getSources().get(0).getNode());
		if (declaration == null) {
			dossier.gap(AiText.str("investigation.node_missing"));
			return dossier;
		}
		dossier.setCallPath(new CallPathTracer(decompiler).trace(endpoint.getSources().get(0).getNode(), token));
		if (endpoint.getOrigin() == ApiEndpoint.Origin.CODE_CONTEXT) {
			dossier.gap(AiText.str("api.path.construction"));
			return dossier;
		}
		JavaClass api = declaration.getDeclaringClass();
		Set<JavaClass> factories = new LinkedHashSet<>();
		for (JavaNode usage : api.getUseIn()) {
			token.check();
			factories.add(usage.getTopParentClass());
			if (factories.size() > 12) {
				break;
			}
		}
		if (factories.size() > 12) {
			dossier.gap(AiText.str("api.relation.limit"));
		}
		int count = 0;
		for (JavaClass factory : factories) {
			token.check();
			if (++count > 12) {
				break;
			}
			try {
				String code = factory.getCode();
				token.check();
				analyze(code, api.getFullName().replace('$', '.'), factory.getFullName(), JadxNodeRef.forCls(factory), dossier, token);
			} catch (RuntimeException e) {
				dossier.gap(AiText.str("analysis.read_failed") + factory.getRawName());
			}
		}
		Set<String> interceptorNames = new LinkedHashSet<>();
		dossier.getBindings().forEach(b -> interceptorNames.addAll(b.getInterceptors()));
		for (String name : interceptorNames) {
			token.check();
			if (dossier.getEvidence().size() >= 16) {
				dossier.gap(AiText.str("api.relation.limit"));
				break;
			}
			JavaClass cls = decompiler.searchJavaClassByAliasFullName(name);
			if (cls == null) {
				dossier.gap(AiText.str("api.relation.interceptor_missing") + name);
				continue;
			}
			for (JavaMethod method : cls.getMethods()) {
				if (method.getName().equals("intercept")) {
					try {
						dossier.evidence(new LocalAnalysisTools(decompiler).inspect(JadxNodeRef.forMth(method), token).getEvidence());
					} catch (RuntimeException e) {
						dossier.gap(AiText.str("analysis.read_failed") + name);
					}
				}
			}
		}
		if (endpoint.isDynamicUrl()) {
			dossier.gap(AiText.str("api.dynamic_url"));
		}
		if (dossier.getBindings().size() > 1) {
			dossier.gap(AiText.str("api.relation.multiple"));
		}
		dossier.gap(AiText.str("api.relation.coverage"));
		return dossier;
	}

	static void analyze(String source, String api, String owner, JadxNodeRef ref, ApiDossier dossier, AiCancellation token)
			throws InterruptedException {
		if (source.length() > 200_000) {
			dossier.gap(AiText.str("api.relation.limit"));
			return;
		}
		var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
		var parsed = parser.parse(source);
		if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
			dossier.gap(AiText.str("api.relation.parse_failed"));
			return;
		}
		CompilationUnit unit = parsed.getResult().orElseThrow();
		int visited = 0;
		for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
			token.check();
			if (++visited > 80) {
				dossier.gap(AiText.str("api.relation.limit"));
				break;
			}
			if (method.getBody().isEmpty() || !method.toString().contains("create")) {
				continue;
			}
			var body = method.getBody().orElseThrow();
			// Branch merges, loops, helper returns and injected clients require a deeper data-flow engine.
			if (body.getStatements().stream().anyMatch(s -> !s.isExpressionStmt() && !s.isReturnStmt() && !s.isEmptyStmt())) {
				dossier.gap(AiText.str("api.relation.control_flow"));
				continue;
			}
			Map<String, Value> constants = new HashMap<>();
			method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).ifPresent(type -> {
				for (Object member : type.getMembers()) {
					if (member instanceof FieldDeclaration) {
						FieldDeclaration field = (FieldDeclaration) member;
						if (field.isFinal()) {
							field.getVariables().forEach(v -> v.getInitializer().filter(Expression::isStringLiteralExpr)
									.ifPresent(e -> constants.put(v.getNameAsString(), Value.string(e.asStringLiteralExpr().asString()))));
						}
					}
				}
			});
			method.getParameters().forEach(p -> constants.put(p.getNameAsString(), Value.unknown()));
			Interpreter interpreter = new Interpreter(unit, api, constants);
			for (Statement statement : body.getStatements()) {
				token.check();
				if (statement.isExpressionStmt()) {
					interpreter.value(statement.asExpressionStmt().getExpression(), 0);
				}
				if (statement.isReturnStmt()) {
					statement.asReturnStmt().getExpression().ifPresent(e -> interpreter.value(e, 0));
					break;
				}
			}
			for (Value binding : interpreter.bindings) {
				String code = AnalysisRedactor.redact(method.toString());
				dossier.add(new ApiDossier.Binding(binding.url, owner + '.' + method.getSignature(), binding.interceptors, ref),
						new AiEvidence(ref, owner + '.' + method.getSignature() + " [client construction]",
								AiCodeChunks.prefix(code, 6000), AiEvidence.fingerprint(method.toString()), code.length() > 6000));
				if (!binding.complete) {
					dossier.gap(AiText.str("api.relation.partial_client"));
				}
			}
		}
	}

	private static final class Value {
		private final String kind;
		private String text = "";
		private String url = "";
		private List<String> interceptors = new ArrayList<>();
		private boolean complete = true;

		Value(String kind) {
			this.kind = kind;
		}

		static Value unknown() {
			Value v = new Value("unknown");
			v.complete = false;
			return v;
		}

		static Value string(String text) {
			Value v = new Value("string");
			v.text = text;
			return v;
		}

		Value copy(String kind) {
			Value copy = new Value(kind);
			copy.text = text;
			copy.url = url;
			copy.interceptors = new ArrayList<>(interceptors);
			copy.complete = complete;
			return copy;
		}
	}

	private static final class Interpreter {
		private final CompilationUnit unit;
		private final String api;
		private final Map<String, Value> variables;
		private final List<Value> bindings = new ArrayList<>();

		Interpreter(CompilationUnit unit, String api, Map<String, Value> variables) {
			this.unit = unit;
			this.api = api;
			this.variables = variables;
		}

		Value value(Expression expression, int depth) {
			if (depth > 40 || bindings.size() > 8) {
				return Value.unknown();
			}
			if (expression.isEnclosedExpr()) {
				return value(expression.asEnclosedExpr().getInner(), depth + 1);
			}
			if (expression.isCastExpr()) {
				return value(expression.asCastExpr().getExpression(), depth + 1);
			}
			if (expression.isStringLiteralExpr()) {
				return Value.string(expression.asStringLiteralExpr().asString());
			}
			if (expression.isNameExpr()) {
				return variables.getOrDefault(expression.asNameExpr().getNameAsString(), Value.unknown());
			}
			if (expression.isClassExpr()) {
				Value v = new Value("class");
				v.text = type(expression.asClassExpr().getType().asString());
				return v;
			}
			if (expression.isObjectCreationExpr()) {
				if (expression.asObjectCreationExpr().getAnonymousClassBody().isPresent()) {
					return Value.unknown();
				}
				String type = type(expression.asObjectCreationExpr().getType().asString());
				expression.asObjectCreationExpr().getArguments().forEach(this::invalidateEscaped);
				if (type.equals("retrofit2.Retrofit.Builder")) {
					return expression.asObjectCreationExpr().getArguments().isEmpty() ? new Value("retrofit-builder") : Value.unknown();
				}
				if (type.equals("okhttp3.OkHttpClient.Builder")) {
					return expression.asObjectCreationExpr().getArguments().isEmpty() ? new Value("http-builder") : Value.unknown();
				}
				Value v = new Value("object");
				v.text = type;
				return v;
			}
			if (expression.isVariableDeclarationExpr()) {
				expression.asVariableDeclarationExpr().getVariables().forEach(v -> variables.put(v.getNameAsString(),
						v.getInitializer().map(e -> value(e, depth + 1)).orElseGet(Value::unknown)));
				return Value.unknown();
			}
			if (expression.isAssignExpr()) {
				var assignment = expression.asAssignExpr();
				Value assigned = assignment.getOperator() == com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
						? value(assignment.getValue(), depth + 1)
						: Value.unknown();
				if (assignment.getTarget().isNameExpr()) {
					variables.put(assignment.getTarget().asNameExpr().getNameAsString(), assigned);
				} else {
					invalidate(assigned);
				}
				return assigned;
			}
			if (!expression.isMethodCallExpr()) {
				invalidateEscaped(expression);
				return Value.unknown();
			}
			MethodCallExpr call = expression.asMethodCallExpr();
			Value scope = call.getScope().map(e -> value(e, depth + 1)).orElseGet(Value::unknown);
			String method = call.getNameAsString();
			if (scope.kind.equals("retrofit") && method.equals("create") && call.getArguments().size() == 1) {
				Value arg = value(call.getArgument(0), depth + 1);
				if (arg.kind.equals("class") && arg.text.equals(api)) {
					bindings.add(scope.copy("binding"));
				}
				return Value.unknown();
			}
			if (scope.kind.equals("retrofit-builder")) {
				if (method.equals("build") && call.getArguments().isEmpty()) {
					return scope.copy("retrofit");
				}
				if (method.equals("baseUrl") && call.getArguments().size() == 1) {
					Value arg = value(call.getArgument(0), depth + 1);
					scope.url = arg.kind.equals("string") && arg.text.matches("(?is)https?://[^\\s]+") ? arg.text : "";
					scope.complete &= !scope.url.isEmpty();
					return scope;
				}
				if (method.equals("client") && call.getArguments().size() == 1) {
					Value arg = value(call.getArgument(0), depth + 1);
					scope.interceptors = new ArrayList<>(arg.interceptors);
					scope.complete &= arg.kind.equals("http") && arg.complete;
					return scope;
				}
				if (Set.of("addConverterFactory", "addCallAdapterFactory", "validateEagerly", "callbackExecutor").contains(method)) {
					call.getArguments().forEach(this::invalidateEscaped);
					return scope;
				}
			}
			if (scope.kind.equals("http-builder")) {
				if (method.equals("build") && call.getArguments().isEmpty()) {
					return scope.copy("http");
				}
				if ((method.equals("addInterceptor") || method.equals("addNetworkInterceptor")) && call.getArguments().size() == 1) {
					Value arg = value(call.getArgument(0), depth + 1);
					if (arg.kind.equals("object") && !arg.text.isBlank()) {
						scope.interceptors.add(arg.text);
					} else {
						scope.complete = false;
					}
					return scope;
				}
				if (Set.of("connectTimeout", "readTimeout", "writeTimeout", "callTimeout", "retryOnConnectionFailure",
						"followRedirects", "followSslRedirects").contains(method)) {
					call.getArguments().forEach(this::invalidateEscaped);
					return scope;
				}
			}
			// Passing a tracked mutable builder to an unknown helper invalidates subsequent assumptions.
			invalidate(scope);
			call.getArguments().forEach(this::invalidateEscaped);
			return Value.unknown();
		}

		private void invalidateEscaped(Expression expression) {
			expression.findAll(com.github.javaparser.ast.expr.NameExpr.class)
					.forEach(name -> invalidate(variables.get(name.getNameAsString())));
		}

		private void invalidate(Value value) {
			if (value != null && value.kind.endsWith("builder")) {
				value.complete = false;
				value.url = "";
				value.interceptors.clear();
			}
		}

		private String type(String name) {
			if (name.startsWith("retrofit2.") || name.startsWith("okhttp3.") || name.equals(api)) {
				return name;
			}
			String first = name.split("\\.", 2)[0];
			if (unit.findAll(com.github.javaparser.ast.body.TypeDeclaration.class).stream()
					.anyMatch(t -> t.getNameAsString().equals(first))) {
				return unit.getPackageDeclaration().map(p -> p.getNameAsString() + '.').orElse("") + name;
			}
			Set<String> candidates = new LinkedHashSet<>();
			unit.getImports().stream().filter(i -> !i.isStatic()).forEach(i -> {
				String imported = i.getNameAsString();
				if (!i.isAsterisk() && imported.endsWith('.' + first)) {
					candidates.add(imported + name.substring(first.length()));
				} else if (i.isAsterisk()) {
					candidates.add(imported + '.' + name);
				}
			});
			if (candidates.size() == 1) {
				return candidates.iterator().next();
			}
			String local = unit.getPackageDeclaration().map(p -> p.getNameAsString() + '.').orElse("") + name;
			return candidates.isEmpty() ? local : "";
		}
	}
}
