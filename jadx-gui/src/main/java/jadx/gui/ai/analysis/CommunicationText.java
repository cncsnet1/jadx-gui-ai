package jadx.gui.ai.analysis;

import java.util.Locale;
import java.util.Map;

import jadx.gui.utils.NLS;

final class CommunicationText {
	private static final Map<String, String[]> TEXT = Map.ofEntries(
			Map.entry("entry", new String[] { "1 调用入口", "1 Callers" }),
			Map.entry("parameters", new String[] { "2 参数", "2 Parameters" }),
			Map.entry("crypto", new String[] { "3 加密与签名", "3 Crypto & signing" }),
			Map.entry("outlet", new String[] { "4 网络出口", "4 Network client" }),
			Map.entry("traffic", new String[] { "5 实际流量", "5 HTTP traffic" }),
			Map.entry("select", new String[] { "选择一个接口，沿通信过程查看已有证据。", "Select an endpoint to follow its communication evidence." }),
			Map.entry("inspect", new String[] { "追踪通信链路", "Trace communication" }),
			Map.entry("cached", new String[] { "查看已追踪链路", "View saved trace" }),
			Map.entry("not_traced", new String[] { "尚未追踪：先看声明和来源，再点击“追踪通信链路”补充调用者与客户端关系。",
					"Not traced yet. Declarations and sources are available; trace communication to inspect callers and client bindings." }),
			Map.entry("traced",
					new String[] { "已载入保存的静态链路；可逐段查看证据和仍未确认的部分。",
							"Saved static trace loaded. Explore each stage, its evidence and remaining gaps." }),
			Map.entry("evidence", new String[] { "查看证据内容", "View evidence" }),
			Map.entry("crypto_empty", new String[] {
					"当前接口来源和已追踪调用者中，尚未关联到加密或签名调用。\n\n这不代表未使用加密：包装方法、动态调用、JS 和 native 代码可能尚未覆盖。可继续 AI 追查，或从关键代码页检查其他线索。",
					"No crypto or signing call is linked to this endpoint's sources or traced callers.\n\nThis does not establish absence of encryption: wrappers, dynamic dispatch, JS and native code may remain outside coverage. Continue investigating or inspect other leads in Key code." }),
			Map.entry("crypto_boundary", new String[] { "以下调用出现在接口来源、已关联客户端或已追踪调用者的同一方法内。尚未证明请求参数流入这些算法，也未恢复密钥、IV 或完整数据流。",
					"These calls occur in methods from endpoint sources, linked clients or traced callers. Request data reaching these algorithms, keys, IVs and complete data flow have not been established." }),
			Map.entry("outlet_empty", new String[] { "尚未建立此接口与具体网络客户端的关系。已知地址来自静态声明或字符串；实际 host、代理和拦截器仍需追踪或流量验证。",
					"This endpoint has not been linked to a specific network client. Known addresses come from static declarations or strings; runtime hosts, proxies and interceptors still require tracing or traffic evidence." }),
			Map.entry("static_only",
					new String[] { "静态观察，不代表请求已执行或服务可访问。", "Static observation; does not establish execution or service availability." }),
			Map.entry("traffic_unavailable", new String[] { "实际流量需要从应用工作台连接 Burp 后读取。",
					"Read observed traffic by connecting Burp from the application workbench." }));

	private CommunicationText() {
	}

	static String str(String key) {
		Locale locale = NLS.currentLocale() == null ? Locale.ENGLISH : NLS.currentLocale().get();
		return TEXT.get(key)[locale.getLanguage().equals("zh") ? 0 : 1];
	}
}
