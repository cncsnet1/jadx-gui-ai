package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.net.URI;
import java.net.URL;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

public class AboutDialog extends JDialog {
	private static final long serialVersionUID = 5763493590584039096L;

	public AboutDialog() {
		initUI();
	}

	public final void initUI() {
		// 定义更现代的字体和颜色
		Font titleFont = new Font("Microsoft YaHei", Font.BOLD, 24);  // 标题使用微软雅黑
		Font normalFont = new Font("Microsoft YaHei", Font.PLAIN, 13);
		Font customFont = new Font("Microsoft YaHei", Font.ITALIC, 12);

		Color primaryColor = new Color(51, 122, 183);  // 主题蓝
		Color secondaryColor = new Color(108, 117, 125);  // 次要文本颜色
		Color accentColor = new Color(88, 166, 255);  // 强调色

		// Logo部分
		URL logoURL = getClass().getResource("/logos/jadx-logo-48px.png");  // 使用更大的logo
		Icon logo = new ImageIcon(logoURL, "jadx logo");

		JLabel name = new JLabel("JADX-GUI-AI", logo, SwingConstants.CENTER);  // 大写显示更专业
		name.setFont(titleFont);
		name.setForeground(primaryColor);
		name.setAlignmentX(0.5f);

		// 修改标语为中文
		JLabel slogan = new JLabel("强大的 Java 反编译工具");
		slogan.setFont(new Font("Microsoft YaHei", Font.PLAIN, 16));  // 使用微软雅黑字体
		slogan.setForeground(secondaryColor);
		slogan.setAlignmentX(0.5f);

		// 版本信息使用现代样式
		JLabel version = new JLabel("版本: " + getVersion());
		version.setFont(normalFont);
		version.setForeground(secondaryColor);
		version.setAlignmentX(0.5f);

		// 添加精美分隔线
		JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
		separator.setMaximumSize(new Dimension(300, 1));
		separator.setForeground(new Color(230, 230, 230));

		// GitHub链接面板
		JPanel socialPanel = new JPanel();
		socialPanel.setLayout(new BoxLayout(socialPanel, BoxLayout.X_AXIS));
		socialPanel.setAlignmentX(0.5f);
		socialPanel.setBackground(null);

		URL githubIconURL = getClass().getResource("/logos/Github.png");
		Icon githubIcon = new ImageIcon(githubIconURL, "GitHub");
		JLabel githubLabel = new JLabel("在 GitHub 上查看", githubIcon, SwingConstants.LEFT);
		githubLabel.setFont(normalFont);
		githubLabel.setForeground(primaryColor);
		githubLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
		githubLabel.addMouseListener(new MouseListener() {
			@Override
			public void mouseClicked(MouseEvent e) {
				browse("https://github.com/cncsnet1");
			}

			@Override
			public void mousePressed(MouseEvent e) {}

			@Override
			public void mouseReleased(MouseEvent e) {}

			@Override
			public void mouseEntered(MouseEvent e) {
				githubLabel.setForeground(accentColor);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				githubLabel.setForeground(primaryColor);
			}
		});
		socialPanel.add(githubLabel);

		// 作者信息面板
		JPanel creditsPanel = new JPanel();
		creditsPanel.setLayout(new BoxLayout(creditsPanel, BoxLayout.Y_AXIS));
		creditsPanel.setAlignmentX(0.5f);
		creditsPanel.setBackground(null);

		// 二次开发信息
		JLabel modifiedLabel = new JLabel("二次开发版本");
		modifiedLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
		modifiedLabel.setForeground(accentColor);
		modifiedLabel.setAlignmentX(0.5f);

		JLabel enhancedLabel = new JLabel("当前版本开发者: Ferry (v1.0.0)");
		enhancedLabel.setFont(customFont);
		enhancedLabel.setForeground(accentColor);
		enhancedLabel.setAlignmentX(0.5f);

		JLabel originalLabel = new JLabel("原作者: skylot (v1.6.6)");
		originalLabel.setFont(customFont);
		originalLabel.setForeground(secondaryColor);
		originalLabel.setAlignmentX(0.5f);

		creditsPanel.add(modifiedLabel);
		creditsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		creditsPanel.add(enhancedLabel);
		creditsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		creditsPanel.add(originalLabel);

		// 系统信息
		JPanel systemPanel = new JPanel();
		systemPanel.setLayout(new BoxLayout(systemPanel, BoxLayout.Y_AXIS));
		systemPanel.setAlignmentX(0.5f);
		systemPanel.setBackground(null);

		JLabel javaVmLabel = new JLabel("Java 虚拟机: " + System.getProperty("java.vm.name"));
		JLabel javaVerLabel = new JLabel("Java 版本: " + System.getProperty("java.version"));
		javaVmLabel.setFont(normalFont);
		javaVerLabel.setFont(normalFont);
		javaVmLabel.setForeground(secondaryColor);
		javaVerLabel.setForeground(secondaryColor);

		systemPanel.add(javaVmLabel);
		systemPanel.add(javaVerLabel);

		// 主面板布局
		JPanel mainPanel = new JPanel();
		mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(25, 40, 25, 40));
		mainPanel.setBackground(Color.WHITE);

		mainPanel.add(name);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
		mainPanel.add(slogan);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 5)));
		mainPanel.add(version);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 20)));
		mainPanel.add(separator);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 20)));
		mainPanel.add(socialPanel);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		mainPanel.add(creditsPanel);
		mainPanel.add(Box.createRigidArea(new Dimension(0, 15)));
		mainPanel.add(systemPanel);

		// 关闭按钮
		JButton closeButton = new JButton("关闭");
		closeButton.setFont(normalFont);
		closeButton.setForeground(Color.WHITE);
		closeButton.setBackground(primaryColor);
		closeButton.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
		closeButton.setFocusPainted(false);
		closeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
		closeButton.addActionListener(e -> dispose());

		// 设置窗口属性
		setBackground(Color.WHITE);
		getContentPane().setBackground(Color.WHITE);
		getContentPane().add(mainPanel, BorderLayout.CENTER);
		getContentPane().add(closeButton, BorderLayout.SOUTH);

		setTitle("关于 JADX-GUI-AI");
		setResizable(false);
		setModalityType(ModalityType.APPLICATION_MODAL);
		pack();
		setLocationRelativeTo(null);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
	}

	private String getVersion() {
		return "1.0.0";
	}

	private void browse(String url) {
		try {
			Desktop.getDesktop().browse(new URI(url));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
