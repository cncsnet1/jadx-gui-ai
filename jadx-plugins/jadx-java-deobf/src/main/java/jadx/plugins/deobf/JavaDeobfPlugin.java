package jadx.plugins.deobf;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.charset.StandardCharsets;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;

public class JavaDeobfPlugin implements JadxPlugin {
	@Override
	public JadxPluginInfo getPluginInfo() {
		return new JadxPluginInfo("java-deobf", "Java Deobfuscation",
				"Preprocess JAR/CLASS with a local Java Deobfuscator engine (ZKM, Allatori, Stringer)");
	}

	@Override
	public void init(JadxPluginContext context) {
		JavaDeobfOptions options = new JavaDeobfOptions();
		context.registerOptions(options);
		JavaDeobfReport report = new JavaDeobfReport();
		JavaDeobfPreprocessor processor = new JavaDeobfPreprocessor(options, context.files().getPluginTempDir(), report);
		context.addInputPreprocessor(processor);
		context.getDecompiler().addCloseable(processor);
		context.registerInputsHashSupplier(() -> {
			StringBuilder state = new StringBuilder("java-deobf-v1:").append(processor.getResultHash());
			options.getOptionsDescriptions().forEach(option -> state.append('|').append(option.name())
					.append('=').append(context.getArgs().getPluginOptions().get(option.name())));
			return JavaArchive.digest(state.toString().getBytes(StandardCharsets.UTF_8));
		});
		JadxGuiContext gui = context.getGuiContext();
		if (gui != null) {
			gui.addMenuAction("Java Deobfuscation Report / Java 反混淆报告", () -> gui.uiRun(() -> showReport(gui, report)));
		}
	}

	private static void showReport(JadxGuiContext gui, JavaDeobfReport report) {
		JDialog dialog = new JDialog(gui.getMainFrame(), "Java Deobfuscation Report / Java 反混淆报告", false);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		JTextArea text = new JTextArea(report.getText());
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(text);
		scroll.setPreferredSize(new Dimension(850, 550));
		dialog.add(scroll, BorderLayout.CENTER);
		JPanel buttons = new JPanel();
		JButton copy = new JButton("Copy / 复制");
		copy.addActionListener(event -> gui.copyToClipboard(text.getText()));
		buttons.add(copy);
		JButton close = new JButton("Close / 关闭");
		close.addActionListener(event -> dialog.dispose());
		buttons.add(close);
		dialog.add(buttons, BorderLayout.SOUTH);
		dialog.pack();
		dialog.setLocationRelativeTo(gui.getMainFrame());
		dialog.setVisible(true);
	}
}
