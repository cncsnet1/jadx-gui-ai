package jadx.gui.ai.analysis;

import java.util.Map;

import jadx.gui.utils.NLS;

/** Small navigation vocabulary kept separate from the analysis engine's messages. */
final class WorkspaceText {
	private static final Map<String, String[]> TEXT = Map.ofEntries(
			Map.entry("title", new String[] { "应用工作台", "Application workbench" }),
			Map.entry("overview", new String[] { "应用概览", "Application overview" }),
			Map.entry("communication", new String[] { "接口与通信", "APIs & communication" }),
			Map.entry("code", new String[] { "关键代码", "Key code" }),
			Map.entry("history", new String[] { "继续 AI 分析", "Continue AI analysis" }),
			Map.entry("request_direction", new String[] { "追踪这条请求的入口、参数、认证、加密签名和网络出口，列出来源与尚未确认的连接。",
					"Trace this request's entry, parameters, authentication, encryption/signing and network outlet; cite sources and unresolved links." }),
			Map.entry("back", new String[] { "返回应用工作台", "Back to application" }),
			Map.entry("investigation_hint", new String[] { "分析记录与证据随项目保存", "Analysis and evidence are saved with the project" }),
			Map.entry("options_show", new String[] { "自定义 AI 分析", "Custom AI analysis" }),
			Map.entry("options_hide", new String[] { "收起分析选项", "Hide analysis options" }));

	private WorkspaceText() {
	}

	static String str(String key) {
		String[] value = TEXT.get(key);
		return value == null ? key : value[NLS.currentLocale() != null && NLS.currentLocale().get().getLanguage().equals("zh") ? 0 : 1];
	}
}
