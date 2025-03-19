package jadx.gui.ui.dialog;


import com.formdev.flatlaf.FlatClientProperties;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Emitter;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import jadx.api.JavaClass;
import jadx.gui.search.SearchTask;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResSearchNode;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.ProgressPanel;
import jadx.gui.utils.JumpPosition;
import jadx.gui.utils.NLS;
import jadx.gui.utils.TextStandardActions;
import jadx.gui.utils.UiUtils;
import jadx.gui.utils.ai.AIHttpUtils;
import jadx.gui.utils.rx.RxUtils;
import jadx.gui.jobs.ITaskProgress;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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

import static jadx.gui.ui.dialog.SearchDialog.SearchOptions.COMMENT;

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
        try {
            // 先停止所有搜索任务
            if (searchDisposable != null && !searchDisposable.isDisposed()) {
                searchDisposable.dispose();
                searchDisposable = null;
            }
            
            // 移除活动标签页监听器
            removeActiveTabListener();
            
            // 停止搜索任务
            stopSearchTask();
            
            // 清理AI相关资源
            if (aiHttpUtils != null) {
                aiHttpUtils.cancelAllRequests();  // 取消所有正在进行的AI请求
                aiHttpUtils = null;
            }
            
            // 清理其他资源
            allFilePaths.clear();
            classDescriptions.clear();
            if (aiResponseArea != null) {
                aiResponseArea.setText("");
            }
            
            // 等待后台任务完成
            searchBackgroundExecutor.execute(() -> {
                try {
                    mainWindow.getBackgroundExecutor().waitForComplete();
                    unloadTempData();
                } catch (Exception e) {
                    // 忽略关闭时的异常
                }
            });
        } catch (Exception e) {
            // 忽略关闭时的异常
        } finally {
            super.dispose();
        }
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
        systemPrompt = "你是一个专业的Java代码分析专家，负责精确理解用户查询与Java类之间的语义关联。\n\n" +
                "核心任务：\n" +
                "1. 深度分析用户查询：\n" +
                "   - 提取核心功能需求和关键概念\n" +
                "   - 识别领域特定术语\n" +
                "   - 理解查询的上下文和目的\n\n" +
                "2. 类名语义分析：\n" +
                "   - 深入分析类名中的每个单词\n" +
                "   - 理解类名暗示的功能和职责\n" +
                "   - 评估类名与查询的语义匹配度\n\n" +
                "3. 严格的匹配规则：\n" +
                "   - 类名必须直接反映查询的核心功能\n" +
                "   - 拒绝间接或模糊相关的类\n" +
                "   - 每个返回的类必须能解释其相关性\n\n" +
                "4. 输出格式要求：\n" +
                "   必须返回JSON格式数据，格式如下：\n" +
                "   {\n" +
                "     \"status\": \"found\",  // 或 \"not_found\"\n" +
                "     \"results\": [\n" +
                "       {\n" +
                "         \"className\": \"完整类名\",\n" +
                "         \"relevance\": \"相关性解释（至少10个字符）\"\n" +
                "       }\n" +
                "     ]\n" +
                "   }\n" +
                "   如果没有找到相关类，则返回：\n" +
                "   {\"status\": \"not_found\"}\n\n" +
                "5. 禁止事项：\n" +
                "   - 禁止返回任何不在候选列表中的类名\n" +
                "   - 禁止返回泛型工具类（除非查询特指）\n" +
                "   - 禁止基于包名进行推测\n" +
                "   - 禁止返回非JSON格式的内容\n\n";
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
            // 重置AI工具状态
            aiHttpUtils.reset();
            
            // 重新收集并过滤文件列表
            collectAllFilePaths();

            // 清空之前的结果
            UiUtils.uiRun(() -> {
                aiResponseArea.setText("");
                resultsInfoLabel.setText("AI正在分析...");
                aiSearchTableModel.clear();
                progressPane.setProgress(new ITaskProgress() {
                    @Override
                    public int progress() {
                        return 0;
                    }
                    @Override
                    public int total() {
                        return 100;
                    }
                });
                progressPane.setVisible(true);
            });

            // 将所有类名分批处理
            int classesPerBatch = 300; // 增加每批处理的类数量
            Set<String> allRelevantClasses = new HashSet<>();

            // 计算总批次
            int totalBatches = (allFilePaths.size() + classesPerBatch - 1) / classesPerBatch;

            // 更新状态信息，显示当前正在分析的包
            final String packageFilter = packageField != null ? packageField.getText().trim() : "";
            if (!packageFilter.isEmpty()) {
                UiUtils.uiRun(() -> {
                    resultsInfoLabel.setText(String.format("正在分析 %s 包下的类（共 %d 批）...",
                        packageFilter, totalBatches));
                });
            } else {
                UiUtils.uiRun(() -> {
                    resultsInfoLabel.setText(String.format("正在分析所有类（共 %d 批）...", totalBatches));
                });
            }

            for (int i = 0; i < allFilePaths.size(); i += classesPerBatch) {
                final int currentBatch = i / classesPerBatch + 1;
                int end = Math.min(i + classesPerBatch, allFilePaths.size());
                List<String> batch = allFilePaths.subList(i, end);

                // 更新进度信息
                final int currentBatchFinal = currentBatch;
                UiUtils.uiRun(() -> {
                    resultsInfoLabel.setText(String.format("正在分析第 %d/%d 批（每批 %d 个类）...",
                        currentBatchFinal, totalBatches, classesPerBatch));
                    progressPane.setProgress(new ITaskProgress() {
                        @Override
                        public int progress() {
                            return currentBatchFinal * 100 / totalBatches;
                        }
                        @Override
                        public int total() {
                            return 100;
                        }
                    });
                });

                // 构建当前批次的消息
                List<Map<String, String>> messages = new ArrayList<>();
                String currentPrompt = systemPrompt + "\n当前批次类名列表（第" + currentBatch + "批，共" +
                    totalBatches + "批）：\n" +
                    String.join("\n", batch);

                messages.add(AIHttpUtils.createMessage("system", currentPrompt));
                messages.add(AIHttpUtils.createMessage("user",
                        "执行严格语义匹配任务：\n\n" +
                        "用户查询：\n" + query + "\n\n" +
                        "候选类列表（必须严格从此列表选择）：\n" + String.join("\n", batch) + "\n\n" +
                        "严格要求：\n" +
                        "1. 必须返回规定的JSON格式\n" +
                        "2. 只能返回上述候选列表中的类名\n" +
                        "3. 每个类名必须附带相关性解释\n" +
                        "4. 没有匹配时返回 {\"status\": \"not_found\"}"));

                // 使用StringBuffer来累积当前批次的分析结果
                StringBuffer jsonBuffer = new StringBuffer();
                Gson gson = new Gson();

                // AI分析当前批次
                aiHttpUtils.createStreamingChatCompletionWithCallback(
                        null,
                        mainWindow.getSettings().getAiModel(),
                        messages,
                        TEMPERATURE,
                        MAX_TOKENS,
                        content -> {
                            jsonBuffer.append(content);

                            try {
                                String jsonStr = jsonBuffer.toString().trim();
                                // 确保收集到完整的JSON
                                if (jsonStr.endsWith("}")) {
                                    // 使用正则表达式清理可能的前缀文本
                                    jsonStr = jsonStr.replaceAll("^[^{]*", "");

                                    System.out.println("收到AI响应 [批次 " + currentBatch + "/" + totalBatches + "]:");
                                    System.out.println(jsonStr);
                                    System.out.println("------------------------");

                                    JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

                                    if (root.has("status")) {
                                        String status = root.get("status").getAsString();

                                        if ("not_found".equals(status)) {
                                            UiUtils.uiRun(() -> {
                                                aiResponseArea.setText("本批次未找到相关类");
                                            });
                                            // 清空buffer，准备接收下一个响应
                                            jsonBuffer.setLength(0);
                                            return;
                                        }

                                        if ("found".equals(status) && root.has("results")) {
                                            JsonArray results = root.getAsJsonArray("results");
                                            Set<String> batchValidClasses = new HashSet<>();

                                            for (JsonElement result : results) {
                                                JsonObject resultObj = result.getAsJsonObject();
                                                String className = resultObj.get("className").getAsString();
                                                String relevance = resultObj.get("relevance").getAsString();

                                                System.out.println("处理结果: className=" + className + ", relevance=" + relevance);

                                                if (validateClassName(className) && batch.contains(className)) {
                                                    batchValidClasses.add(className);
                                                    // 保存类的描述信息
                                                    processAiResponse(className, relevance);

                                                    String finalText = String.format("[批次 %d/%d] 找到相关类: %s\n相关性: %s\n\n",
                                                        currentBatchFinal, totalBatches, className, relevance);
                                                    UiUtils.uiRun(() -> {
                                                        aiResponseArea.append(finalText);
                                                        aiResponseArea.setCaretPosition(
                                                            aiResponseArea.getDocument().getLength());
                                                    });
                                                }
                                            }

                                            // 清空buffer，准备接收下一个响应
                                            jsonBuffer.setLength(0);

                                            if (!batchValidClasses.isEmpty()) {
                                                System.out.println("\n=== 批次结果处理 ===");
                                                System.out.println("当前批次找到的类数量: " + batchValidClasses.size());

                                                // 记录添加前的总数
                                                int beforeSize = allRelevantClasses.size();
                                                allRelevantClasses.addAll(batchValidClasses);
                                                int afterSize = allRelevantClasses.size();

                                                System.out.println("结果累积统计:");
                                                System.out.println("- 添加前总数: " + beforeSize);
                                                System.out.println("- 添加后总数: " + afterSize);
                                                System.out.println("- 新增类数量: " + (afterSize - beforeSize));
                                                System.out.println("=================\n");

                                                updateResultsTable(allRelevantClasses, currentBatchFinal, totalBatches);
                                            } else {
                                                // 即使没有找到类，也更新进度
                                                UiUtils.uiRun(() -> {
                                                    resultsInfoLabel.setText(String.format("批次 %d/%d 分析完成，暂未找到相关类",
                                                        currentBatchFinal, totalBatches));
                                                });
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                LOG.debug("JSON解析未完成或格式错误: {}", e.getMessage());
                            }
                        });

                // 等待当前批次处理完成
                try {
                    Thread.sleep(100);  // 给UI更新一个小的间隔
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 搜索完成后的最终更新
            final int totalResults = allRelevantClasses.size();
            UiUtils.uiRun(() -> {
                if (totalResults > 0) {
                    resultsInfoLabel.setText(String.format("搜索完成，共找到 %d 个相关类", totalResults));
                } else {
                    resultsInfoLabel.setText("搜索完成，未找到相关类");
                }
                progressPane.setVisible(false);
            });

        } catch (Exception e) {
            LOG.error("AI搜索失败", e);
            UiUtils.uiRun(() -> {
                resultsInfoLabel.setText("AI搜索失败: " + e.getMessage());
                aiResponseArea.setText("AI搜索失败: " + e.getMessage() + "\n\n详细错误信息：\n" + e.toString());
            });
        }
    }

    private boolean validateClassName(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }

        // 基本格式检查
        if (!className.contains(".") ||
            className.startsWith(".") ||
            className.endsWith(".") ||
            className.contains("..")) {
            return false;
        }

        // 检查是否包含无效字符
        if (className.contains(" ") ||
            className.contains("-") ||
            className.contains("*") ||
            className.contains("?") ||
            className.contains("!")) {
            return false;
        }

        // 验证Java命名规范
        String[] parts = className.split("\\.");
        for (String part : parts) {
            if (!part.matches("^[a-zA-Z_$][a-zA-Z0-9_$]*$")) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void openItem(JNode node) {
        if (mainWindow.getSettings().isUseAutoSearch()) {
            // for auto search save only searches which leads to node opening
            mainWindow.getProject().addToSearchHistory(searchField.getText());
        }
        // 获取选中的行
        int selectedRow = aiSearchTable.getSelectedRow();
        if (selectedRow != -1) {
            String className = (String) aiSearchTable.getValueAt(selectedRow, 0);
            JavaClass javaClass = mainWindow.getWrapper().searchJavaClassByFullAlias(className);

            if (javaClass != null) {
                JClass jClass = new JClass(javaClass, null, mainWindow.getCacheObject().getNodeCache());

                if (jClass != null) {
                    // 使用正确的方式打开代码标签页

					if (node instanceof JResSearchNode) {
						JumpPosition jmpPos = new JumpPosition(((JResSearchNode) node).getResNode(), node.getPos());
						tabsController.codeJump(jmpPos);
					} else {
						tabsController.codeJump(node);
					}
                    //mainWindow.getTabbedPane().addTab(jClass.getFullName(), jClass.getCodeArea());
                }
            }
        }
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
        aiSearchTable.setEnabled(true);
        searchField.setEnabled(true);
        searchEmitter.emitSearch();
    }

    @Override
    protected void loadStart() {
        aiSearchTable.setEnabled(false);
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
        aiSearchTableModel = new AiSearchTableModel();
        aiSearchTable = new AiSearchTable(aiSearchTableModel);

        // 添加表格点击事件监听器
        aiSearchTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) { // 双击事件
                    int row = aiSearchTable.getSelectedRow();
                    if (row != -1) {
                        String className = (String) aiSearchTable.getValueAt(row, 0);
                        JavaClass javaClass = mainWindow.getWrapper().searchJavaClassByFullAlias(className);
                        if (javaClass != null) {
                            JClass jClass = new JClass(javaClass, null, mainWindow.getCacheObject().getNodeCache());
                            if (jClass != null) {
                                openItem(jClass);
                            }
                        }
                    }
                }
            }
        });

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
        JPanel packagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        packagePanel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel packageLabel = new JLabel("包名：");
        packageField.setFont(packageField.getFont().deriveFont(14f));
        packageField.setPreferredSize(new Dimension(300, 35));
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
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        searchPanel.setAlignmentX(LEFT_ALIGNMENT);

        JLabel searchLabel = new JLabel("搜索：");
        searchField = new JTextField();
        searchField.setFont(searchField.getFont().deriveFont(14f));
        searchField.setPreferredSize(new Dimension(400, 35));
        searchFieldDefaultBgColor = searchField.getBackground();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "输入功能描述，AI将分析项目结构并找出最相关的源代码文件");
        new TextStandardActions(searchField);

        // 搜索按钮
        JButton searchBtn = new JButton("开始搜索");
        searchBtn.setFont(searchBtn.getFont().deriveFont(14f));
        searchBtn.setPreferredSize(new Dimension(100, 35));
        searchBtn.addActionListener(e -> searchEmitter.emitSearch());

        searchPanel.add(searchLabel);
        searchPanel.add(searchField);
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
        resultsPanel.add(new JScrollPane(aiSearchTable), BorderLayout.CENTER);
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

    private void updateResultsTable(Set<String> classNames, int currentBatch, int totalBatches) {
        System.out.println("\n=== 更新结果表格 ===");
        System.out.println("当前批次: " + currentBatch + "/" + totalBatches);
        System.out.println("待处理类总数: " + classNames.size());

        List<AiSearchResult> results = new ArrayList<>();
        int successCount = 0;
        final int[] failCount = {0};

        for (String className : classNames) {
            try {
                JavaClass javaClass = mainWindow.getWrapper().searchJavaClassByFullAlias(className);
                if (javaClass != null) {
                    String description = classDescriptions.getOrDefault(className, "未找到相关描述");
                    results.add(new AiSearchResult(className, description));
                    successCount++;
                } else {
                    System.out.println("警告: 找不到类: " + className);
                    failCount[0]++;
                }
            } catch (Exception e) {
                System.out.println("错误: 处理类时出错: " + className + ", 原因: " + e.getMessage());
                failCount[0]++;
            }
        }

        System.out.println("处理结果统计:");
        System.out.println("- 成功添加类数量: " + successCount);
        System.out.println("- 处理失败类数量: " + failCount[0]);
        System.out.println("=================\n");

        final int finalSuccessCount = successCount;
        UiUtils.uiRun(() -> {
            aiSearchTableModel.clear();
            aiSearchTableModel.addAll(results);
            if (currentBatch == totalBatches) {
                resultsInfoLabel.setText(String.format("搜索完成，成功加载 %d 个相关类%s",
                    finalSuccessCount,
                    failCount[0] > 0 ? String.format("（%d个类加载失败）", failCount[0]) : ""));
                progressPane.setVisible(false);
            } else {
                resultsInfoLabel.setText(String.format("批次 %d/%d，已找到 %d 个相关类%s",
                    currentBatch, totalBatches, finalSuccessCount,
                    failCount[0] > 0 ? String.format("（%d个类加载失败）", failCount[0]) : ""));
            }
        });
    }

    private class AiSearchResult {
        private final String filePath;
        private final String description;

        public AiSearchResult(String filePath, String description) {
            this.filePath = filePath;
            this.description = description;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getDescription() {
            return description;
        }
    }

    private class AiSearchTableModel extends AbstractTableModel {
        private final List<AiSearchResult> results = new ArrayList<>();
        private final String[] columnNames = {"文件路径", "描述"};

        @Override
        public int getRowCount() {
            return results.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AiSearchResult result = results.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return result.getFilePath();
                case 1:
                    return result.getDescription();
                default:
                    return null;
            }
        }

        public void clear() {
            results.clear();
            fireTableDataChanged();
        }

        public void addAll(List<AiSearchResult> newResults) {
            results.addAll(newResults);
            fireTableDataChanged();
        }
    }

    private class AiSearchTable extends JTable {
        public AiSearchTable(AiSearchTableModel model) {
            super(model);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
            setShowGrid(true);
            setGridColor(new Color(230, 230, 230));
            setRowHeight(25);
            setFont(new Font("Dialog", Font.PLAIN, 14));
            getTableHeader().setFont(new Font("Dialog", Font.BOLD, 14));
            getTableHeader().setBackground(new Color(245, 245, 245));
            getTableHeader().setForeground(new Color(75, 75, 75));
        }
    }

    private AiSearchTableModel aiSearchTableModel;
    private AiSearchTable aiSearchTable;
    private Map<String, String> classDescriptions = new HashMap<>();

    // 在处理AI响应时保存类描述
    private void processAiResponse(String className, String relevance) {
        classDescriptions.put(className, relevance);
    }
}
