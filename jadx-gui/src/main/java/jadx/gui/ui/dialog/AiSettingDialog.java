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
import javax.swing.border.EmptyBorder;

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
		maxTokenField.setText(settings.getAiMaxTokens()==null?"":settings.getAiMaxTokens());
    }

	private void button1MouseClicked(MouseEvent e) {
		// TODO add your code here

		settings.setAiApi(apiUrlField.getText()==null?"":apiUrlField.getText());
		settings.setAiApiKey(apiKeyField.getText()==null?"":apiKeyField.getText());
		settings.setAiModel(modelField.getText()==null?"":modelField.getText());
		settings.setAiMaxTokens(maxTokenField.getText()==null?"":maxTokenField.getText());
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
		urlHelpLabel = new JLabel();
		platformsLabel = new JLabel();
		label2 = new JLabel();
		apiKeyField = new JTextField();
		keyHelpLabel = new JLabel();
		label3 = new JLabel();
		modelField = new JTextField();
		modelHelpLabel = new JLabel();
		platformModelsLabel = new JLabel();
		label4 = new JLabel();
		maxTokenField = new JTextField();
		maxTokenHelpLabel = new JLabel();
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

			//---- urlHelpLabel ----
			urlHelpLabel.setText("<html><font color='#666666'>请输入完整的API服务器地址，包含https://<br>示例：<br>OpenAI: <a href='https://api.openai.com/v1'>https://api.openai.com/v1</a><br>百度千帆: <a href='https://aip.baidubce.com/v1'>https://aip.baidubce.com/v1</a><br>阿里云: <a href='https://dashscope.aliyuncs.com/v1'>https://dashscope.aliyuncs.com/v1</a><br>火山引擎: <a href='https://open.volcengineapi.com/v1'>https://open.volcengineapi.com/v1</a></font></html>");
			urlHelpLabel.setFont(urlHelpLabel.getFont().deriveFont(urlHelpLabel.getFont().getSize() - 2f));
			urlHelpLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
			urlHelpLabel.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					String url = e.getSource().toString();
					if (url.contains("href='")) {
						url = url.substring(url.indexOf("href='") + 6, url.indexOf("'>"));
						try {
							Desktop.getDesktop().browse(new URI(url));
						} catch (Exception ex) {
							// 忽略异常
						}
					}
				}
			});
			panel1.add(urlHelpLabel, new GridBagConstraints(1, 1, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 8, 8, 0), 0, 0));

			//---- platformsLabel ----
			platformsLabel.setText("<html><font color='#666666'>支持的平台: OpenAI, 百度千帆, DeepSeek, 火山引擎, 阿里云</font></html>");
			platformsLabel.setFont(platformsLabel.getFont().deriveFont(platformsLabel.getFont().getSize() - 2f));
			panel1.add(platformsLabel, new GridBagConstraints(1, 2, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 8, 15, 0), 0, 0));

			//---- label2 ----
			label2.setText("API Key:");
			label2.setFont(label2.getFont().deriveFont(label2.getFont().getStyle() | Font.BOLD));
			panel1.add(label2, new GridBagConstraints(0, 3, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- apiKeyField ----
			apiKeyField.setPreferredSize(new Dimension(700, 30));
			apiKeyField.setToolTipText("输入您的API密钥");
			panel1.add(apiKeyField, new GridBagConstraints(1, 3, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- keyHelpLabel ----
			keyHelpLabel.setText("<html><font color='gray'>在API提供商处获取的密钥，请妥善保管<br>获取方式：<br>OpenAI: <a href='https://platform.openai.com/api-keys'>https://platform.openai.com/api-keys</a><br>百度千帆: <a href='https://console.bce.baidu.com/qianfan/overview'>https://console.bce.baidu.com/qianfan/overview</a><br>阿里云: <a href='https://dashscope.console.aliyun.com/apiKey'>https://dashscope.console.aliyun.com/apiKey</a><br>火山引擎: <a href='https://console.volcengine.com/iam/keymanage/'>https://console.volcengine.com/iam/keymanage/</a></font></html>");
			keyHelpLabel.setFont(keyHelpLabel.getFont().deriveFont(keyHelpLabel.getFont().getSize() - 2f));
			keyHelpLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
			keyHelpLabel.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					String url = e.getSource().toString();
					if (url.contains("href='")) {
						url = url.substring(url.indexOf("href='") + 6, url.indexOf("'>"));
						try {
							Desktop.getDesktop().browse(new URI(url));
						} catch (Exception ex) {
							// 忽略异常
						}
					}
				}
			});
			panel1.add(keyHelpLabel, new GridBagConstraints(1, 4, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 15, 0), 0, 0));

			//---- label3 ----
			label3.setText("模型名称:");
			label3.setFont(label3.getFont().deriveFont(label3.getFont().getStyle() | Font.BOLD));
			panel1.add(label3, new GridBagConstraints(0, 5, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- modelField ----
			modelField.setPreferredSize(new Dimension(700, 30));
			modelField.setToolTipText("输入要使用的AI模型名称");
			panel1.add(modelField, new GridBagConstraints(1, 5, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- modelHelpLabel ----
			modelHelpLabel.setText("<html><font color='gray'>各平台支持的模型示例：<br>OpenAI: gpt-4-turbo-preview, gpt-3.5-turbo<br>百度千帆: qianfan-chinese-llama-2-13b, qianfan-chinese-llama-2-7b<br>阿里云: qwen-max, qwen-plus<br>火山引擎: skylark-chat, skylark-pro</font></html>");
			modelHelpLabel.setFont(modelHelpLabel.getFont().deriveFont(modelHelpLabel.getFont().getSize() - 2f));
			panel1.add(modelHelpLabel, new GridBagConstraints(1, 6, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 15, 0), 0, 0));

			//---- platformModelsLabel ----
			platformModelsLabel.setText("<html><font color='gray'>各平台模型: qianfan-chinese-llama-2, deepseek-chat, chatglm3-6b等</font></html>");
			platformModelsLabel.setFont(platformModelsLabel.getFont().deriveFont(platformModelsLabel.getFont().getSize() - 2f));
			panel1.add(platformModelsLabel, new GridBagConstraints(1, 7, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- label4 ----
			label4.setText("最大Token:");
			label4.setFont(label4.getFont().deriveFont(label4.getFont().getStyle() | Font.BOLD));
			panel1.add(label4, new GridBagConstraints(0, 7, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- maxTokenField ----
			maxTokenField.setPreferredSize(new Dimension(700, 30));
			maxTokenField.setToolTipText("设置AI响应的最大Token数量");
			panel1.add(maxTokenField, new GridBagConstraints(1, 7, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(10, 0, 10, 0), 0, 0));

			//---- maxTokenHelpLabel ----
			maxTokenHelpLabel.setText("<html><font color='gray'>默认值为4096，根据您使用的模型可以适当调整<br>建议值：<br>OpenAI GPT-4: 4096-8192<br>百度千帆: 2048-4096<br>阿里云: 2048-4096<br>火山引擎: 2048-4096</font></html>");
			maxTokenHelpLabel.setFont(maxTokenHelpLabel.getFont().deriveFont(maxTokenHelpLabel.getFont().getSize() - 2f));
			panel1.add(maxTokenHelpLabel, new GridBagConstraints(1, 8, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));
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
	private JLabel urlHelpLabel;
	private JLabel platformsLabel;
	private JLabel label2;
	private JTextField apiKeyField;
	private JLabel keyHelpLabel;
	private JLabel label3;
	private JTextField modelField;
	private JLabel modelHelpLabel;
	private JLabel platformModelsLabel;
	private JLabel label4;
	private JTextField maxTokenField;
	private JLabel maxTokenHelpLabel;
	private JPanel panel2;
	private JButton button1;
	private JButton button2;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}
