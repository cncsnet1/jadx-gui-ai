package jadx.gui.ui.codearea;

import jadx.gui.treemodel.JNode;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.ai.AIHttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static jadx.gui.ui.action.ActionModel.AI_Audit;

/**
 * 安全审计菜单
 */
public final class AuditAction extends JNodeAction {
	private static final Logger LOG = LoggerFactory.getLogger(AuditAction.class);
	private final AIHttpUtils aiHttpUtils;
	private static final int MAX_TOKENS = 4096;
	private static final double TEMPERATURE = 0.3; // 降低温度以提高严谨性
	private static final int MAX_CODE_LENGTH = 4000;
	private static final int CONTEXT_LINES = 15; // 增加上下文行数
	private MainWindow mainWindow;
	private final Parser markdownParser;
	private final HtmlRenderer htmlRenderer;

	public AuditAction(CodeArea codeArea) {
		super(AI_Audit, codeArea);
		mainWindow = codeArea.getMainWindow();
		String apiUrl = mainWindow.getSettings().getAiApi();
		String apiKey = mainWindow.getSettings().getAiApiKey();

		// 初始化Markdown解析器
		this.markdownParser = Parser.builder().build();
		this.htmlRenderer = HtmlRenderer.builder().build();

		if (apiUrl == null || apiUrl.trim().isEmpty() ||
			apiKey == null || apiKey.trim().isEmpty()) {
			LOG.warn("AI配置信息不完整");
			this.aiHttpUtils = null;
		} else {
			this.aiHttpUtils = new AIHttpUtils(apiUrl, apiKey);
		}
	}

	/**
	 * 将Markdown转换为HTML
	 */
	private String markdownToHtml(String markdown) {
		try {
			Node document = markdownParser.parse(markdown);
			return htmlRenderer.render(document);
		} catch (Exception e) {
			LOG.error("Markdown转换失败", e);
			return markdown;
		}
	}

	/**
	 * 将代码分段，保持上下文
	 */
	private List<String> splitCodeWithContext(String code) {
		List<String> segments = new ArrayList<>();
		String[] lines = code.split("\n");
		int currentIndex = 0;

		while (currentIndex < lines.length) {
			StringBuilder segment = new StringBuilder();
			int segmentLength = 0;

			// 添加前文上下文
			for (int i = Math.max(0, currentIndex - CONTEXT_LINES); i < currentIndex; i++) {
				segment.append(lines[i]).append("\n");
				segmentLength += lines[i].length() + 1;
			}

			// 添加当前段落的代码
			while (currentIndex < lines.length && segmentLength < MAX_CODE_LENGTH) {
				String line = lines[currentIndex];
				if (segmentLength + line.length() + 1 > MAX_CODE_LENGTH) {
					break;
				}
				segment.append(line).append("\n");
				segmentLength += line.length() + 1;
				currentIndex++;
			}

			// 添加后文上下文
			for (int i = currentIndex; i < Math.min(lines.length, currentIndex + CONTEXT_LINES); i++) {
				segment.append(lines[i]).append("\n");
			}

			segments.add(segment.toString().trim());
		}

		return segments;
	}

	@Override
	public void runAction(JNode node) {
		CodeArea codeArea = getCodeArea();
		String selectedText = codeArea.getSelectedText();
		if (selectedText == null || selectedText.trim().isEmpty()) {
			JOptionPane.showMessageDialog(codeArea, "请先选择要审计的代码", "提示", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		// 检查AI配置
		if (aiHttpUtils == null) {
			JOptionPane.showMessageDialog(codeArea,
				"请先在设置中配置AI接口信息：\n1. AI接口地址\n2. API密钥",
				"配置缺失",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		// 创建进度对话框
		JDialog progressDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(codeArea), "正在安全审计", true);
		progressDialog.setLayout(new BorderLayout(5, 5));
		progressDialog.setResizable(true);  // 允许调整大小
		progressDialog.setMinimumSize(new Dimension(600, 400));  // 设置最小尺寸

		// 创建主面板
		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// 创建顶部面板（进度信息）
		JPanel topPanel = new JPanel(new BorderLayout(5, 5));
		topPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));  // 添加底部间距

		// 创建进度标签
		JLabel progressLabel = new JLabel("正在分析代码...");
		progressLabel.setForeground(new Color(75, 75, 75));
		progressLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));  // 添加底部间距
		topPanel.add(progressLabel, BorderLayout.NORTH);

		// 创建进度条
		JProgressBar progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setStringPainted(true);  // 显示进度文本
		progressBar.setString("准备中...");
		topPanel.add(progressBar, BorderLayout.CENTER);

		mainPanel.add(topPanel, BorderLayout.NORTH);

		// 创建中间面板（审计结果显示）
		JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

		// 创建支持Markdown的结果显示区域
		JEditorPane resultPane = new JEditorPane();
		resultPane.setEditable(false);
		resultPane.setContentType("text/html");
		resultPane.setFont(new Font("Monospaced", Font.PLAIN, 12));  // 调整字体大小
		resultPane.setBackground(new Color(245, 245, 245));
		resultPane.setMargin(new Insets(5, 5, 5, 5));

		// 设置HTML样式
		String style = "<style>" +
			"body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; " +
			"font-size: 12px; line-height: 1.4; color: #2c3e50; background-color: #ffffff; padding: 15px; }" +
			"h1 { color: #1a237e; font-size: 18px; margin-top: 1.2em; margin-bottom: 0.6em; border-bottom: 1px solid #e0e0e0; padding-bottom: 0.2em; }" +
			"h2 { color: #283593; font-size: 16px; margin-top: 1em; margin-bottom: 0.4em; }" +
			"h3 { color: #303f9f; font-size: 14px; margin-top: 0.8em; margin-bottom: 0.3em; }" +
			"code { background-color: #f8f9fa; padding: 1px 4px; border-radius: 3px; font-family: 'Consolas', 'Monaco', 'Courier New', monospace; " +
			"font-size: 11px; color: #e83e8c; border: 1px solid #e9ecef; }" +
			"pre { background-color: #f8f9fa; padding: 10px; border-radius: 4px; overflow-x: auto; border: 1px solid #e9ecef; " +
			"font-family: 'Consolas', 'Monaco', 'Courier New', monospace; font-size: 11px; line-height: 1.4; }" +
			"pre code { background-color: transparent; padding: 0; border: none; }" +
			"ul, ol { padding-left: 20px; margin: 0.8em 0; }" +
			"li { margin: 0.3em 0; line-height: 1.4; }" +
			"blockquote { border-left: 3px solid #3f51b5; margin: 1em 0; padding: 0.4em 0.8em; background-color: #f5f5f5; " +
			"color: #546e7a; font-style: italic; font-size: 11px; }" +
			"table { border-collapse: collapse; width: 100%; margin: 1em 0; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }" +
			"th, td { border: 1px solid #e0e0e0; padding: 8px; text-align: left; font-size: 11px; }" +
			"th { background-color: #f5f5f5; color: #1a237e; font-weight: 600; }" +
			"tr:nth-child(even) { background-color: #fafafa; }" +
			"tr:hover { background-color: #f5f5f5; }" +
			"p { margin: 0.8em 0; }" +
			"a { color: #3f51b5; text-decoration: none; border-bottom: 1px solid transparent; transition: border-color 0.2s; }" +
			"a:hover { border-bottom-color: #3f51b5; }" +
			"hr { border: none; border-top: 1px solid #e0e0e0; margin: 1.5em 0; }" +
			"</style>";

		resultPane.setText(style + "<body></body>");

		JScrollPane scrollPane = new JScrollPane(resultPane);
		scrollPane.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createTitledBorder("安全审计结果"),
			BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		centerPanel.add(scrollPane, BorderLayout.CENTER);
		mainPanel.add(centerPanel, BorderLayout.CENTER);

		// 创建底部面板（按钮）
		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));  // 添加顶部间距
		
		// 添加导出按钮
		JButton exportButton = new JButton("导出报告");
		exportButton.setEnabled(false);  // 初始状态禁用
		try {
			ImageIcon icon = new ImageIcon(getClass().getResource("/icons/export.png"));
			if (icon.getImageLoadStatus() == MediaTracker.COMPLETE) {
				exportButton.setIcon(icon);
			}
		} catch (Exception e) {
			LOG.debug("无法加载导出图标", e);
		}
		exportButton.addActionListener(e -> {
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setDialogTitle("导出审计报告");
			
			// 设置文件过滤器
			fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
				@Override
				public boolean accept(File f) {
					return f.isDirectory() || f.getName().toLowerCase().endsWith(".html") || 
						   f.getName().toLowerCase().endsWith(".md");
				}
				
				@Override
				public String getDescription() {
					return "HTML或Markdown文件 (*.html, *.md)";
				}
			});
			
			// 设置默认文件名
			String defaultFileName = "安全审计报告_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".html";
			fileChooser.setSelectedFile(new File(defaultFileName));
			
			if (fileChooser.showSaveDialog(progressDialog) == JFileChooser.APPROVE_OPTION) {
				File file = fileChooser.getSelectedFile();
				String fileName = file.getName().toLowerCase();
				
				try {
					String content = resultPane.getText();
					// 移除style标签，因为导出时不需要
					content = content.replaceAll("<style>.*?</style>", "");
					
					if (fileName.endsWith(".md")) {
						// 将HTML转换为Markdown
						content = convertHtmlToMarkdown(content);
					}
					
					// 写入文件
					try (FileWriter writer = new FileWriter(file)) {
						writer.write(content);
					}
					
					JOptionPane.showMessageDialog(progressDialog,
						"报告已成功导出到：" + file.getAbsolutePath(),
						"导出成功",
						JOptionPane.INFORMATION_MESSAGE);
						
				} catch (IOException ex) {
					LOG.error("导出报告失败", ex);
					JOptionPane.showMessageDialog(progressDialog,
						"导出报告失败：" + ex.getMessage(),
						"导出失败",
						JOptionPane.ERROR_MESSAGE);
				}
			}
		});
		
		// 添加关闭按钮
		JButton closeButton = new JButton("关闭");
		closeButton.addActionListener(e -> progressDialog.dispose());
		
		bottomPanel.add(exportButton);
		bottomPanel.add(closeButton);
		
		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		progressDialog.add(mainPanel);
		progressDialog.setSize(800, 600);
		progressDialog.setLocationRelativeTo(null);
		progressDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// 在新线程中执行安全审计
		new Thread(() -> {
			try {
				// 重置AI工具状态
				aiHttpUtils.reset();

				// 构建系统提示词
				String systemPrompt = "你是一个专业的Java代码安全审计专家，擅长识别代码中的安全漏洞和风险。请对以下Java代码进行全面的安全审计分析。\n\n" +
					"审计范围说明：\n" +
					"1. 当前仅对单个文件进行审计，可能存在以下局限性：\n" +
					"   - 无法完整评估跨文件调用的安全性\n" +
					"   - 无法评估整体架构设计的安全性\n" +
					"   - 无法评估配置文件和资源文件的安全性\n" +
					"   - 无法评估第三方依赖的安全性\n" +
					"   - 无法评估运行时环境的安全性\n\n" +
					"2. 建议进行更全面的安全审计：\n" +
					"   - 对整个项目进行完整审计\n" +
					"   - 评估所有相关依赖的安全性\n" +
					"   - 进行渗透测试和漏洞扫描\n" +
					"   - 评估运行时环境的安全性\n" +
					"   - 进行安全配置审查\n\n" +
					"审计要求：\n" +
					"1. 识别代码中可能存在的安全漏洞和风险\n" +
					"2. 分析代码中的权限控制问题\n" +
					"3. 检查数据验证和输入处理\n" +
					"4. 识别潜在的SQL注入、XSS等安全问题\n" +
					"5. 检查加密算法的使用是否安全\n" +
					"6. 分析代码中的敏感信息处理\n" +
					"7. 检查异常处理的安全性\n" +
					"8. 识别不安全的反序列化\n" +
					"9. 检查不安全的文件操作\n" +
					"10. 分析不安全的反射使用\n" +
					"11. 检查不安全的动态代码执行\n" +
					"12. 识别不安全的线程处理\n" +
					"13. 分析不安全的日志记录\n" +
					"14. 检查不安全的配置管理\n" +
					"15. 识别不安全的依赖使用\n\n" +
					"审计原则：\n" +
					"1. 严格遵循OWASP Top 10安全风险\n" +
					"2. 参考CWE/SANS Top 25危险软件错误\n" +
					"3. 确保每个发现的问题都有明确的证据支持\n" +
					"4. 避免误报，只报告确定的安全问题\n" +
					"5. 提供具体的CVE编号和修复方案\n" +
					"6. 对于跨文件调用，标注需要进一步评估的部分\n" +
					"7. 对于依赖相关的问题，建议进行完整的依赖审计\n\n" +
					"请按以下格式输出审计结果：\n" +
					"1. 审计范围说明\n" +
					"   - 当前审计范围\n" +
					"   - 审计局限性\n" +
					"   - 建议的后续审计步骤\n\n" +
					"2. 安全风险概述\n" +
					"   - 总体风险等级（高/中/低）\n" +
					"   - 主要安全威胁\n" +
					"   - 潜在影响\n\n" +
					"3. 详细问题列表\n" +
					"   对每个发现的问题，请提供：\n" +
					"   - 问题描述\n" +
					"   - 风险等级（高/中/低）\n" +
					"   - 相关CVE编号（如果适用）\n" +
					"   - 问题代码位置\n" +
					"   - 详细的技术分析\n" +
					"   - 具体的修复建议\n" +
					"   - 是否需要进一步评估（如跨文件调用）\n\n" +
					"4. 总体安全评估\n" +
					"   - 代码安全性评分（0-100）\n" +
					"   - 主要安全优势\n" +
					"   - 主要安全劣势\n" +
					"   - 需要进一步评估的方面\n\n" +
					"5. 改进建议\n" +
					"   - 短期改进措施\n" +
					"   - 长期安全加固建议\n" +
					"   - 安全最佳实践建议\n" +
					"   - 建议的后续审计步骤\n\n" +
					"请确保分析严谨、全面，并尽可能关联到具体的CVE编号。对于每个发现的问题，都要提供详细的技术分析和具体的修复方案。同时，请明确指出需要进一步评估的部分。";

				// 分段处理代码
				List<String> codeSegments = splitCodeWithContext(selectedText);
				
				// 更新UI显示正在收集代码
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("正在收集代码段...");
					progressBar.setIndeterminate(false);
					progressBar.setMaximum(codeSegments.size());
					progressBar.setValue(0);
					progressBar.setString("0/" + codeSegments.size());
				});

				// 收集所有代码段
				StringBuilder allCode = new StringBuilder();
				for (int i = 0; i < codeSegments.size(); i++) {
					final int currentSegment = i + 1;
					
					// 更新进度
					SwingUtilities.invokeLater(() -> {
						progressLabel.setText(String.format("正在收集第 %d/%d 段代码...", currentSegment, codeSegments.size()));
						progressBar.setValue(currentSegment);
						progressBar.setString(String.format("%d/%d", currentSegment, codeSegments.size()));
					});

					allCode.append("代码段 ").append(currentSegment).append(":\n");
					allCode.append(codeSegments.get(i)).append("\n\n");
				}

				// 更新UI显示正在审计
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("正在进行安全审计分析...");
					progressBar.setIndeterminate(true);
					progressBar.setString("分析中...");
				});

				// 构建消息列表
				List<Map<String, String>> messages = new ArrayList<>();
				messages.add(AIHttpUtils.createMessage("system", systemPrompt));
				messages.add(AIHttpUtils.createMessage("user", allCode.toString()));

				final StringBuilder auditBuffer = new StringBuilder();

				// 直接调用方法，它会自动处理完成状态
				aiHttpUtils.createStreamingChatCompletionWithCallback(
					mainWindow.getSettings().getAiModel(),
					messages,
					TEMPERATURE,
					MAX_TOKENS,
					auditText -> {
						auditBuffer.append(auditText);
						SwingUtilities.invokeLater(() -> {
							String htmlContent = markdownToHtml(auditBuffer.toString());
							resultPane.setText(style + "<body>" + htmlContent + "</body>");
							progressBar.setString("正在生成报告...");
						});
					}
				);

				// 检查是否有错误发生
				if (auditBuffer.length() == 0) {
					throw new RuntimeException("审计结果为空，请重试");
				}

				// 审计完成后更新UI
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("安全审计完成");
					progressBar.setIndeterminate(false);
					progressBar.setValue(progressBar.getMaximum());
					progressBar.setString("完成");
					exportButton.setEnabled(true);  // 确保导出按钮可用
				});

			} catch (Exception e) {
				LOG.error("安全审计代码时出错", e);
				SwingUtilities.invokeLater(() -> {
					progressDialog.dispose();
					JOptionPane.showMessageDialog(codeArea,
						"安全审计失败: " + e.getMessage(),
						"错误",
						JOptionPane.ERROR_MESSAGE);
				});
			}
		}).start();

		progressDialog.setVisible(true);
	}

	@Override
	public boolean isActionEnabled(JNode node) {
		return true;
	}

	/**
	 * 将HTML转换为Markdown格式
	 */
	private String convertHtmlToMarkdown(String html) {
		// 移除HTML标签，保留文本内容
		html = html.replaceAll("<[^>]*>", "");
		
		// 处理标题
		html = html.replaceAll("h1", "# ");
		html = html.replaceAll("h2", "## ");
		html = html.replaceAll("h3", "### ");
		
		// 处理列表
		html = html.replaceAll("<li>", "- ");
		
		// 处理代码块
		html = html.replaceAll("<pre>", "```\n");
		html = html.replaceAll("</pre>", "\n```");
		
		// 处理引用
		html = html.replaceAll("<blockquote>", "> ");
		html = html.replaceAll("</blockquote>", "");
		
		// 处理表格
		html = html.replaceAll("<table>", "");
		html = html.replaceAll("</table>", "");
		html = html.replaceAll("<tr>", "");
		html = html.replaceAll("</tr>", "");
		html = html.replaceAll("<th>", "| ");
		html = html.replaceAll("</th>", " |");
		html = html.replaceAll("<td>", "| ");
		html = html.replaceAll("</td>", " |");
		
		// 清理多余的空行
		html = html.replaceAll("\n\\s*\n", "\n\n");
		
		return html.trim();
	}
}
