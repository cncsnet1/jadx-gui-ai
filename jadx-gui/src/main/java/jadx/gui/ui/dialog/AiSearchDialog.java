package jadx.gui.ui.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeListener;

import jadx.gui.ui.panel.ProgressPanel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchWithHistoryIcon;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import jadx.api.JavaClass;
import jadx.api.JavaPackage;
import jadx.core.utils.ListUtils;
import jadx.gui.jobs.ITaskInfo;
import jadx.gui.jobs.ITaskProgress;
import jadx.gui.search.SearchSettings;
import jadx.gui.search.SearchTask;
import jadx.gui.search.providers.ClassSearchProvider;
import jadx.gui.search.providers.CodeSearchProvider;
import jadx.gui.search.providers.CommentSearchProvider;
import jadx.gui.search.providers.FieldSearchProvider;
import jadx.gui.search.providers.MergedSearchProvider;
import jadx.gui.search.providers.MethodSearchProvider;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResource;
import jadx.gui.ui.MainWindow;

import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.ACTIVE_TAB;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.CLASS;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.CODE;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.COMMENT;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.FIELD;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.IGNORE_CASE;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.METHOD;
import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.USE_REGEX;

import jadx.gui.utils.JumpPosition;
import jadx.gui.utils.NLS;
import jadx.gui.utils.TextStandardActions;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.layout.WrapLayout;
import jadx.gui.utils.rx.RxUtils;
import jadx.gui.utils.ai.AIHttpUtils;

public class AiSearchDialog extends CommonSearchDialog {
    private static final Logger LOG = LoggerFactory.getLogger(AiSearchDialog.class);
    private static final long serialVersionUID = -5105405456969134105L;

    private static final Color SEARCH_FIELD_ERROR_COLOR = new Color(255, 150, 150);

    public static void search(MainWindow window, SearchPreset preset) {
        AiSearchDialog searchDialog = new AiSearchDialog(window, preset, Collections.emptySet());
        show(searchDialog, window);
    }

    public static void searchInActiveTab(MainWindow window, SearchPreset preset) {
        AiSearchDialog searchDialog = new AiSearchDialog(window, preset, EnumSet.of(SearchOptions.ACTIVE_TAB));
        show(searchDialog, window);
    }

    public static void searchText(MainWindow window, String text) {
        AiSearchDialog searchDialog = new AiSearchDialog(window, SearchPreset.TEXT, Collections.emptySet());
        searchDialog.initSearchText = text;
        show(searchDialog, window);
    }

    public static void searchPackage(MainWindow window, String packageName) {
        AiSearchDialog searchDialog = new AiSearchDialog(window, SearchPreset.TEXT, Collections.emptySet());
        searchDialog.initSearchPackage = packageName;
        show(searchDialog, window);
    }

    private static void show(AiSearchDialog searchDialog, MainWindow mw) {
        mw.addLoadListener(loaded -> {
            if (!loaded) {
                searchDialog.dispose();
                return true;
            }
            return false;
        });
        searchDialog.setVisible(true);
    }

    public enum SearchPreset {
        TEXT, CLASS, COMMENT
    }

    public enum SearchOptions {
        CLASS,
        METHOD,
        FIELD,
        CODE,
        RESOURCE,
        COMMENT,

        IGNORE_CASE,
        USE_REGEX,
        ACTIVE_TAB
    }

    private final transient SearchPreset searchPreset;
    private final transient Set<SearchOptions> options;

    private transient Color searchFieldDefaultBgColor;

    private transient JTextField searchField;
    private transient JTextField packageField;

    private transient @Nullable SearchTask searchTask;


    private transient Disposable searchDisposable;
    private transient SearchEventEmitter searchEmitter;
    private transient ChangeListener activeTabListener;

    private transient String initSearchText = null;
    private transient String initSearchPackage = null;

    // temporal list for pending results
    private final List<JNode> pendingResults = new ArrayList<>();

    /**
     * Use single thread to do all background work, so additional synchronisation not needed
     */
    private final Executor searchBackgroundExecutor = Executors.newSingleThreadExecutor();

    private transient AIHttpUtils aiHttpUtils;
    private List<String> allFilePaths = new ArrayList<>();
    private static final int MAX_TOKENS = 4096;
    private static final double TEMPERATURE = 0.7;
    private JTextArea aiResponseArea;

    private String systemPrompt;

    private ResultsModel resultsModel;
    private transient JLabel warnLabel;
    private transient ProgressPanel progressPane;
    private transient ResultsTable resultsTable;
    private transient JLabel resultsInfoLabel;

    private AiSearchDialog(MainWindow mainWindow, SearchPreset preset, Set<SearchOptions> additionalOptions) {
        super(mainWindow, NLS.str("menu.ai_search"));
        this.searchPreset = preset;
        this.options = buildOptions(preset);
        this.options.addAll(additionalOptions);

        // 初始化packageField
        packageField = new JTextField();
        if (initSearchPackage != null) {
            packageField.setText(initSearchPackage);
        }

        loadWindowPos();
        initUI();
        initSearchEvents();
        registerInitOnOpen();
        registerActiveTabListener();
    }

    @Override
    public void dispose() {
        if (searchDisposable != null && !searchDisposable.isDisposed()) {
            searchDisposable.dispose();
        }
        resultsModel.clear();
        removeActiveTabListener();
        searchBackgroundExecutor.execute(() -> {
            stopSearchTask();
            mainWindow.getBackgroundExecutor().waitForComplete();
            unloadTempData();
        });
        super.dispose();
    }

    private Set<SearchOptions> buildOptions(SearchPreset preset) {
        Set<SearchOptions> searchOptions = null;
        if (searchOptions == null) {
            searchOptions = EnumSet.noneOf(SearchOptions.class);
        }
        switch (preset) {
            case TEXT:
                if (searchOptions.isEmpty()) {
                    searchOptions.add(SearchOptions.CODE);

                }
                break;

            case CLASS:
                searchOptions.add(SearchOptions.CLASS);
                break;

            case COMMENT:
                searchOptions.add(SearchOptions.COMMENT);
                searchOptions.remove(SearchOptions.ACTIVE_TAB);
                break;
        }
        return searchOptions;
    }

    @Override
    protected void openInit() {
        // 先初始化AI和文件列表
        if (!initAI()) {
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("请先在设置中配置AI接口信息");
                aiResponseArea.setText("请先在设置中配置以下信息：\n1. AI接口地址\n2. API密钥\n3. 模型名称");
            });
            return;
        }

        // 收集并过滤文件列表
        collectAllFilePaths();

        // 初始化搜索事件
        initSearchEvents();

        String searchText = initSearchText != null ? initSearchText : cache.getLastSearch();
        if (searchText != null) {
            searchField.setText(searchText);
            searchField.selectAll();
        }

        searchField.requestFocus();
        resultsTable.initColumnWidth();

        if (options.contains(COMMENT)) {
            // show all comments on empty input
            searchEmitter.emitSearch();
        }
    }

    private boolean initAI() {
        String apiUrl = mainWindow.getSettings().getAiApi();
        String apiKey = mainWindow.getSettings().getAiApiKey();
        String model = mainWindow.getSettings().getAiModel();

        // 检查AI配置是否完整
        if (apiUrl == null || apiUrl.trim().isEmpty() ||
            apiKey == null || apiKey.trim().isEmpty() ||
            model == null || model.trim().isEmpty()) {
            LOG.warn("AI配置信息不完整");
            return false;
        }

        try {
            aiHttpUtils = new AIHttpUtils(apiUrl, apiKey);
            return true;
        } catch (Exception e) {
            LOG.error("初始化AI工具失败", e);
            return false;
        }
    }

    private void initSearchEvents() {
        if (searchDisposable != null) {
            searchDisposable.dispose();
            searchDisposable = null;
        }
        try {
            searchEmitter = new SearchEventEmitter();
            Flowable<String> searchEvents = Flowable.merge(
                    RxUtils.textFieldEnterPress(searchField),
                    searchEmitter.getFlowable());

            searchDisposable = searchEvents
                    .debounce(300, TimeUnit.MILLISECONDS)
                    .observeOn(Schedulers.from(searchBackgroundExecutor))
                    .subscribe(
                        query -> {
							System.out.println("开始AI搜索:"+query);
                            searchWithAI(query);
                        },
                        error -> {
                            LOG.error("搜索事件处理错误", error);
                            UiUtils.uiRun(() -> {
                                resultsInfoLabel.setText("搜索处理出错：" + error.getMessage());
                                aiResponseArea.setText("搜索处理出错：" + error.getMessage());
                            });
                        }
                    );
        } catch (Exception e) {
            LOG.error("初始化搜索事件失败", e);
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("初始化搜索功能失败：" + e.getMessage());
                aiResponseArea.setText("初始化搜索功能失败：" + e.getMessage());
            });
        }
    }

    private void collectAllFilePaths() {
        // 获取所有Java类
        List<JavaClass> allClasses = mainWindow.getWrapper().getIncludedClassesWithInners();

        // 获取包名过滤
        final String packageFilter = packageField != null ? packageField.getText().trim() : "";

        // 过滤类名
        allFilePaths = allClasses.stream()
                .map(JavaClass::getFullName)  // 直接使用完整类名
                .filter(Objects::nonNull)
                .filter(className -> {
                    if (packageFilter.isEmpty()) {
                        return true;  // 如果没有输入包名，返回所有类
                    }
                    return className.startsWith(packageFilter);  // 只返回指定包下的类
                })
                .distinct()
                .collect(Collectors.toList());

        // 更新系统提示词
        systemPrompt = "你是一个专业的代码分析助手，负责帮助用户在Java项目中定位相关代码。\n" +
                "我会给你以下信息：\n" +
                "1. 用户的功能描述或查询需求\n" +
                "2. 项目中所有Java类的完整类名列表" +
                (packageFilter.isEmpty() ? "" : "(已过滤为" + packageFilter + "包下的类)") + "\n\n" +
                "请严格按照以下规则进行分析：\n" +
                "1. 只根据类名进行判断，不要根据包名推测\n" +
                "2. 类名必须与功能描述有明确的语义关联\n" +
                "3. 不要返回整个包下的所有类\n" +
                "4. 如果类名与功能描述关联度不高，不要返回\n" +
                "5. 返回结果必须是完整的类名，后面跟着命中率(0-100)\n" +
                "6. 如果当前批次没有相关类，请直接回复\"本批次无相关类\"\n" +
                "7. 不要解释选择原因，只返回类名和命中率\n" +
                "8. 类名匹配必须精确，不要使用模糊匹配\n" +
                "9. 不要返回内部类，除非明确与功能相关\n" +
                "10. 如果类名包含多个单词，必须与功能描述中的关键词有直接对应关系\n" +
                "11. 返回格式为：类名|命中率，例如：com.example.MyClass|85\n";
    }

    private void searchWithAI(String query) {
        if (aiHttpUtils == null) {
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("请先在设置中配置AI接口信息");
                aiResponseArea.setText("请先在设置中配置AI接口信息");
            });
            return;
        }

        if (query == null || query.trim().isEmpty()) {
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("请输入搜索内容");
                aiResponseArea.setText("请输入要搜索的内容");
            });
            return;
        }

        try {
            // 重新收集并过滤文件列表
            collectAllFilePaths();

            // 清空之前的结果
            UiUtils.uiRun(() -> {
                aiResponseArea.setText("");
                resultsInfoLabel.setText("AI正在分析...");
                resultsModel.clear();
                resultsTable.updateTable();
            });

            // 将所有类名分批处理
            int classesPerBatch = 300; // 增加每批处理的类数量
            Set<String> allRelevantClasses = new HashSet<>();
            StringBuffer fullAnalysis = new StringBuffer();

            // 计算总批次
            int totalBatches = (allFilePaths.size() + classesPerBatch - 1) / classesPerBatch;

            // 更新状态信息，显示当前正在分析的包
            final String packageFilter = packageField != null ? packageField.getText().trim() : "";
            if (!packageFilter.isEmpty()) {
                UiUtils.uiRun(() -> {
                    resultsInfoLabel.setText("正在分析 " + packageFilter + " 包下的类...");
                });
            }

            for (int i = 0; i < allFilePaths.size(); i += classesPerBatch) {
                final int currentBatch = i / classesPerBatch + 1;
                int end = Math.min(i + classesPerBatch, allFilePaths.size());
                List<String> batch = allFilePaths.subList(i, end);

                // 构建当前批次的消息
                List<Map<String, String>> messages = new ArrayList<>();
                String currentPrompt = systemPrompt + "\n当前批次类名列表（第" + currentBatch + "批，共" +
                    totalBatches + "批）：\n" +
                    String.join("\n", batch);

                messages.add(AIHttpUtils.createMessage("system", currentPrompt));
                messages.add(AIHttpUtils.createMessage("user",
                    "请严格按照以下要求分析功能描述，从当前批次类名中找出相关的类：\n" +
                    "1. 只返回与功能描述直接相关的类名\n" +
                    "2. 每个类名单独一行\n" +
                    "3. 不要返回无关的类\n" +
                    "4. 不要解释选择原因\n" +
                    "5. 如果当前批次没有相关类，请直接回复\"本批次无相关类\"\n\n" +
                    "当前批次类名列表：\n" +
                    String.join("\n", batch) + "\n\n" +
                    "功能描述：\n" + query));

                // 使用StringBuffer来累积当前批次的分析结果
                StringBuffer batchAnalysis = new StringBuffer();

                // AI分析当前批次
                aiHttpUtils.createStreamingChatCompletionWithCallback(
                    null,
                    mainWindow.getSettings().getAiModel(),
                    messages,
                    TEMPERATURE,
                    MAX_TOKENS,
                    content -> {
                        batchAnalysis.append(content);

                        // 在UI线程中更新结果
                        UiUtils.uiRun(() -> {
                            fullAnalysis.append(content);
                            aiResponseArea.setText(fullAnalysis.toString());
                            aiResponseArea.setCaretPosition(aiResponseArea.getDocument().getLength());

                            // 从AI响应中提取类名和命中率
                            String[] lines = batchAnalysis.toString().split("\n");
                            if (!batchAnalysis.toString().contains("\"本批次无相关类\"")) {
                                for (String line : lines) {
                                    line = line.trim();
                                    // 跳过空行和注释行
                                    if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                                        continue;
                                    }
                                    // 解析类名和命中率
                                    String[] parts = line.split("\\|");
                                    if (parts.length == 2) {
                                        String className = parts[0].trim();
                                        int relevance = Integer.parseInt(parts[1].trim());
                                        // 精确匹配类名
                                        for (String batchClassName : batch) {
                                            if (className.equals(batchClassName)) {
                                                allRelevantClasses.add(className + "|" + relevance);
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            // 更新结果表格
                            List<JNode> results = new ArrayList<>();
                            for (String classInfo : allRelevantClasses) {
                                String[] parts = classInfo.split("\\|");
                                String className = parts[0];
                                int relevance = Integer.parseInt(parts[1]);

                                JavaClass javaClass = mainWindow.getWrapper().searchJavaClassByFullAlias(className);
                                if (javaClass != null) {
                                    // 使用NodeCache创建JClass实例
                                    JClass jClass = new JClass(javaClass, null, mainWindow.getCacheObject().getNodeCache());
                                    if (jClass != null) {
                                        // 设置显示名称为类名和命中率

                                        results.add(jClass);
                                    }
                                }
                            }
                            resultsModel.clear();
                            resultsModel.addAll(results);
                            resultsTable.updateTable();

                            resultsInfoLabel.setText("找到 " + results.size() + " 个相关类 (分析第 " +
                                currentBatch + "/" + totalBatches + " 批)");
                        });
                    }
                );
            }

        } catch (Exception e) {
            LOG.error("AI搜索失败", e);
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("AI搜索失败: " + e.getMessage());
                aiResponseArea.setText("AI搜索失败: " + e.getMessage() + "\n\n详细错误信息：\n" + e.toString());
            });
        }
    }




    @Override
    protected void openItem(JNode node) {
        if (mainWindow.getSettings().isUseAutoSearch()) {
            // for auto search save only searches which leads to node opening
            mainWindow.getProject().addToSearchHistory(searchField.getText());
        }
        super.openItem(node);
    }



    private void stopSearchTask() {
        UiUtils.notUiThreadGuard();
        if (searchTask != null) {
            searchTask.cancel();
            searchTask.waitTask();
            searchTask = null;
        }
    }
    private void unloadTempData() {
        mainWindow.getWrapper().unloadClasses();
        System.gc();
    }

    private JCheckBox makeOptionsCheckBox(String name, final SearchOptions opt) {
        final JCheckBox chBox = new JCheckBox(name);
        chBox.setAlignmentX(LEFT_ALIGNMENT);
        chBox.setSelected(options.contains(opt));
        chBox.addItemListener(e -> {
            if (chBox.isSelected()) {
                options.add(opt);
            } else {
                options.remove(opt);
            }
            searchEmitter.emitSearch();
        });
        return chBox;
    }

    @Override
    protected void loadFinished() {
        resultsTable.setEnabled(true);
        searchField.setEnabled(true);
        searchEmitter.emitSearch();
    }

    @Override
    protected void loadStart() {
        resultsTable.setEnabled(false);
        searchField.setEnabled(false);
    }

    private void registerActiveTabListener() {
        removeActiveTabListener();
        activeTabListener = e -> {
            if (options.contains(SearchOptions.ACTIVE_TAB)) {
                LOG.debug("active tab change event received");
                searchEmitter.emitSearch();
            }
        };
        mainWindow.getTabbedPane().addChangeListener(activeTabListener);
    }

    private void removeActiveTabListener() {
        if (activeTabListener != null) {
            mainWindow.getTabbedPane().removeChangeListener(activeTabListener);
            activeTabListener = null;
        }
    }

    private void initUI() {
        // 初始化必要的组件
        resultsModel = new ResultsModel();
        resultsTable = new ResultsTable(resultsModel, new ResultsTableCellRenderer());

        warnLabel = new JLabel();
        warnLabel.setForeground(Color.RED);
        warnLabel.setVisible(false);

        resultsInfoLabel = new JLabel();
        resultsInfoLabel.setForeground(new Color(75, 75, 75));
        resultsInfoLabel.setVisible(true);

        progressPane = new ProgressPanel(mainWindow, false);
        progressPane.setVisible(false);


        // 创建主布局面板
        JPanel contentPanel = new JPanel(new BorderLayout(5, 5));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // 搜索框面板
        JPanel searchLinePanel = new JPanel();
        searchLinePanel.setLayout(new BoxLayout(searchLinePanel, BoxLayout.Y_AXIS));
        searchLinePanel.setAlignmentX(LEFT_ALIGNMENT);

        // 包名输入面板
        JPanel packagePanel = new JPanel();
        packagePanel.setLayout(new BoxLayout(packagePanel, BoxLayout.X_AXIS));
        packagePanel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel packageLabel = new JLabel("包名：");
        packageField.setFont(packageField.getFont().deriveFont(14f));
        packageField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "输入包名进行过滤(可选)");
        new TextStandardActions(packageField);

        // 添加包名输入框的监听器
        packageField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    collectAllFilePaths();  // 重新收集文件
                    searchEmitter.emitSearch();  // 触发搜索
                }
            }
        });

        packagePanel.add(packageLabel);
        packagePanel.add(packageField);

        // 搜索输入面板
        JPanel searchPanel = new JPanel();
        searchPanel.setLayout(new BoxLayout(searchPanel, BoxLayout.X_AXIS));
        searchPanel.setAlignmentX(LEFT_ALIGNMENT);

        searchField = new JTextField();
        searchField.setFont(searchField.getFont().deriveFont(14f));
        searchFieldDefaultBgColor = searchField.getBackground();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "输入功能描述，AI将分析项目结构并找出最相关的源代码文件");
        new TextStandardActions(searchField);

        // 搜索按钮
        JButton searchBtn = new JButton("开始搜索");
        searchBtn.setFont(searchBtn.getFont().deriveFont(14f));
        searchBtn.setPreferredSize(new Dimension(120, 40));
        searchBtn.addActionListener(e -> searchEmitter.emitSearch());

        searchPanel.add(searchField);
        searchPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        searchPanel.add(searchBtn);

        searchLinePanel.add(packagePanel);
        searchLinePanel.add(Box.createRigidArea(new Dimension(0, 5)));
        searchLinePanel.add(searchPanel);

        // AI响应区域
        aiResponseArea = new JTextArea();
        aiResponseArea.setFont(new Font("Dialog", Font.PLAIN, 14));
        aiResponseArea.setEditable(false);
        aiResponseArea.setLineWrap(true);
        aiResponseArea.setWrapStyleWord(true);
        aiResponseArea.setBackground(new Color(245, 245, 245));

        JScrollPane aiScrollPane = new JScrollPane(aiResponseArea);
        aiScrollPane.setPreferredSize(new Dimension(600, 200));
        aiScrollPane.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("AI分析结果"),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        // 添加搜索面板到顶部
        contentPanel.add(searchLinePanel, BorderLayout.NORTH);

        // 创建中间面板，包含AI响应区域和结果表格
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BorderLayout(5, 5));

        // 创建AI响应区域面板
        JPanel aiPanel = new JPanel(new BorderLayout());
        aiPanel.add(aiScrollPane, BorderLayout.CENTER);

        // 创建结果表格面板
        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.add(warnLabel, BorderLayout.NORTH);
        resultsPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        resultsPanel.add(progressPane, BorderLayout.SOUTH);

        // 将AI响应区域和结果表格添加到中间面板
        centerPanel.add(aiPanel, BorderLayout.NORTH);
        centerPanel.add(resultsPanel, BorderLayout.CENTER);

        // 添加状态信息标签
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.add(resultsInfoLabel, BorderLayout.CENTER);
        centerPanel.add(statusPanel, BorderLayout.SOUTH);

        // 添加中间面板到主面板
        contentPanel.add(centerPanel, BorderLayout.CENTER);

        // 添加按钮面板到底部
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        contentPanel.add(buttonPanel, BorderLayout.SOUTH);

        // 设置内容面板
        setContentPane(contentPanel);



        // 调整窗口大小和位置
        pack();
        setSize(800, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }

    private class SearchEventEmitter {
        private final Flowable<String> flowable;
        private Emitter<String> emitter;

        public SearchEventEmitter() {
            flowable = Flowable.create(this::saveEmitter, BackpressureStrategy.LATEST);
        }

        public Flowable<String> getFlowable() {
            return flowable;
        }

        private void saveEmitter(Emitter<String> emitter) {
            this.emitter = emitter;
        }

        public synchronized void emitSearch() {
            if (this.emitter == null) {
                LOG.error("搜索事件发射器未初始化");
                UiUtils.uiRun(() -> resultsInfoLabel.setText("搜索功能初始化失败，请重试"));
                return;
            }
            String searchText = searchField.getText();
            if (searchText == null || searchText.trim().isEmpty()) {
                UiUtils.uiRun(() -> resultsInfoLabel.setText("请输入搜索内容"));
                return;
            }
            this.emitter.onNext(searchText);
        }
    }
}
