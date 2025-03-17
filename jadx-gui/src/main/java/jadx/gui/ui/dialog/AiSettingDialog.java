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
		setTitle("AI\u914d\u7f6e");
		setResizable(true);
		setSize(new Dimension(650, 450));
		setMinimumSize(new Dimension(600, 420));
		var contentPane = getContentPane();
		contentPane.setLayout(new GridBagLayout());
		((GridBagLayout)contentPane.getLayout()).columnWeights = new double[] {1.0};
		((GridBagLayout)contentPane.getLayout()).rowWeights = new double[] {1.0, 0.0};

		//======== panel1 ========
		{
			panel1.setBorder(new EmptyBorder(25, 25, 15, 25));
			panel1.setPreferredSize(new Dimension(600, 380));
			panel1.setLayout(new GridBagLayout());

			//---- label1 ----
			label1.setText("API\u5730\u5740:");
			label1.setFont(label1.getFont().deriveFont(label1.getFont().getStyle() | Font.BOLD));
			panel1.add(label1, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(8, 5, 8, 12), 0, 0));

			//---- apiUrlField ----
			apiUrlField.setPreferredSize(new Dimension(320, 32));
			apiUrlField.setToolTipText("\u8f93\u5165API\u670d\u52a1\u5668\u5730\u5740\uff0c\u4f8b\u5982: https://api.openai.com");
			panel1.add(apiUrlField, new GridBagConstraints(1, 0, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(8, 5, 8, 12), 0, 0));

			//---- urlHelpLabel ----
			urlHelpLabel.setText("<html><font color='#666666'>\u8f93\u5165\u5b8c\u6574\u7684API\u670d\u52a1\u5668\u5730\u5740\uff0c\u5305\u542bhttps://</font></html>");
			urlHelpLabel.setFont(urlHelpLabel.getFont().deriveFont(urlHelpLabel.getFont().getSize() - 2f));
			panel1.add(urlHelpLabel, new GridBagConstraints(1, 1, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 8, 4, 0), 0, 0));

			//---- platformsLabel ----
			platformsLabel.setText("<html><font color='#666666'>\u652f\u6301\u7684\u5e73\u53f0: OpenAI, \u7845\u57fa\u6d41\u52a8, DeepSeek, \u706b\u5c71\u5f15\u64ce, \u963f\u91cc\u4e91\u7b49</font></html>");
			platformsLabel.setFont(platformsLabel.getFont().deriveFont(platformsLabel.getFont().getSize() - 2f));
			panel1.add(platformsLabel, new GridBagConstraints(1, 2, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 8, 12, 0), 0, 0));

			//---- label2 ----
			label2.setText("API Key:");
			label2.setFont(label2.getFont().deriveFont(label2.getFont().getStyle() | Font.BOLD));
			panel1.add(label2, new GridBagConstraints(0, 3, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- apiKeyField ----
			apiKeyField.setPreferredSize(new Dimension(300, 30));
			apiKeyField.setToolTipText("\u8f93\u5165\u60a8\u7684API\u5bc6\u94a5");
			panel1.add(apiKeyField, new GridBagConstraints(1, 3, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- keyHelpLabel ----
			keyHelpLabel.setText("<html><font color='gray'>\u5728API\u63d0\u4f9b\u5546\u5904\u83b7\u53d6\u7684\u5bc6\u94a5\uff0c\u8bf7\u59a5\u5584\u4fdd\u7ba1</font></html>");
			keyHelpLabel.setFont(keyHelpLabel.getFont().deriveFont(keyHelpLabel.getFont().getSize() - 2f));
			panel1.add(keyHelpLabel, new GridBagConstraints(1, 4, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- label3 ----
			label3.setText("\u6a21\u578b\u540d\u79f0:");
			label3.setFont(label3.getFont().deriveFont(label3.getFont().getStyle() | Font.BOLD));
			panel1.add(label3, new GridBagConstraints(0, 5, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- modelField ----
			modelField.setPreferredSize(new Dimension(300, 30));
			modelField.setToolTipText("\u8f93\u5165\u8981\u4f7f\u7528\u7684AI\u6a21\u578b\u540d\u79f0");
			panel1.add(modelField, new GridBagConstraints(1, 5, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- modelHelpLabel ----
			modelHelpLabel.setText("<html><font color='gray'>\u4f8b\u5982: gpt-4-turbo-preview, gpt-3.5-turbo</font></html>");
			modelHelpLabel.setFont(modelHelpLabel.getFont().deriveFont(modelHelpLabel.getFont().getSize() - 2f));
			panel1.add(modelHelpLabel, new GridBagConstraints(1, 6, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- platformModelsLabel ----
			platformModelsLabel.setText("<html><font color='gray'>\u5404\u5e73\u53f0\u6a21\u578b: qianfan-chinese-llama-2, deepseek-chat, chatglm3-6b\u7b49</font></html>");
			platformModelsLabel.setFont(platformModelsLabel.getFont().deriveFont(platformModelsLabel.getFont().getSize() - 2f));
			panel1.add(platformModelsLabel, new GridBagConstraints(1, 7, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- label4 ----
			label4.setText("\u6700\u5927Token:");
			label4.setFont(label4.getFont().deriveFont(label4.getFont().getStyle() | Font.BOLD));
			panel1.add(label4, new GridBagConstraints(0, 8, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- maxTokenField ----
			maxTokenField.setPreferredSize(new Dimension(300, 30));
			maxTokenField.setToolTipText("\u8bbe\u7f6eAI\u54cd\u5e94\u7684\u6700\u5927Token\u6570\u91cf");
			panel1.add(maxTokenField, new GridBagConstraints(1, 8, 1, 1, 1.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));

			//---- maxTokenHelpLabel ----
			maxTokenHelpLabel.setText("<html><font color='gray'>\u9ed8\u8ba4\u503c\u4e3a4096\uff0c\u6839\u636e\u60a8\u4f7f\u7528\u7684\u6a21\u578b\u53ef\u4ee5\u9002\u5f53\u8c03\u6574</font></html>");
			maxTokenHelpLabel.setFont(maxTokenHelpLabel.getFont().deriveFont(maxTokenHelpLabel.getFont().getSize() - 2f));
			panel1.add(maxTokenHelpLabel, new GridBagConstraints(1, 9, 1, 1, 0.0, 0.0,
				GridBagConstraints.CENTER, GridBagConstraints.BOTH,
				new Insets(0, 0, 0, 0), 0, 0));
		}
		contentPane.add(panel1, new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0,
			GridBagConstraints.CENTER, GridBagConstraints.BOTH,
			new Insets(0, 0, 15, 0), 0, 0));

		//======== panel2 ========
		{
			panel2.setBorder(new EmptyBorder(5, 0, 15, 0));
			panel2.setLayout(new FlowLayout(FlowLayout.CENTER, 25, 10));

			//---- button1 ----
			button1.setText("\u4fdd\u5b58");
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
			button2.setText("\u5173\u95ed");
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
