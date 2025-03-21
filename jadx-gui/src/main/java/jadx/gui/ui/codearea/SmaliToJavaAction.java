package jadx.gui.ui.codearea;

import jadx.gui.treemodel.JNode;
import jadx.gui.ui.action.ActionModel;
import jadx.gui.ui.MainWindow;
import jadx.gui.utils.ai.AIHttpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SmaliToJavaAction extends JNodeAction {
	private static final Logger LOG = LoggerFactory.getLogger(SmaliToJavaAction.class);
	private final AIHttpUtils aiHttpUtils;
	private static final int MAX_TOKENS = 4096;
	private static final double TEMPERATURE = 0.7;
	private static final int MAX_CODE_LENGTH = 4000;
	private static final int CONTEXT_LINES = 15;
	private MainWindow mainWindow;

	public SmaliToJavaAction(CodeArea codeArea) {
		super(ActionModel.CODE_COMMENT, codeArea);
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
	 * 将代码分段，保持上下文
	 */
	private List<String> splitCodeWithContext(String code) {
		List<String> segments = new ArrayList<>();
		String[] lines = code.split("\n");
		int currentIndex = 0;

		while (currentIndex < lines.length) {
			StringBuilder segment = new StringBuilder();
			int segmentLength = 0;
			int startIndex = currentIndex;

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

			if (segment.length() > 0) {
				segments.add(segment.toString().trim());
			}

			if (currentIndex == startIndex) {
				currentIndex++;
			}
		}

		return segments;
	}

	@Override
	public void runAction(JNode node) {
		CodeArea codeArea = getCodeArea();
		String selectedText = codeArea.getSelectedText();
		if (selectedText == null || selectedText.trim().isEmpty()) {
			JOptionPane.showMessageDialog(codeArea, "请先选择要转换的Smali代码", "提示", JOptionPane.INFORMATION_MESSAGE);
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
		JDialog progressDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(codeArea), "正在转换", true);
		progressDialog.setLayout(new BorderLayout(5, 5));
		progressDialog.setResizable(true);
		progressDialog.setMinimumSize(new Dimension(600, 400));

		// 创建主面板
		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		// 创建顶部面板
		JPanel topPanel = new JPanel(new BorderLayout(5, 5));
		topPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

		JLabel progressLabel = new JLabel("正在分析Smali代码...");
		progressLabel.setForeground(new Color(75, 75, 75));
		progressLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		topPanel.add(progressLabel, BorderLayout.NORTH);

		JProgressBar progressBar = new JProgressBar();
		progressBar.setIndeterminate(true);
		progressBar.setStringPainted(true);
		progressBar.setString("准备中...");
		topPanel.add(progressBar, BorderLayout.CENTER);

		mainPanel.add(topPanel, BorderLayout.NORTH);

		// 创建中间面板
		JPanel centerPanel = new JPanel(new BorderLayout(5, 5));

		JTextArea resultArea = new JTextArea();
		resultArea.setEditable(false);
		resultArea.setLineWrap(true);
		resultArea.setWrapStyleWord(true);
		resultArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
		resultArea.setBackground(new Color(245, 245, 245));
		resultArea.setMargin(new Insets(5, 5, 5, 5));

		JScrollPane scrollPane = new JScrollPane(resultArea);
		scrollPane.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder("转换结果"),
				BorderFactory.createEmptyBorder(5, 5, 5, 5)));

		centerPanel.add(scrollPane, BorderLayout.CENTER);
		mainPanel.add(centerPanel, BorderLayout.CENTER);

		// 创建底部面板
		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
		bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));

		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		progressDialog.add(mainPanel);
		progressDialog.setSize(800, 600);
		progressDialog.setLocationRelativeTo(null);
		progressDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// 在新线程中执行转换
		new Thread(() -> {
			try {
				aiHttpUtils.reset();

				// 构建系统提示词
				String systemPrompt = "你是一个专业的Smali代码转换专家。请将以下Smali代码转换为等效的Java代码。\n\n" +
						"要求：\n" +
						"1. 保持代码的原有功能和逻辑不变\n" +
						"2. 生成规范的Java代码，包括适当的类、方法和变量声明\n" +
						"3. 正确处理Smali特有的语法和指令\n" +
						"4. 添加必要的import语句\n" +
						"5. 保持代码格式规范，包括缩进和空行\n" +
						"6. 对于复杂的逻辑，添加适当的注释\n" +
						"7. 确保转换后的代码可以正常编译\n\n" +
						"请直接返回转换后的Java代码，不要添加任何额外的解释或说明。";

				// 分段处理代码
				List<String> codeSegments = splitCodeWithContext(selectedText);

				// 更新UI显示正在转换
				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("正在转换代码...");
					progressBar.setIndeterminate(false);
					progressBar.setMaximum(codeSegments.size());
					progressBar.setValue(0);
					progressBar.setString("0/" + codeSegments.size());
				});

				// 逐段转换
				StringBuilder convertedBuffer = new StringBuilder();
				for (int i = 0; i < codeSegments.size(); i++) {
					final int currentSegment = i + 1;
					final int totalSegments = codeSegments.size();

					SwingUtilities.invokeLater(() -> {
						progressLabel.setText(String.format("正在转换第 %d/%d 段代码...", currentSegment, totalSegments));
						progressBar.setValue(currentSegment);
						progressBar.setString(String.format("%d/%d", currentSegment, totalSegments));
					});

					List<Map<String, String>> messages = new ArrayList<>();
					messages.add(AIHttpUtils.createMessage("system", systemPrompt));

					if (i > 0) {
						messages.add(AIHttpUtils.createMessage("system",
								"这是代码的第 " + currentSegment + " 段，请确保与前面的转换保持一致性。"));
					}

					messages.add(AIHttpUtils.createMessage("user", codeSegments.get(i)));

					final CountDownLatch segmentLatch = new CountDownLatch(1);
					final StringBuilder segmentBuffer = new StringBuilder();

					aiHttpUtils.createStreamingChatCompletionWithCallback(
							mainWindow.getSettings().getAiModel(),
							messages,
							TEMPERATURE,
							MAX_TOKENS,
							convertedText -> {
								segmentBuffer.append(convertedText);
								SwingUtilities.invokeLater(() -> {
									resultArea.setText(convertedBuffer.toString() + segmentBuffer.toString());
									resultArea.setCaretPosition(resultArea.getDocument().getLength());
									progressBar.setString(String.format("正在处理第 %d/%d 段...", currentSegment, totalSegments));
								});
							}
					);

					try {
						if (!segmentLatch.await(10, TimeUnit.SECONDS)) {
							LOG.warn("转换超时，继续处理下一段");
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("转换被中断", e);
					}

					String segmentResult = segmentBuffer.toString();
					convertedBuffer.append(segmentResult);
				}

				if (convertedBuffer.length() == 0) {
					throw new RuntimeException("转换结果为空，请重试");
				}

				SwingUtilities.invokeLater(() -> {
					progressLabel.setText("转换完成");
					progressBar.setIndeterminate(false);
					progressBar.setValue(progressBar.getMaximum());
					progressBar.setString("完成");

					JButton confirmButton = new JButton("确认替换");
					confirmButton.addActionListener(e -> {
						try {
							String finalResult = convertedBuffer.toString().trim();
							int start = codeArea.getSelectionStart();
							int end = codeArea.getSelectionEnd();

							codeArea.getDocument().remove(start, end - start);
							codeArea.getDocument().insertString(start, finalResult, null);

							progressDialog.dispose();
						} catch (Exception ex) {
							LOG.error("替换代码时出错", ex);
							JOptionPane.showMessageDialog(progressDialog,
									"替换代码失败: " + ex.getMessage(),
									"错误",
									JOptionPane.ERROR_MESSAGE);
						}
					});

					JButton cancelButton = new JButton("取消");
					cancelButton.addActionListener(e -> progressDialog.dispose());

					bottomPanel.add(cancelButton);
					bottomPanel.add(confirmButton);
				});

			} catch (Exception e) {
				LOG.error("转换代码时出错", e);
				SwingUtilities.invokeLater(() -> {
					progressDialog.dispose();
					JOptionPane.showMessageDialog(codeArea,
							"转换失败: " + e.getMessage(),
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
