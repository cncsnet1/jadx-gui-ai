/*
 * Created by JFormDesigner on Sun Mar 16 01:19:20 CST 2025
 */

package jadx.gui.ui.dialog;



import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Cursor;
import java.awt.Desktop;
import java.net.URI;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.Color;

import jadx.gui.settings.JadxSettings;




/**
 * @author Administrator
 */
public class AiSettingDialog extends JDialog {

	private final JadxSettings settings;
    public AiSettingDialog(Window owner,JadxSettings settings) {
        super(owner);
		this.settings = settings;
        initComponents();
		//初始化赋值

		settings.loadWindowPos(this);

		apiUrlField.setText(settings.getAiApi()==null?"":settings.getAiApi());
		apiKeyField.setText(settings.getAiApiKey()==null?"":settings.getAiApiKey());
		modelField.setText(settings.getAiModel()==null?"":settings.getAiModel());
		settings.setAiMaxTokens("4096");
		maxTokenHelpText.setText("固定使用4096 Token，此值已针对代码分析优化");
    }

	private void button1MouseClicked(MouseEvent e) {
		// TODO add your code here

		settings.setAiApi(apiUrlField.getText()==null?"":apiUrlField.getText());
		settings.setAiApiKey(apiKeyField.getText()==null?"":apiKeyField.getText());
		settings.setAiModel(modelField.getText()==null?"":modelField.getText());
		settings.setAiMaxTokens("4096");
		settings.saveWindowPos(this);
		settings.sync();

		this.dispose();

	}

	private void button2MouseClicked(MouseEvent e) {
		this.dispose();
	}

    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
		panel1 = new JPanel();
		label1 = new JLabel();
		apiUrlField = new JTextField();
		urlHelpText = new JTextArea();
		label2 = new JLabel();
		apiKeyField = new JTextField();
		keyHelpText = new JTextArea();
		label3 = new JLabel();
		modelField = new JTextField();
		modelHelpText = new JTextArea();
		label4 = new JLabel();
		maxTokenHelpText = new JTextArea();
		panel2 = new JPanel();
		button1 = new JButton();
		button2 = new JButton();

		//======== this ========
		setTitle("AI配置");
		setResizable(true);
		setSize(new Dimension(1000, 800));
		setMinimumSize(new Dimension(900, 700));
		var contentPane = getContentPane();
		contentPane.setLayout(new GridBagLayout());
		((GridBagLayout)contentPane.getLayout()).columnWeights = new double[] {1.0};
		((GridBagLayout)contentPane.getLayout()).rowWeights = new double[] {1.0, 0.0};

		//======== panel1 ========
		{
			panel1.setBorder(new EmptyBorder(30, 30, 20, 30));
			panel1.setPreferredSize(new Dimension(800, 600));
			panel1.setLayout(new GridBagLayout());

			//---- label1 ----
			label1.setText("API服务器地址:");
			label1.setFont(label1.getFont().deriveFont(label1.getFont().getStyle() | Font.BOLD));
			panel1.add(label1, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 5, 10, 15), 0, 0));

			//---- apiUrlField ----
			apiUrlField.setPreferredSize(new Dimension(700, 32));
			apiUrlField.setToolTipText("输入API服务器地址，例如: https://api.openai.com/v1");
			panel1.add(apiUrlField, new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 5, 10, 15), 0, 0));

			//---- urlHelpText ----
			urlHelpText.setText("支持的平台: 硅基流动（首选）, OpenAI, 火山引擎, 阿里云\n\n请输入完整的API服务器地址，包含https://\n示例：\n硅基流动: https://api.guiji.ai/v1\nOpenAI: https://api.openai.com/v1\n阿里云: https://dashscope.aliyuncs.com/v1\n火山引擎: https://open.volcengineapi.com/v1");
			urlHelpText.setFont(urlHelpText.getFont().deriveFont(urlHelpText.getFont().getSize() - 2f));
			urlHelpText.setBackground(new Color(245, 245, 245));
			urlHelpText.setBorder(new LineBorder(new Color(200, 200, 200)));
			urlHelpText.setEditable(false);
			urlHelpText.setLineWrap(true);
			urlHelpText.setWrapStyleWord(true);
			panel1.add(urlHelpText, new GridBagConstraints(1, 1, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 8, 15, 0), 0, 0));

			//---- label2 ----
			label2.setText("API Key:");
			label2.setFont(label2.getFont().deriveFont(label2.getFont().getStyle() | Font.BOLD));
			panel1.add(label2, new GridBagConstraints(0, 2, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- apiKeyField ----
			apiKeyField.setPreferredSize(new Dimension(700, 30));
			apiKeyField.setToolTipText("输入您的API密钥");
			panel1.add(apiKeyField, new GridBagConstraints(1, 2, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- keyHelpText ----
			keyHelpText.setText("在API提供商处获取的密钥，请妥善保管\n获取方式：\n硅基流动: https://console.guiji.ai/api-keys\nOpenAI: https://platform.openai.com/api-keys\n阿里云: https://dashscope.console.aliyun.com/apiKey\n火山引擎: https://console.volcengine.com/iam/keymanage/");
			keyHelpText.setFont(keyHelpText.getFont().deriveFont(keyHelpText.getFont().getSize() - 2f));
			keyHelpText.setBackground(new Color(245, 245, 245));
			keyHelpText.setBorder(new LineBorder(new Color(200, 200, 200)));
			keyHelpText.setEditable(false);
			keyHelpText.setLineWrap(true);
			keyHelpText.setWrapStyleWord(true);
			panel1.add(keyHelpText, new GridBagConstraints(1, 3, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 15, 0), 0, 0));

			//---- label3 ----
			label3.setText("模型名称:");
			label3.setFont(label3.getFont().deriveFont(label3.getFont().getStyle() | Font.BOLD));
			panel1.add(label3, new GridBagConstraints(0, 4, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- modelField ----
			modelField.setPreferredSize(new Dimension(700, 30));
			modelField.setToolTipText("输入要使用的AI模型名称");
			panel1.add(modelField, new GridBagConstraints(1, 4, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- modelHelpText ----
			modelHelpText.setText("推荐使用授权code类模型: Qwen2.5-Coder-32B-Instruct, gpt-4, qwen-max, skylark-chat等\n\n各平台支持的模型示例：\n硅基流动: Qwen2.5-Coder-32B-Instruct (推荐使用授权code类模型)\nOpenAI: gpt-4-turbo-preview, gpt-3.5-turbo\n阿里云: qwen-max, qwen-plus\n火山引擎: skylark-chat, skylark-pro\n\n注意：请勿使用r1类逻辑模型，可能会导致分析结果不准确");
			modelHelpText.setFont(modelHelpText.getFont().deriveFont(modelHelpText.getFont().getSize() - 2f));
			modelHelpText.setBackground(new Color(245, 245, 245));
			modelHelpText.setBorder(new LineBorder(new Color(200, 200, 200)));
			modelHelpText.setEditable(false);
			modelHelpText.setLineWrap(true);
			modelHelpText.setWrapStyleWord(true);
			panel1.add(modelHelpText, new GridBagConstraints(1, 5, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 15, 0), 0, 0));

			//---- label4 ----
			label4.setText("最大Token:");
			label4.setFont(label4.getFont().deriveFont(label4.getFont().getStyle() | Font.BOLD));
			panel1.add(label4, new GridBagConstraints(0, 6, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- maxTokenHelpText ----
			maxTokenHelpText.setText("固定使用4096 Token，此值已针对代码分析优化");
			maxTokenHelpText.setFont(maxTokenHelpText.getFont().deriveFont(maxTokenHelpText.getFont().getSize() - 2f));
			maxTokenHelpText.setBackground(new Color(245, 245, 245));
			maxTokenHelpText.setBorder(new LineBorder(new Color(200, 200, 200)));
			maxTokenHelpText.setEditable(false);
			maxTokenHelpText.setLineWrap(true);
			maxTokenHelpText.setWrapStyleWord(true);
			panel1.add(maxTokenHelpText, new GridBagConstraints(1, 6, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));
		}
		contentPane.add(panel1, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
			GridBagConstraints.CENTER, GridBagConstraints.BOTH,
			new Insets(0, 0, 20, 0), 0, 0));

		//======== panel2 ========
		{
			panel2.setBorder(new EmptyBorder(5, 0, 15, 0));
			panel2.setLayout(new FlowLayout(FlowLayout.CENTER, 25, 10));

			//---- button1 ----
			button1.setText("保存");
			button1.setPreferredSize(new Dimension(100, 36));
			button1.setFont(button1.getFont().deriveFont(button1.getFont().getStyle() | Font.BOLD));
			button1.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					button1MouseClicked(e);
				}
			});
			panel2.add(button1);

			//---- button2 ----
			button2.setText("关闭");
			button2.setPreferredSize(new Dimension(100, 36));
			button2.setFont(button2.getFont().deriveFont(button2.getFont().getStyle() | Font.BOLD));
			button2.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					button2MouseClicked(e);
				}
			});
			panel2.add(button2);
		}
		contentPane.add(panel2, new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0,
			GridBagConstraints.CENTER, GridBagConstraints.BOTH,
			new Insets(0, 0, 0, 0), 0, 0));
		pack();
		setLocationRelativeTo(getOwner());
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
	private JPanel panel1;
	private JLabel label1;
	private JTextField apiUrlField;
	private JTextArea urlHelpText;
	private JLabel label2;
	private JTextField apiKeyField;
	private JTextArea keyHelpText;
	private JLabel label3;
	private JTextField modelField;
	private JTextArea modelHelpText;
	private JLabel label4;
	private JTextArea maxTokenHelpText;
	private JPanel panel2;
	private JButton button1;
	private JButton button2;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
