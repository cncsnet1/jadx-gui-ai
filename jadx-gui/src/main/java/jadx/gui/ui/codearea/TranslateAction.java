package jadx.gui.ui.codearea;

import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JRenameNode;
import jadx.gui.ui.action.ActionModel;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.ai.AIHttpUtils;
import jadx.gui.utils.ai.AIHttpUtils.StreamResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static jadx.gui.ui.action.ActionModel.AI_Translate;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.DefaultPrettyPrinter;

public final class TranslateAction extends JNodeAction {
	private static final Logger LOG = LoggerFactory.getLogger(TranslateAction.class);
	private final AIHttpUtils aiHttpUtils;
	private static final int MAX_TOKENS = 4096;
	private static final double TEMPERATURE = 0.7;
	private static final int MAX_CODE_LENGTH = 2000; // 每段代码的最大长度
	private static final int CONTEXT_LINES = 5; // 上下文行数
	private MainWindow mainWindow;

	public TranslateAction(CodeArea codeArea) {
		super(AI_Translate, codeArea);
		mainWindow = codeArea.getMainWindow();
		String apiUrl = mainWindow.getSettings().getAiApi();
		String apiKey = mainWindow.getSettings().getAiApiKey();

		if (apiUrl == null || apiUrl.trim().isEmpty() ||
			apiKey == null || apiKey.trim().isEmpty()) {
			LOG.warn("AI配置信息不完整");
			this.aiHttpUtils = null;
		} else {
			this.aiHttpUtils = new AIHttpUtils(apiUrl, apiKey);
		}
	}

	/**
	 * 处理代码块标记
	 */
	private String processCodeBlock(String code) {
		// 移除可能存在的代码块标记，包括多种可能的格式
		code = code.replaceAll("^```(?:java)?\\s*", "")
				  .replaceAll("```\\s*$", "")
				  .replaceAll("^`{3,}(?:java)?\\s*", "")
				  .replaceAll("`{3,}\\s*$", "");
		return code.trim();
	}

	/**
	 * 格式化代码
	 */
	private String formatCode(String code) {
		try {
			// 解析代码
			CompilationUnit cu = StaticJavaParser.parse(code);
			
			// 格式化代码
			DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
			String formattedCode = printer.print(cu);
			
			// 移除可能的多余空行
			formattedCode = formattedCode.replaceAll("(?m)^\\s*$\\n", "");
			
			return formattedCode;
		} catch (Exception e) {
			LOG.warn("代码格式化失败", e);
			return code;
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
			JOptionPane.showMessageDialog(codeArea, "请先选择要翻译的代码", "提示", JOptionPane.INFORMATION_MESSAGE);
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
		JDialog progressDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(codeArea), "正在翻译", true);
		progressDialog.setLayout(new BorderLayout(5, 5));

		// 创建主面板
		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// 创建顶部面板（进度信息）
		JPanel topPanel = new JPanel(new BorderLayout(5, 5));

		// 创建进度标签
		JLabel progressLabel = new JLabel("正在分析代码...");
		progressLabel.setForeground(new Color(75, 75, 75));
		topPanel.add(progressLabel, BorderLayout.NORTH);

		// 创建进度条
		JProgressBar progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		topPanel.add(progressBar, BorderLayout.CENTER);

		mainPanel.add(topPanel, BorderLayout.NORTH);

		// 创建中间面板（代码显示）
		JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

		// 创建代码显示区域
		JTextArea resultArea = new JTextArea();
		resultArea.setEditable(false);
		resultArea.setLineWrap(true);
		resultArea.setWrapStyleWord(true);
		resultArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
		resultArea.setBackground(new Color(245, 245, 245));
		resultArea.setMargin(new Insets(5, 5, 5, 5));

		JScrollPane scrollPane = new JScrollPane(resultArea);
		scrollPane.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createTitledBorder("翻译结果"),
			BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		centerPanel.add(scrollPane, BorderLayout.CENTER);
		mainPanel.add(centerPanel, BorderLayout.CENTER);

		// 创建底部面板（按钮）
		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		progressDialog.add(mainPanel);
		progressDialog.setSize(800, 600);
		progressDialog.setLocationRelativeTo(null);
		progressDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// 在新线程中执行翻译
		new Thread(() -> {
			try {
				// 重置AI工具状态
				aiHttpUtils.reset();

				// 构建系统提示词
				String systemPrompt = "你是一个专业的Java代码翻译和注释专家。请将以下Java代码翻译成中文，并添加详细的中文注释。\n\n" +
					"要求：\n" +
					"1. 保持代码的原有结构和功能不变\n" +
					"2. 添加清晰的中文注释，解释代码的功能和实现逻辑\n" +
					"3. 翻译变量名和方法名时保持其原有含义\n" +
					"4. 确保翻译后的代码仍然可以正常编译和运行\n" +
					"5. 保持代码格式规范，包括缩进和空行\n" +
					"6. 对于复杂的逻辑，添加详细的中文注释说明\n" +
					"7. 保持原有的代码风格和结构\n" +
					"8. 注意保持上下文的连贯性\n\n" +
					"请直接返回翻译后的代码，不要添加任何额外的解释或说明。";

				// 分段处理代码
				List<String> codeSegments = splitCodeWithContext(selectedText);
				StringBuffer translatedBuffer = new StringBuffer();

				// 更新UI显示正在翻译
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("正在翻译代码...");
					progressBar.setIndeterminate(false);
					progressBar.setMaximum(codeSegments.size());
					progressBar.setValue(0);
				});

				// 逐段翻译
				for (int i = 0; i < codeSegments.size(); i++) {
					final int currentSegment = i + 1;
					final int totalSegments = codeSegments.size();

					// 更新进度
					SwingUtilities.invokeLater(() -> {
						progressLabel.setText(String.format("正在翻译第 %d/%d 段代码...", currentSegment, totalSegments));
						progressBar.setValue(currentSegment);
					});

					List<Map<String, String>> messages = new ArrayList<>();
					messages.add(AIHttpUtils.createMessage("system", systemPrompt));

					// 添加上下文信息
					if (i > 0) {
						messages.add(AIHttpUtils.createMessage("system",
							"这是代码的第 " + currentSegment + " 段，请确保与前面的翻译保持一致性。"));
					}

					messages.add(AIHttpUtils.createMessage("user", codeSegments.get(i)));

					// 使用CountDownLatch等待当前段翻译完成
					final CountDownLatch segmentLatch = new CountDownLatch(1);
					final StringBuilder segmentBuffer = new StringBuilder();

					aiHttpUtils.createStreamingChatCompletionWithCallback(
						mainWindow.getSettings().getAiModel(),
						messages,
						TEMPERATURE,
						MAX_TOKENS,
						translatedText -> {
							segmentBuffer.append(translatedText);
							SwingUtilities.invokeLater(() -> {
								resultArea.setText(translatedBuffer.toString() + segmentBuffer.toString());
								resultArea.setCaretPosition(resultArea.getDocument().getLength());
							});
						}
					);

					// 等待当前段翻译完成
					try {
						segmentLatch.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("翻译被中断", e);
					}

					// 将当前段添加到最终结果中
					String segmentResult = segmentBuffer.toString();
					System.out.println("第 " + currentSegment + " 段翻译结果：\n" + segmentResult);
					translatedBuffer.append(segmentResult);
				}

				// 翻译完成后更新UI
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("翻译完成");
					progressBar.setValue(progressBar.getMaximum());

					// 添加确认按钮
					JButton confirmButton = new JButton("确认替换");
					confirmButton.addActionListener(e -> {
						try {
							// 处理代码块标记和格式化
							String finalResult = processCodeBlock(translatedBuffer.toString());
							finalResult = formatCode(finalResult);

							System.out.println("最终翻译结果：\n" + finalResult);

							int start = codeArea.getSelectionStart();
							int end = codeArea.getSelectionEnd();

							// 替换代码
							codeArea.getDocument().remove(start, end - start);
							codeArea.getDocument().insertString(start, finalResult, null);

							// 重新加载代码区域以保持关联功能
							if (codeArea.getNode() instanceof  JClass) {
								// 保存当前代码
								String currentCode = codeArea.getText();
								// 重新加载类以更新关联信息
								codeArea.refreshClass();
								// 恢复我们的修改
								codeArea.setText(currentCode);
							}

							// 格式化整个文件的代码
							String fullCode = codeArea.getText();
							String formattedCode = formatCode(fullCode);
							codeArea.setText(formattedCode);

							progressDialog.dispose();
						} catch (Exception ex) {
							LOG.error("替换代码时出错", ex);
							JOptionPane.showMessageDialog(progressDialog,
								"替换代码失败: " + ex.getMessage(),
								"错误",
								JOptionPane.ERROR_MESSAGE);
						}
					});

					// 添加取消按钮
					JButton cancelButton = new JButton("取消");
					cancelButton.addActionListener(e -> progressDialog.dispose());

					bottomPanel.add(cancelButton);
					bottomPanel.add(confirmButton);
				});

			} catch (Exception e) {
				LOG.error("翻译代码时出错", e);
				SwingUtilities.invokeLater(() -> {
					progressDialog.dispose();
					JOptionPane.showMessageDialog(codeArea,
						"翻译失败: " + e.getMessage(),
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
}
