package com.family.quizparent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** 手机端家长控制台：连接电视、查看状态、更新题库、控制放行、查看答题统计。 */
public class MainActivity extends Activity {

    private static final String PREFS = "quizparent";
    private static final int REQ_PICK_JSON = 21;

    /** 电视端常见受限应用，用于「管理受限应用」对话框的候选列表。 */
    private static final String[][] KNOWN_APPS = {
            {"com.cibn.tv", "未来电视"},
            {"com.newtv.cboxtv", "银河奇异果"},
            {"com.xiaodianshi.tv.yst", "小电视"},
            {"com.gitvdemo.video", "系统视频演示"},
            {"com.hisilicon.android.videoplayer", "本地视频播放器"}
    };

    /** 「复制模板」写入剪贴板的 v3 多学科 JSON（两个学科，共 6 题，可直接通过校验）。 */
    private static final String TEMPLATE_JSON =
            "{\n"
            + "  \"version\": 3,\n"
            + "  \"title\": \"示例总题库（请改成你的标题）\",\n"
            + "  \"subjects\": [\n"
            + "    {\n"
            + "      \"key\": \"english\",\n"
            + "      \"name\": \"英语\",\n"
            + "      \"questions\": [\n"
            + "        {\"id\": \"en-001\", \"q\": \"Which word is different?\", \"options\": [\"apple\", \"banana\", \"carrot\", \"orange\"], \"answer\": 2},\n"
            + "        {\"id\": \"en-002\", \"q\": \"Choose the correct sentence.\", \"options\": [\"She go to school.\", \"She goes to school.\", \"She going to school.\"], \"answer\": 1},\n"
            + "        {\"id\": \"en-003\", \"q\": \"What is the past tense of \\\"eat\\\"?\", \"options\": [\"ate\", \"eated\", \"eaten\"], \"answer\": 0}\n"
            + "      ]\n"
            + "    },\n"
            + "    {\n"
            + "      \"key\": \"math\",\n"
            + "      \"name\": \"数学\",\n"
            + "      \"questions\": [\n"
            + "        {\"id\": \"math-001\", \"q\": \"3 + 5 = ?\", \"options\": [\"6\", \"7\", \"8\", \"9\"], \"answer\": 2},\n"
            + "        {\"id\": \"math-002\", \"q\": \"12 × 3 = ?\", \"options\": [\"24\", \"36\", \"48\"], \"answer\": 1},\n"
            + "        {\"id\": \"math-003\", \"q\": \"一个正方形的边长是 4 厘米，面积是多少？\", \"options\": [\"8 平方厘米\", \"12 平方厘米\", \"16 平方厘米\"], \"answer\": 2}\n"
            + "      ]\n"
            + "    }\n"
            + "  ]\n"
            + "}";

    /** 「让 AI 生成题库」卡片里可编辑的提示词（v3 多学科）。 */
    private static final String AI_PROMPT =
            "你是一位出题老师，请按下面的规则生成题库，直接输出纯 JSON 文本。\n\n"
            + "【输出格式】\n"
            + "1. 只输出纯 JSON，不要任何解释文字，不要 Markdown 代码块标记（不要 ```json），不要注释。\n"
            + "2. 顶层字段：\n"
            + "   - version：整数，固定填 3\n"
            + "   - title：字符串，总题库标题\n"
            + "   - subjects：学科数组，至少 1 个学科\n"
            + "3. 每个学科包含：\n"
            + "   - key：字符串，学科唯一标识，只用小写字母、数字、短横线，例如 english、math、chinese\n"
            + "   - name：字符串，学科中文名称，例如 英语、数学、语文\n"
            + "   - questions：该学科的题目数组\n"
            + "4. 每道题包含：\n"
            + "   - id：字符串，全库唯一编号，建议用 学科-单元-序号，例如 en-u3-001\n"
            + "   - q：题干，字符串，最多 200 字\n"
            + "   - options：选项数组，2 到 6 个字符串\n"
            + "   - answer：正确选项的下标，从 0 开始计数\n\n"
            + "【数量规则】\n"
            + "- 全库总题数至少 5 道，最多 500 道。\n"
            + "- 每个学科的 questions 不能为空。\n\n"
            + "【内容规则】\n"
            + "- 中文题保持中文；英文题保留原始大小写与标点，句首字母大写，专有名词大写。\n"
            + "- 选项之间不得只有大小写不同，除非题目本身就是在考大小写。\n"
            + "- 不要重复题干。\n"
            + "- 题干必须自包含，不能依赖图片、听力音频或外部资料。\n"
            + "- answer 必须指向一个真实存在的选项。\n"
            + "- 每道题的 id 在全库内不能重复。\n\n"
            + "【本次出题要求】\n"
            + "学科：{学科}\n"
            + "年级：{年级}\n"
            + "知识点：{知识点}\n"
            + "题量：{题量}\n\n"
            + "【示例结构】\n"
            + "{\n"
            + "  \"version\": 3,\n"
            + "  \"title\": \"示例总题库\",\n"
            + "  \"subjects\": [\n"
            + "    {\"key\": \"english\", \"name\": \"英语\", \"questions\": [\n"
            + "      {\"id\": \"en-001\", \"q\": \"Which word is different?\", \"options\": [\"apple\", \"banana\", \"carrot\", \"orange\"], \"answer\": 2}\n"
            + "    ]},\n"
            + "    {\"key\": \"math\", \"name\": \"数学\", \"questions\": [\n"
            + "      {\"id\": \"math-001\", \"q\": \"3 + 5 = ?\", \"options\": [\"6\", \"7\", \"8\", \"9\"], \"answer\": 2}\n"
            + "    ]}\n"
            + "  ]\n"
            + "}";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> labels = new LinkedHashMap<String, String>();

    private ExecutorService io;
    private SharedPreferences sp;

    private EditText etIp;
    private EditText etPort;
    private EditText etPin;
    private Button btnConnect;
    private Button btnScan;
    private Button btnRefresh;
    private Button btnTabOverview;
    private Button btnTabBank;
    private Button btnTabStats;
    private Button btnPick;
    private Button btnPickSubject;
    private Button btnUpload;
    private Button btnRollback;
    private Button btnCopyTemplate;
    private Button btnPasteUpload;
    private Button btnCopyPrompt;
    private Button btnApps;
    private Button btnTarget;
    private Button btnPass30;
    private Button btnRevoke;
    private Button btnClearRecords;
    private Button btnStatsRefresh;
    private Button btnResetStats;
    private Button btnRetryConn;
    private Button btnRetryStats;
    private SeekBar sbMinutes;
    private SeekBar sbPassLine;
    private SeekBar sbThreshold;
    private Switch swAutoRemove;
    private TextView tvMinutesLabel;
    private TextView tvPassLineLabel;
    private TextView tvThresholdLabel;
    private TextView tvConnState;
    private TextView tvConnBadge;
    private TextView tvChipVersion;
    private TextView tvChipMinutes;
    private TextView tvChipPass;
    private TextView tvChipRestricted;
    private TextView tvStatusMain;
    private TextView tvSubjectSummary;
    private TextView tvSubjectHint;
    private TextView tvPasses;
    private TextView tvRecords;
    private TextView tvFileInfo;
    private TextView tvPasteState;
    private TextView tvAppsInfo;
    private TextView tvPromptArrow;
    private TextView tvStatsState;
    private LinearLayout promptContent;
    private LinearLayout subjectList;
    private LinearLayout statSubjects;
    private LinearLayout statQuestions;
    private LinearLayout panelOverview;
    private LinearLayout panelBank;
    private LinearLayout panelStats;
    private View rowPromptToggle;
    private EditText etPaste;
    private EditText etPrompt;

    private TvClient client;
    private boolean connected;
    private JSONObject summary;
    private JSONObject statsData;
    private JSONArray subjects;
    private int minutes = 30;
    private int passLine = 4;
    private int threshold = 3;
    private boolean autoRemove;
    private boolean suppressSlider;
    private boolean suppressSwitch;
    private boolean suppressSubjectSwitch;
    private boolean destroyed;
    private String selectedPkg;
    private String pickedPayload;
    private int pickedCount;
    private String pendingSubjectKey;
    private String pendingSubjectName;
    private LanScanner scanner;
    private boolean scanning;
    private int currentTab;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        io = Executors.newSingleThreadExecutor();
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        bindViews();
        loadLabels();
        restoreConnection();
        setListeners();
        selectTab(0);
        renderSubjects();
        updateActionStates();

        if (sp.getBoolean("auto", false) && !text(etIp).isEmpty()) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed && !isFinishing()) {
                        connect();
                    }
                }
            }, 350);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (scanner != null) {
            scanner.cancel();
        }
        if (io != null) {
            io.shutdownNow();
        }
        super.onDestroy();
    }

    /** 页面销毁（例如旋转/改分辨率）后任务不能再丢进已关闭的线程池。 */
    private void enqueue(Runnable task) {
        if (destroyed || io == null || io.isShutdown()) {
            return;
        }
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 线程池恰好被关闭，忽略这次请求
        }
    }

    private void bindViews() {
        etIp = (EditText) findViewById(R.id.et_ip);
        etPort = (EditText) findViewById(R.id.et_port);
        etPin = (EditText) findViewById(R.id.et_pin);
        btnConnect = (Button) findViewById(R.id.btn_connect);
        btnScan = (Button) findViewById(R.id.btn_scan);
        btnRefresh = (Button) findViewById(R.id.btn_refresh);
        btnTabOverview = (Button) findViewById(R.id.btn_tab_overview);
        btnTabBank = (Button) findViewById(R.id.btn_tab_bank);
        btnTabStats = (Button) findViewById(R.id.btn_tab_stats);
        btnPick = (Button) findViewById(R.id.btn_pick);
        btnPickSubject = (Button) findViewById(R.id.btn_pick_subject);
        btnUpload = (Button) findViewById(R.id.btn_upload);
        btnRollback = (Button) findViewById(R.id.btn_rollback);
        btnCopyTemplate = (Button) findViewById(R.id.btn_copy_template);
        btnPasteUpload = (Button) findViewById(R.id.btn_paste_upload);
        btnCopyPrompt = (Button) findViewById(R.id.btn_copy_prompt);
        btnApps = (Button) findViewById(R.id.btn_apps);
        btnTarget = (Button) findViewById(R.id.btn_target);
        btnPass30 = (Button) findViewById(R.id.btn_pass30);
        btnRevoke = (Button) findViewById(R.id.btn_revoke);
        btnClearRecords = (Button) findViewById(R.id.btn_clear_records);
        btnStatsRefresh = (Button) findViewById(R.id.btn_stats_refresh);
        btnResetStats = (Button) findViewById(R.id.btn_reset_stats);
        btnRetryConn = (Button) findViewById(R.id.btn_retry_conn);
        btnRetryStats = (Button) findViewById(R.id.btn_retry_stats);
        sbMinutes = (SeekBar) findViewById(R.id.sb_minutes);
        sbPassLine = (SeekBar) findViewById(R.id.sb_passline);
        sbThreshold = (SeekBar) findViewById(R.id.sb_threshold);
        swAutoRemove = (Switch) findViewById(R.id.sw_autoremove);
        tvMinutesLabel = (TextView) findViewById(R.id.tv_minutes_label);
        tvPassLineLabel = (TextView) findViewById(R.id.tv_passline_label);
        tvThresholdLabel = (TextView) findViewById(R.id.tv_threshold_label);
        tvConnState = (TextView) findViewById(R.id.tv_conn_state);
        tvConnBadge = (TextView) findViewById(R.id.tv_conn_badge);
        tvChipVersion = (TextView) findViewById(R.id.tv_chip_version);
        tvChipMinutes = (TextView) findViewById(R.id.tv_chip_minutes);
        tvChipPass = (TextView) findViewById(R.id.tv_chip_pass);
        tvChipRestricted = (TextView) findViewById(R.id.tv_chip_restricted);
        tvStatusMain = (TextView) findViewById(R.id.tv_status_main);
        tvSubjectSummary = (TextView) findViewById(R.id.tv_subject_summary);
        tvSubjectHint = (TextView) findViewById(R.id.tv_subject_hint);
        tvPasses = (TextView) findViewById(R.id.tv_passes);
        tvRecords = (TextView) findViewById(R.id.tv_records);
        tvFileInfo = (TextView) findViewById(R.id.tv_file_info);
        tvPasteState = (TextView) findViewById(R.id.tv_paste_state);
        tvAppsInfo = (TextView) findViewById(R.id.tv_apps_info);
        tvPromptArrow = (TextView) findViewById(R.id.tv_prompt_arrow);
        tvStatsState = (TextView) findViewById(R.id.tv_stats_state);
        promptContent = (LinearLayout) findViewById(R.id.prompt_content);
        subjectList = (LinearLayout) findViewById(R.id.subject_list);
        statSubjects = (LinearLayout) findViewById(R.id.stat_subjects);
        statQuestions = (LinearLayout) findViewById(R.id.stat_questions);
        panelOverview = (LinearLayout) findViewById(R.id.panel_overview);
        panelBank = (LinearLayout) findViewById(R.id.panel_bank);
        panelStats = (LinearLayout) findViewById(R.id.panel_stats);
        rowPromptToggle = findViewById(R.id.row_prompt_toggle);
        etPaste = (EditText) findViewById(R.id.et_paste);
        etPrompt = (EditText) findViewById(R.id.et_prompt);
        etPrompt.setText(AI_PROMPT);

        updateMinuteLabel(30);
        updatePassLineLabel(4);
        updateThresholdLabel(3);
    }

    private void restoreConnection() {
        etIp.setText(sp.getString("ip", "192.168.1.50"));
        etPort.setText(String.valueOf(sp.getInt("port", 8080)));
        etPin.setText(sp.getString("pin", ""));
    }

    private void setListeners() {
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshSummary(true);
            }
        });
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scanning) {
                    stopScan();
                } else {
                    startScan();
                }
            }
        });
        btnTabOverview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(0);
            }
        });
        btnTabBank.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(1);
            }
        });
        btnTabStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(2);
                if (connected && statsData == null) {
                    refreshStats(false);
                }
            }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingSubjectKey = null;
                pendingSubjectName = null;
                pickJsonFile();
            }
        });
        btnPickSubject.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSubjectPicker();
            }
        });
        btnUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadQuestions();
            }
        });
        btnRollback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRollback();
            }
        });
        btnCopyTemplate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("模板", TEMPLATE_JSON);
            }
        });
        btnPasteUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                validateAndUploadPasted();
            }
        });
        btnCopyPrompt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard("提示词", etPrompt.getText().toString());
            }
        });
        rowPromptToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePromptCard();
            }
        });
        etPaste.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    normalizePastedText();
                }
            }
        });
        btnApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppsDialog();
            }
        });
        btnTarget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTargetDialog();
            }
        });
        btnPass30.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doPass();
            }
        });
        btnRevoke.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRevoke();
            }
        });
        btnClearRecords.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearRecords();
            }
        });
        btnStatsRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStats(true);
            }
        });
        btnResetStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmResetStats();
            }
        });
        btnRetryConn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (connected) {
                    refreshSummary(true);
                } else {
                    connect();
                }
            }
        });
        btnRetryStats.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStats(true);
            }
        });

        sbMinutes.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateMinuteLabel(progress + 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (suppressSlider) {
                    return;
                }
                int value = seekBar.getProgress() + 1;
                saveSetting("minutes", value, "放行时长已更新为 " + value + " 分钟");
            }
        });

        sbPassLine.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePassLineLabel(progress + 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (suppressSlider) {
                    return;
                }
                int value = seekBar.getProgress() + 1;
                saveSetting("passLine", value, "及格线已更新为需答对 " + value + " 题");
            }
        });

        sbThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateThresholdLabel(progress + 1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (suppressSlider) {
                    return;
                }
                int value = seekBar.getProgress() + 1;
                saveStatsSetting("masteredThreshold", value,
                        "掌握阈值已更新为答对 " + value + " 次");
            }
        });

        swAutoRemove.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppressSwitch || !connected) {
                    return;
                }
                saveAutoRemove(isChecked);
            }
        });
    }

    // ---------------- 页面切换 ----------------

    private void selectTab(int index) {
        currentTab = index;
        panelOverview.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelBank.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelStats.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        styleTab(btnTabOverview, index == 0);
        styleTab(btnTabBank, index == 1);
        styleTab(btnTabStats, index == 2);
    }

    private void styleTab(Button button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_tab_on : R.drawable.bg_btn_ghost);
        button.setTextColor(getColor(selected ? R.color.accent : R.color.text2));
        button.setTextSize(15);
        button.setText(button == btnTabOverview ? "总览" : button == btnTabBank ? "题库" : "统计");
    }

    // ---------------- 连接 ----------------

    private void connect() {
        final String host = text(etIp);
        final int port = parsePort(text(etPort));
        final String pin = text(etPin);
        if (host.isEmpty()) {
            toast("请填写电视 IP");
            return;
        }
        if (port <= 0) {
            toast("端口需要是 1~65535 的数字");
            return;
        }
        if (!pin.matches("[0-9]{4}")) {
            toast("PIN 是 4 位数字，请在电视「家长设置」里查看");
            return;
        }

        final TvClient candidate = new TvClient(host, port, pin);
        connected = false;
        btnConnect.setEnabled(false);
        tvConnState.setTextColor(getColor(R.color.text2));
        tvConnState.setText("正在连接 " + host + ":" + port + " …");
        updateActionStates();

        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response = candidate.summary();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        btnConnect.setEnabled(true);
                        if (!response.ok) {
                            failConnect(response.error);
                            return;
                        }
                        JSONObject json = response.json();
                        if (json == null) {
                            failConnect("电视端返回的数据不是 JSON。电视端版本过低，请升级到 v2.8");
                            return;
                        }
                        client = candidate;
                        connected = true;
                        sp.edit()
                                .putString("ip", host)
                                .putInt("port", port)
                                .putString("pin", pin)
                                .putBoolean("auto", true)
                                .apply();
                        applySummary(json);
                        String version = str(json, "version", "未知");
                        tvConnState.setTextColor(getColor(R.color.ok));
                        tvConnState.setText("已连接 " + host + ":" + port + " · 电视端 v" + version);
                        tvConnBadge.setText("已连接 v" + version);
                        tvConnBadge.setTextColor(getColor(R.color.ok));
                        tvConnBadge.setBackgroundResource(R.drawable.bg_chip_ok);
                        toast("已连接电视 v" + version);
                        btnRetryConn.setVisibility(View.GONE);
                        refreshStats(false);
                    }
                });
            }
        });
    }

    private void failConnect(String message) {
        connected = false;
        client = null;
        statsData = null;
        tvConnState.setTextColor(getColor(R.color.danger));
        tvConnState.setText("连接失败：" + message);
        tvConnBadge.setText("未连接");
        tvConnBadge.setTextColor(getColor(R.color.text2));
        tvConnBadge.setBackgroundResource(R.drawable.bg_chip_gray);
        tvStatusMain.setText("连接后显示题库概况、学科与放行状态。");
        tvPasses.setText("正在放行：—");
        tvRecords.setText("暂无答题记录");
        tvChipVersion.setText("版本 —");
        tvChipMinutes.setText("放行 —");
        tvChipPass.setText("及格 —");
        tvChipRestricted.setText("受限 —");
        tvSubjectSummary.setText("学科：—");
        subjects = null;
        renderSubjects();
        clearStats("未连接电视，暂无统计。");
        btnRetryConn.setVisibility(View.VISIBLE);
        btnRetryStats.setVisibility(View.GONE);
        updateActionStates();
        toast(message, true);
    }

    private void refreshSummary(final boolean showToast) {
        if (!ensureConnected()) {
            return;
        }
        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response = client.summary();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (!response.ok) {
                            showConnRetry(response.error);
                            if (showToast) {
                                toast(response.error, true);
                            }
                            return;
                        }
                        JSONObject json = response.json();
                        if (json == null) {
                            showConnRetry("电视端返回的数据不是 JSON，请升级到 v2.9");
                            if (showToast) {
                                toast("电视端返回的数据不是 JSON，请升级到 v2.9", true);
                            }
                            return;
                        }
                        btnRetryConn.setVisibility(View.GONE);
                        tvConnState.setTextColor(getColor(R.color.ok));
                        tvConnState.setText("已连接 " + text(etIp) + ":" + text(etPort)
                                + " · 电视端 v" + json.optString("version", "2.9"));
                        applySummary(json);
                        if (showToast) {
                            toast("已刷新电视端状态");
                        }
                    }
                });
            }
        });
        if (showToast) {
            refreshStats(false);
        }
    }

    /** 响应不完整/刷新失败时给一句中文 + 「重试」按钮，不静默失败。 */
    private void showConnRetry(String message) {
        tvConnState.setTextColor(getColor(R.color.danger));
        tvConnState.setText("刷新失败：" + message);
        btnRetryConn.setVisibility(View.VISIBLE);
    }

    // ---------------- 状态展示 ----------------

    private void applySummary(JSONObject json) {
        summary = json;
        connected = true;

        String version = str(json, "version", "未知");
        minutes = clamp(json.optInt("minutes", 30), 1, 120);
        passLine = clamp(json.optInt("passLine", 4), 1, 5);

        JSONArray restricted = json.optJSONArray("restricted");
        if (restricted == null) {
            restricted = new JSONArray();
        }
        List<String> packages = stringList(restricted);
        if (selectedPkg == null || !packages.contains(selectedPkg)) {
            selectedPkg = packages.isEmpty() ? null : packages.get(0);
        }

        JSONObject questions = json.optJSONObject("questions");
        int questionCount = questions == null ? 0 : questions.optInt("count", 0);
        String questionTitle = questions == null ? "" : str(questions, "title", "");
        String sourceText = sourceName(questions == null ? "" : str(questions, "source", ""));

        String subjectText = "";
        if (!questionTitle.isEmpty()) {
            subjectText = questionTitle;
        }
        if (!sourceText.isEmpty()) {
            subjectText = subjectText.isEmpty() ? "（" + sourceText + "）"
                    : subjectText + "（" + sourceText + "）";
        }
        tvStatusMain.setText("题库共 " + questionCount + " 题" + (subjectText.isEmpty() ? "" : " · " + subjectText));

        subjects = json.optJSONArray("subjects");
        JSONObject statsSummary = json.optJSONObject("stats");
        boolean supportsSubjects = subjects != null && statsSummary != null;
        if (supportsSubjects) {
            threshold = clamp(statsSummary.optInt("threshold", 3), 1, 10);
            autoRemove = statsSummary.optBoolean("autoRemove", false);
            updateThresholdLabel(threshold);
            suppressSwitch = true;
            swAutoRemove.setChecked(autoRemove);
            suppressSwitch = false;
        }

        tvChipVersion.setText("版本 v" + version);
        tvChipVersion.setTextColor(getColor(R.color.accent));
        tvChipVersion.setBackgroundResource(R.drawable.bg_chip_info);
        tvChipMinutes.setText("放行 " + minutes + " 分钟");
        tvChipPass.setText("及格 " + passLine + " 题");
        tvChipRestricted.setText("受限 " + packages.size() + " 个");

        tvSubjectSummary.setText(buildSubjectSummary());
        if (!supportsSubjects) {
            tvSubjectSummary.setText("学科：电视端未提供学科信息（v2.8 及更早版本），请升级到 v2.9");
        }
        tvSubjectHint.setText(supportsSubjects
                ? "停用的学科不会出题。单学科上传只替换该学科，其余学科保留。"
                : "电视端版本过低，没有学科数据。请先把电视端升级到 v2.9。");

        renderSubjects();
        tvPasses.setText(buildPasses(json.optJSONArray("passes")));
        tvRecords.setText(buildRecords(json.optJSONArray("records")));
        ScrollView recordsScroll = (ScrollView) tvRecords.getParent();
        recordsScroll.scrollTo(0, 0);

        suppressSlider = true;
        sbMinutes.setProgress(minutes - 1);
        sbPassLine.setProgress(passLine - 1);
        suppressSlider = false;
        updateMinuteLabel(minutes);
        updatePassLineLabel(passLine);

        btnApps.setText("管理受限应用（" + packages.size() + "）");
        updateTargetButton();
        tvAppsInfo.setText("受限应用：" + joinLabels(packages));
        updateActionStates();
    }

    private String buildSubjectSummary() {
        if (subjects == null || subjects.length() == 0) {
            return "学科：暂无学科数据";
        }
        StringBuilder sb = new StringBuilder("学科：");
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject == null) {
                continue;
            }
            if (i > 0) {
                sb.append("　·　");
            }
            sb.append(str(subject, "name", str(subject, "key", "?")))
                    .append(" ").append(subject.optInt("count", 0)).append(" 题");
            int mastered = subject.optInt("mastered", -1);
            if (mastered >= 0) {
                sb.append("，已掌握 ").append(mastered);
            }
        }
        return sb.toString();
    }

    private String buildPasses(JSONArray passes) {
        if (passes == null || passes.length() == 0) {
            return "正在放行：当前没有放行的应用";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < passes.length(); i++) {
            JSONObject item = passes.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String pkg = str(item, "pkg", "");
            if (pkg.isEmpty()) {
                continue;
            }
            long remain = item.optLong("remainMs", 0L);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("正在放行：").append(labelFor(pkg));
            sb.append("（").append(pkg).append("）");
            sb.append(" · 剩余 ").append(formatRemain(remain));
        }
        return sb.length() == 0 ? "正在放行：当前没有放行的应用" : sb.toString();
    }

    private String buildRecords(JSONArray records) {
        if (records == null || records.length() == 0) {
            return "暂无答题记录";
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) {
                continue;
            }
            long time = record.optLong("ts", record.optLong("t", 0L));
            String pkg = str(record, "pkg", "");
            String label = str(record, "label", pkg.isEmpty() ? "未知应用" : labelFor(pkg));
            int correct = record.optInt("correct", 0);
            int total = record.optInt("total", 0);
            boolean pass = record.optBoolean("pass", false);
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(time > 0 ? format.format(new Date(time)) : "时间未知");
            sb.append(" · ").append(label);
            sb.append(pass ? " · 通过 " : " · 未通过 ");
            sb.append(correct).append('/').append(total);
        }
        return sb.length() == 0 ? "暂无答题记录" : sb.toString();
    }

    private void updateMinuteLabel(int value) {
        tvMinutesLabel.setText("放行时长：" + value + " 分钟");
    }

    private void updatePassLineLabel(int value) {
        tvPassLineLabel.setText("及格线：需答对 " + value + " 题");
    }

    private void updateThresholdLabel(int value) {
        tvThresholdLabel.setText("掌握阈值：答对 " + value + " 次视为已掌握");
    }

    private void updateTargetButton() {
        if (selectedPkg == null) {
            btnTarget.setText("放行对象：—");
            return;
        }
        btnTarget.setText("放行对象：" + labelFor(selectedPkg));
    }

    // ---------------- 学科列表 ----------------

    private List<String> enabledSubjectKeys() {
        List<String> keys = new ArrayList<String>();
        JSONObject statsSummary = summary == null ? null : summary.optJSONObject("stats");
        JSONArray enabled = statsSummary == null ? null : statsSummary.optJSONArray("enabledSubjects");
        if (enabled != null) {
            keys.addAll(stringList(enabled));
            return keys;
        }
        if (subjects != null) {
            for (int i = 0; i < subjects.length(); i++) {
                JSONObject subject = subjects.optJSONObject(i);
                if (subject != null) {
                    String key = str(subject, "key", "");
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private void renderSubjects() {
        if (subjectList == null) {
            return;
        }
        subjectList.removeAllViews();
        if (subjects == null || subjects.length() == 0) {
            TextView notice = new TextView(this);
            notice.setText("暂无学科数据。电视端 v2.8 及更早版本不支持多学科，请升级到 v2.9 后重新上传 v3 题库。");
            notice.setTextColor(getColor(R.color.text2));
            notice.setTextSize(13);
            notice.setLineSpacing(dp(3), 1f);
            subjectList.addView(notice);
            return;
        }
        List<String> enabled = enabledSubjectKeys();
        suppressSubjectSwitch = true;
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject == null) {
                continue;
            }
            final String key = str(subject, "key", "");
            String name = str(subject, "name", key);
            int count = subject.optInt("count", 0);
            int mastered = subject.optInt("mastered", 0);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_input);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rowParams.topMargin = dp(8);
            }
            row.setLayoutParams(rowParams);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextColor(getColor(R.color.text));
            nameView.setTextSize(15);
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            texts.addView(nameView);

            TextView meta = new TextView(this);
            meta.setText(key + " · " + count + " 题 · 已掌握 " + mastered);
            meta.setTextColor(getColor(R.color.text2));
            meta.setTextSize(13);
            meta.setPadding(0, dp(2), 0, 0);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final Switch toggle = new Switch(this);
            toggle.setText("");
            toggle.setTextSize(13);
            toggle.setChecked(enabled.contains(key));
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (suppressSubjectSwitch) {
                        return;
                    }
                    setSubjectEnabled(key, isChecked);
                }
            });
            row.addView(toggle);
            subjectList.addView(row);
        }
        suppressSubjectSwitch = false;
    }

    private void setSubjectEnabled(final String key, final boolean enabled) {
        if (!ensureConnected()) {
            renderSubjects();
            return;
        }
        final List<String> keys = new ArrayList<String>();
        if (subjects != null) {
            for (int i = 0; i < subjects.length(); i++) {
                JSONObject subject = subjects.optJSONObject(i);
                if (subject != null) {
                    String value = str(subject, "key", "");
                    if (!value.isEmpty()) {
                        keys.add(value);
                    }
                }
            }
        }
        final List<String> target = new ArrayList<String>();
        if (enabled) {
            target.addAll(enabledSubjectKeys());
            if (!target.contains(key)) {
                target.add(key);
            }
        } else {
            List<String> current = enabledSubjectKeys();
            if (current.isEmpty()) {
                current = keys;
            }
            for (String value : current) {
                if (!value.equals(key)) {
                    target.add(value);
                }
            }
        }
        final String name = subjectName(key);
        perform(new OpCall() {
            @Override
            public Op run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("enabledSubjects", new JSONArray(target));
                    TvClient.Response response = client.settings(body);
                    if (!response.ok) {
                        return new Op(false, response.error, null);
                    }
                    TvClient.Response readBack = client.summary();
                    JSONObject json = readBack.ok ? readBack.json() : null;
                    return new Op(true, (enabled ? "已启用学科「" : "已停用学科「") + name
                            + "」（已回读确认）", json);
                } catch (Throwable t) {
                    return new Op(false, "请求参数错误：" + t.getMessage(), null);
                }
            }
        });
    }

    private String subjectName(String key) {
        if (subjects != null) {
            for (int i = 0; i < subjects.length(); i++) {
                JSONObject subject = subjects.optJSONObject(i);
                if (subject != null && key.equals(str(subject, "key", ""))) {
                    return str(subject, "name", key);
                }
            }
        }
        return key;
    }

    // ---------------- 设置写入 ----------------

    private void saveSetting(final String field, final int value, final String okText) {
        if (!ensureConnected()) {
            return;
        }
        perform(new OpCall() {
            @Override
            public Op run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put(field, value);
                    TvClient.Response response = client.settings(body);
                    if (!response.ok) {
                        return new Op(false, response.error, null);
                    }
                    TvClient.Response readBack = client.summary();
                    if (!readBack.ok) {
                        return new Op(true, okText + "（回读失败：" + readBack.error + "）", null);
                    }
                    JSONObject json = readBack.json();
                    if (json == null) {
                        return new Op(true, okText, null);
                    }
                    int actual = json.optInt(field, value);
                    if (actual != value) {
                        return new Op(true, okText + "，但电视回读为 " + actual, json);
                    }
                    return new Op(true, okText + "（已回读确认）", json);
                } catch (Throwable t) {
                    return new Op(false, "请求参数错误：" + t.getMessage(), null);
                }
            }
        });
    }

    private void saveRestricted(final List<String> packages) {
        if (!ensureConnected()) {
            return;
        }
        perform(new OpCall() {
            @Override
            public Op run() {
                try {
                    JSONArray array = new JSONArray();
                    for (String pkg : packages) {
                        array.put(pkg);
                    }
                    JSONObject body = new JSONObject();
                    body.put("restricted", array);
                    TvClient.Response response = client.settings(body);
                    if (!response.ok) {
                        return new Op(false, response.error, null);
                    }
                    TvClient.Response readBack = client.summary();
                    JSONObject json = readBack.ok ? readBack.json() : null;
                    return new Op(true, "受限应用已更新，共 " + packages.size() + " 个（已回读确认）", json);
                } catch (Throwable t) {
                    return new Op(false, "请求参数错误：" + t.getMessage(), null);
                }
            }
        });
    }

    // ---------------- 受限应用管理对话框 ----------------

    private static final class AppRow {
        String pkg;
        CheckBox checkBox;
        EditText labelField;
    }

    private void showAppsDialog() {
        if (summary == null) {
            toast("请先连接电视");
            return;
        }
        JSONArray restricted = summary.optJSONArray("restricted");
        List<String> current = restricted == null ? new ArrayList<String>()
                : stringList(restricted);

        final LinkedHashMap<String, String> candidates = new LinkedHashMap<String, String>();
        for (String pkg : current) {
            candidates.put(pkg, labelFor(pkg));
        }
        for (String[] known : KNOWN_APPS) {
            if (!candidates.containsKey(known[0])) {
                candidates.put(known[0], known[1]);
            }
        }

        final List<AppRow> rows = new ArrayList<AppRow>();
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(16), dp(4), dp(16), dp(4));

        LinearLayout rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(rowsContainer);

        for (Map.Entry<String, String> entry : candidates.entrySet()) {
            rows.add(addAppRow(rowsContainer, entry.getKey(), entry.getValue(),
                    current.contains(entry.getKey())));
        }

        TextView hint = new TextView(this);
        hint.setText("勾选要拦截的应用；标签只保存在手机本地，用于界面显示。");
        hint.setTextColor(getColor(R.color.text2));
        hint.setTextSize(12);
        hint.setPadding(0, dp(10), 0, dp(4));
        outer.addView(hint);

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, dp(4), 0, 0);
        final EditText addInput = new EditText(this);
        addInput.setHint("新增包名，如 com.xxx.yyy");
        addInput.setSingleLine(true);
        addInput.setTextSize(13);
        addInput.setTextColor(getColor(R.color.text));
        addInput.setHintTextColor(getColor(R.color.hint));
        addInput.setBackgroundResource(R.drawable.bg_input);
        addInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        addRow.addView(addInput, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button addButton = new Button(this);
        addButton.setText("添加");
        addButton.setTextColor(getColor(R.color.text));
        addButton.setTextSize(13);
        addButton.setAllCaps(false);
        addButton.setBackgroundResource(R.drawable.bg_btn_ghost);
        addButton.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams addButtonParams =
                new LinearLayout.LayoutParams(dp(64), dp(44));
        addButtonParams.leftMargin = dp(8);
        addRow.addView(addButton, addButtonParams);
        outer.addView(addRow);

        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = addInput.getText().toString().trim();
                if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
                    toast("包名格式不正确，例如 com.xxx.yyy");
                    return;
                }
                for (AppRow row : rows) {
                    if (row.pkg.equals(pkg)) {
                        row.checkBox.setChecked(true);
                        return;
                    }
                }
                AppRow row = addAppRow(rowsContainer, pkg, "", true);
                rows.add(row);
                addInput.setText("");
            }
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(outer);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("受限应用")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                List<String> packages = new ArrayList<String>();
                                for (AppRow row : rows) {
                                    String label = row.labelField.getText().toString().trim();
                                    if (!label.isEmpty()) {
                                        labels.put(row.pkg, label);
                                    }
                                    if (row.checkBox.isChecked()) {
                                        packages.add(row.pkg);
                                    }
                                }
                                persistLabels();
                                dialog.dismiss();
                                saveRestricted(packages);
                                if (packages.isEmpty()) {
                                    toast("已清空受限应用，电视将不再拦截任何应用", true);
                                }
                            }
                        });
            }
        });
        dialog.show();
    }

    private AppRow addAppRow(LinearLayout container, String pkg, String label, boolean checked) {
        AppRow row = new AppRow();
        row.pkg = pkg;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(pkg);
        checkBox.setTextColor(getColor(R.color.text));
        checkBox.setTextSize(13);
        checkBox.setChecked(checked);
        box.addView(checkBox);
        row.checkBox = checkBox;

        EditText labelField = new EditText(this);
        labelField.setHint("标签（可选，如：未来电视）");
        labelField.setSingleLine(true);
        labelField.setTextSize(13);
        labelField.setTextColor(getColor(R.color.text));
        labelField.setHintTextColor(getColor(R.color.hint));
        labelField.setBackgroundResource(R.drawable.bg_input);
        labelField.setPadding(dp(10), dp(8), dp(10), dp(8));
        labelField.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(32);
        params.topMargin = dp(4);
        box.addView(labelField, params);
        row.labelField = labelField;

        container.addView(box);
        return row;
    }

    private void showTargetDialog() {
        if (summary == null || !connected) {
            toast("请先连接电视");
            return;
        }
        JSONArray restricted = summary.optJSONArray("restricted");
        List<String> packages = restricted == null ? new ArrayList<String>() : stringList(restricted);
        if (packages.isEmpty()) {
            toast("当前没有受限应用，请先在「管理受限应用」里添加");
            return;
        }
        final String[] items = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            items[i] = labelFor(packages.get(i)) + "（" + packages.get(i) + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择放行对象")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedPkg = packages.get(which);
                        updateTargetButton();
                        updateActionStates();
                    }
                })
                .show();
    }

    // ---------------- 放行 / 撤销 / 清空记录 ----------------

    private void doPass() {
        if (!ensureConnected()) {
            return;
        }
        if (selectedPkg == null) {
            toast("请先选择放行对象");
            return;
        }
        final String pkg = selectedPkg;
        final String name = labelFor(pkg);
        perform(new OpCall() {
            @Override
            public Op run() {
                TvClient.Response response = client.pass(pkg, 30);
                if (!response.ok) {
                    return new Op(false, response.error, null);
                }
                TvClient.Response readBack = client.summary();
                JSONObject json = readBack.ok ? readBack.json() : null;
                return new Op(true, "已放行「" + name + "」30 分钟", json);
            }
        });
    }

    private void confirmRevoke() {
        if (!ensureConnected()) {
            return;
        }
        if (selectedPkg == null) {
            toast("请先选择放行对象");
            return;
        }
        final String pkg = selectedPkg;
        final String name = labelFor(pkg);
        new AlertDialog.Builder(this)
                .setTitle("撤销放行")
                .setMessage("确定撤销「" + name + "」的放行吗？撤销后孩子再次打开会重新答题。")
                .setNegativeButton("取消", null)
                .setPositiveButton("撤销", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRevoke(pkg, name);
                    }
                })
                .show();
    }

    private void doRevoke(final String pkg, final String name) {
        if (!ensureConnected()) {
            return;
        }
        perform(new OpCall() {
            @Override
            public Op run() {
                TvClient.Response response = client.revoke(pkg);
                if (!response.ok) {
                    return new Op(false, response.error, null);
                }
                TvClient.Response readBack = client.summary();
                JSONObject json = readBack.ok ? readBack.json() : null;
                return new Op(true, "已撤销「" + name + "」的放行", json);
            }
        });
    }

    private void confirmClearRecords() {
        if (!ensureConnected()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空答题记录")
                .setMessage("将清空电视上的全部答题记录，且无法恢复。确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doClearRecords();
                    }
                })
                .show();
    }

    private void doClearRecords() {
        if (!ensureConnected()) {
            return;
        }
        perform(new OpCall() {
            @Override
            public Op run() {
                TvClient.Response response = client.clearRecords();
                if (!response.ok) {
                    return new Op(false, response.error, null);
                }
                TvClient.Response readBack = client.summary();
                JSONObject json = readBack.ok ? readBack.json() : null;
                return new Op(true, "已清空电视端答题记录", json);
            }
        });
    }

    // ---------------- 题库上传 ----------------

    private void pickJsonFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream", "text/*"});
        try {
            startActivityForResult(intent, REQ_PICK_JSON);
        } catch (Throwable t) {
            toast("没有可用的文件选择器：" + t.getClass().getSimpleName(), true);
        }
    }

    private void showSubjectPicker() {
        if (!connected || summary == null) {
            toast("请先连接电视");
            return;
        }
        if (subjects == null || subjects.length() == 0) {
            toast("电视端没有学科数据，请先整库上传 v3 题库");
            return;
        }
        final List<String> keys = new ArrayList<String>();
        final List<String> items = new ArrayList<String>();
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject == null) {
                continue;
            }
            String key = str(subject, "key", "");
            if (key.isEmpty()) {
                continue;
            }
            keys.add(key);
            items.add(str(subject, "name", key) + "（" + key + " · "
                    + subject.optInt("count", 0) + " 题）");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要替换的学科")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingSubjectKey = keys.get(which);
                        pendingSubjectName = subjectName(pendingSubjectKey);
                        pickJsonFile();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_JSON || resultCode != RESULT_OK || data == null) {
            return;
        }
        final Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        final String subjectKey = pendingSubjectKey;
        final String subjectDisplay = pendingSubjectName;
        pendingSubjectKey = null;
        pendingSubjectName = null;
        tvFileInfo.setText("正在读取文件…");
        enqueue(new Runnable() {
            @Override
            public void run() {
                String name = queryFileName(uri);
                String text = null;
                String error = null;
                try {
                    text = readTextFile(uri);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                final String finalName = name;
                final String finalText = text;
                final String finalError = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (finalError != null) {
                            pickedPayload = null;
                            pickedCount = 0;
                            tvFileInfo.setText("已选择：" + finalName + "\n读取失败：" + finalError);
                            updateActionStates();
                            return;
                        }
                        QuestionValidator.Result result = QuestionValidator.validate(finalText);
                        if (!result.ok) {
                            pickedPayload = null;
                            pickedCount = 0;
                            tvFileInfo.setText("已选择：" + finalName + "\n本地校验失败：" + result.message);
                            updateActionStates();
                            toast("题库校验失败：" + result.message, true);
                            return;
                        }
                        if (subjectKey != null) {
                            try {
                                String payload = buildSubjectPayload(finalText, subjectKey, subjectDisplay);
                                QuestionValidator.Result check =
                                        QuestionValidator.validate(payload);
                                if (!check.ok) {
                                    throw new Exception(check.message);
                                }
                                pickedPayload = payload;
                                pickedCount = check.count;
                                tvFileInfo.setText("已选择：" + finalName + "\n" + result.message
                                        + "\n将只替换学科「" + subjectDisplay + "」，共 "
                                        + check.count + " 题。");
                                updateActionStates();
                                toast("本地校验通过，将替换学科「" + subjectDisplay + "」");
                            } catch (Throwable t) {
                                pickedPayload = null;
                                pickedCount = 0;
                                tvFileInfo.setText("已选择：" + finalName + "\n单学科校验失败："
                                        + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                                updateActionStates();
                                toast("单学科校验失败", true);
                            }
                            return;
                        }
                        pickedPayload = finalText;
                        pickedCount = result.count;
                        tvFileInfo.setText("已选择：" + finalName + "\n本地校验通过：" + result.message
                                + "，可以上传到电视。");
                        updateActionStates();
                        toast("本地校验通过：" + result.message);
                    }
                });
            }
        });
    }

    /**
     * 把整库 JSON 收敛成只含目标学科的 v3 载荷；旧 v2 文件会补上 id 后包成单学科。
     */
    private String buildSubjectPayload(String text, String key, String name) throws Exception {
        String clean = text.replace("\uFEFF", "").trim();
        String title = "单学科题库";
        JSONArray questions = null;
        JSONObject root = null;
        if (clean.startsWith("[")) {
            questions = new JSONArray(clean);
        } else {
            root = new JSONObject(clean);
            title = root.optString("title", title);
            JSONArray subjects = root.optJSONArray("subjects");
            if (subjects != null) {
                JSONObject found = null;
                for (int i = 0; i < subjects.length(); i++) {
                    JSONObject subject = subjects.optJSONObject(i);
                    if (subject != null && key.equals(subject.optString("key", "").trim())) {
                        found = subject;
                        break;
                    }
                }
                if (found == null) {
                    throw new Exception("文件里没有 key 为「" + key + "」的学科");
                }
                JSONObject payload = new JSONObject();
                payload.put("version", 3);
                payload.put("title", title);
                JSONArray one = new JSONArray();
                one.put(found);
                payload.put("subjects", one);
                return payload.toString();
            }
            questions = root.optJSONArray("questions");
        }
        if (questions == null) {
            throw new Exception("文件里既没有 subjects 也没有 questions");
        }
        JSONObject subject = new JSONObject();
        subject.put("key", key);
        subject.put("name", name);
        JSONArray wrapped = new JSONArray();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject source = questions.optJSONObject(i);
            if (source == null) {
                throw new Exception("第 " + (i + 1) + " 题不是 JSON 对象");
            }
            JSONObject copy = new JSONObject(source.toString());
            if (copy.optString("id", "").trim().isEmpty()) {
                copy.put("id", key + "-" + String.format(Locale.US, "%03d", i + 1));
            }
            wrapped.put(copy);
        }
        subject.put("questions", wrapped);
        JSONObject payload = new JSONObject();
        payload.put("version", 3);
        payload.put("title", title);
        JSONArray one = new JSONArray();
        one.put(subject);
        payload.put("subjects", one);
        return payload.toString();
    }

    private void uploadQuestions() {
        if (!ensureConnected()) {
            return;
        }
        if (pickedPayload == null) {
            toast("请先选择题库 JSON 文件");
            return;
        }
        final String json = pickedPayload;
        final int count = pickedCount;
        perform(new OpCall() {
            @Override
            public Op run() {
                TvClient.Response response = client.upload(json);
                if (!response.ok) {
                    return new Op(false, response.error, null);
                }
                String message = messageFrom(response.body, "导入成功，共 " + count + " 题");
                TvClient.Response readBack = client.summary();
                JSONObject data = readBack.ok ? readBack.json() : null;
                return new Op(true, message, data);
            }
        });
        pickedPayload = null;
        tvFileInfo.setText("正在上传…成功后请回读题库确认。");
        refreshStats(false);
    }

    private void confirmRollback() {
        if (!ensureConnected()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("回退上一版题库")
                .setMessage("将把电视端题库恢复到上一次上传前的版本，确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("回退", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        rollbackQuestions();
                    }
                })
                .show();
    }

    private void rollbackQuestions() {
        if (!ensureConnected()) {
            return;
        }
        perform(new OpCall() {
            @Override
            public Op run() {
                TvClient.Response response = client.rollback();
                if (!response.ok) {
                    return new Op(false, response.error, null);
                }
                String message = messageFrom(response.body, "已回退到上一版题库");
                TvClient.Response readBack = client.summary();
                JSONObject data = readBack.ok ? readBack.json() : null;
                return new Op(true, message, data);
            }
        });
        refreshStats(false);
    }

    // ---------------- 模板 / AI 提示词 / 粘贴导入 ----------------

    private void copyToClipboard(String label, CharSequence text) {
        if (text == null || text.length() == 0) {
            toast(label + "内容为空", true);
            return;
        }
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null) {
                toast("当前设备不支持剪贴板", true);
                return;
            }
            manager.setPrimaryClip(ClipData.newPlainText(label, text));
            // 立即回读校验，避免只弹 Toast 但实际没写进去。
            ClipData check = manager.getPrimaryClip();
            CharSequence copied = check != null && check.getItemCount() > 0
                    ? check.getItemAt(0).coerceToText(this) : null;
            if (copied != null && copied.toString().equals(text.toString())) {
                toast(label + "已复制");
            } else {
                toast(label + "复制失败，请重试", true);
            }
        } catch (Throwable t) {
            toast("复制失败：" + t.getClass().getSimpleName(), true);
        }
    }

    private void togglePromptCard() {
        boolean expanded = promptContent.getVisibility() == View.VISIBLE;
        promptContent.setVisibility(expanded ? View.GONE : View.VISIBLE);
        tvPromptArrow.setText(expanded ? "展开 ▾" : "收起 ▴");
    }

    /** 粘贴内容去首尾空白和 BOM，界面里的文本会被同步更新。 */
    private String normalizePastedText() {
        String raw = etPaste.getText().toString();
        String text = raw.replace("\uFEFF", "").trim();
        if (!text.equals(raw)) {
            etPaste.setText(text);
            etPaste.setSelection(text.length());
        }
        return text;
    }

    private void validateAndUploadPasted() {
        String text = normalizePastedText();
        if (text.isEmpty()) {
            tvPasteState.setText("请先粘贴题库 JSON。");
            toast("请先粘贴题库 JSON");
            return;
        }
        tvPasteState.setText("正在本地校验…");
        QuestionValidator.Result result = QuestionValidator.validate(text);
        if (!result.ok) {
            tvPasteState.setText("校验失败：" + result.message);
            toast("校验失败：" + result.message, true);
            return;
        }
        if (!ensureConnected()) {
            tvPasteState.setText("本地校验通过：" + result.message + "；但还没有连接电视。");
            return;
        }
        tvPasteState.setText("本地校验通过：" + result.message + "，正在上传…");
        uploadPastedText(text, result.count);
    }

    private void uploadPastedText(final String json, final int count) {
        if (client == null) {
            toast("请先连接电视");
            return;
        }
        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response = client.upload(json);
                final String failMessage = response.error;
                final String okMessage = messageFrom(response.body, "导入成功，共 " + count + " 题");
                final JSONObject data;
                final JSONObject stats;
                if (response.ok) {
                    TvClient.Response readBack = client.summary();
                    data = readBack.ok ? readBack.json() : null;
                    TvClient.Response statsResponse = client.stats();
                    stats = statsResponse.ok ? statsResponse.json() : null;
                } else {
                    data = null;
                    stats = null;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (response.ok) {
                            tvPasteState.setText(okMessage);
                            if (data != null) {
                                applySummary(data);
                            }
                            if (stats != null) {
                                applyStats(stats);
                            }
                            toast(okMessage);
                        } else {
                            tvPasteState.setText("上传失败：" + failMessage);
                            toast(failMessage, true);
                        }
                    }
                });
            }
        });
    }

    private String readTextFile(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("无法打开文件");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                if (out.size() > QuestionValidator.MAX_BYTES) {
                    throw new Exception("文件超过 1MB 上限");
                }
            }
            String text = out.toString("UTF-8");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private String queryFileName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 个别文件提供者不支持查询
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "题库文件" : last;
    }

    // ---------------- 统计 ----------------

    private void refreshStats(final boolean showToast) {
        if (!ensureConnected()) {
            return;
        }
        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response = client.stats();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (!response.ok) {
                            statsData = null;
                            clearStats(response.error);
                            tvStatsState.setText(response.error
                                    + (response.incomplete ? "" : "。统计功能需要电视端 v2.9。"));
                            btnRetryStats.setVisibility(View.VISIBLE);
                            if (showToast) {
                                toast(response.error, true);
                            }
                            return;
                        }
                        JSONObject json = response.json();
                        if (json == null) {
                            statsData = null;
                            clearStats("电视端返回的统计不是 JSON，请升级到 v2.9。");
                            btnRetryStats.setVisibility(View.VISIBLE);
                            return;
                        }
                        btnRetryStats.setVisibility(View.GONE);
                        applyStats(json);
                        if (showToast) {
                            toast("统计已刷新");
                        }
                    }
                });
            }
        });
    }

    private void clearStats(String message) {
        if (statSubjects == null) {
            return;
        }
        suppressSwitch = true;
        swAutoRemove.setChecked(false);
        suppressSwitch = false;
        sbThreshold.setEnabled(false);
        swAutoRemove.setEnabled(false);
        btnResetStats.setEnabled(false);
        btnResetStats.setAlpha(0.45f);
        statSubjects.removeAllViews();
        statQuestions.removeAllViews();
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextColor(getColor(R.color.text2));
        empty.setTextSize(13);
        empty.setLineSpacing(dp(3), 1f);
        statSubjects.addView(empty);
        TextView emptyQuestions = new TextView(this);
        emptyQuestions.setText(message);
        emptyQuestions.setTextColor(getColor(R.color.text2));
        emptyQuestions.setTextSize(13);
        statQuestions.addView(emptyQuestions);
        if (tvStatsState != null) {
            tvStatsState.setText(message);
        }
    }

    private void applyStats(JSONObject json) {
        statsData = json;
        threshold = clamp(json.optInt("threshold", 3), 1, 10);
        autoRemove = json.optBoolean("autoRemove", false);

        suppressSlider = true;
        sbThreshold.setProgress(threshold - 1);
        suppressSlider = false;
        updateThresholdLabel(threshold);
        suppressSwitch = true;
        swAutoRemove.setChecked(autoRemove);
        suppressSwitch = false;
        sbThreshold.setEnabled(true);
        swAutoRemove.setEnabled(true);
        btnResetStats.setEnabled(connected);
        btnResetStats.setAlpha(connected ? 1f : 0.45f);

        JSONArray subjectArray = json.optJSONArray("subjects");
        JSONArray questionArray = json.optJSONArray("questions");
        int questionCount = questionArray == null ? 0 : questionArray.length();
        tvStatsState.setText("已加载 " + (subjectArray == null ? 0 : subjectArray.length())
                + " 个学科、" + questionCount + " 道题 · 阈值答对 " + threshold
                + " 次 · 自动移出" + (autoRemove ? "已开启" : "已关闭"));

        renderStatsSubjects(subjectArray);
        renderStatsQuestions(questionArray, subjectArray);
    }

    private void renderStatsSubjects(JSONArray subjectArray) {
        statSubjects.removeAllViews();
        if (subjectArray == null || subjectArray.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("电视端没有返回学科汇总。");
            empty.setTextColor(getColor(R.color.text2));
            empty.setTextSize(13);
            statSubjects.addView(empty);
            return;
        }
        for (int i = 0; i < subjectArray.length(); i++) {
            JSONObject subject = subjectArray.optJSONObject(i);
            if (subject == null) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_input);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.topMargin = dp(8);
            }
            row.setLayoutParams(params);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameView = new TextView(this);
            nameView.setText(str(subject, "name", str(subject, "key", "?")));
            nameView.setTextColor(getColor(R.color.text));
            nameView.setTextSize(15);
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            texts.addView(nameView);
            TextView meta = new TextView(this);
            meta.setText(str(subject, "key", "") + " · 共 " + subject.optInt("count", 0)
                    + " 题 · 已掌握 " + subject.optInt("mastered", 0));
            meta.setTextColor(getColor(R.color.text2));
            meta.setTextSize(13);
            meta.setPadding(0, dp(2), 0, 0);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView chip = new TextView(this);
            chip.setText("已掌握 " + subject.optInt("mastered", 0));
            chip.setTextSize(13);
            chip.setTextColor(getColor(R.color.ok));
            chip.setBackgroundResource(R.drawable.bg_chip_ok);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            row.addView(chip);
            statSubjects.addView(row);
        }
    }

    private void renderStatsQuestions(JSONArray questionArray, JSONArray subjectArray) {
        statQuestions.removeAllViews();
        if (questionArray == null || questionArray.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("电视端没有返回逐题统计。");
            empty.setTextColor(getColor(R.color.text2));
            empty.setTextSize(13);
            statQuestions.addView(empty);
            return;
        }
        for (int i = 0; i < questionArray.length(); i++) {
            final JSONObject question = questionArray.optJSONObject(i);
            if (question == null) {
                continue;
            }
            final String id = str(question, "id", "");
            final String text = str(question, "q", "（无题干）");
            final boolean removed = question.optBoolean("removed", false);
            boolean mastered = question.optBoolean("mastered", false);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_input);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.topMargin = dp(8);
            }
            row.setLayoutParams(params);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView stem = new TextView(this);
            stem.setText(truncate(text, 30));
            stem.setTextColor(getColor(R.color.text));
            stem.setTextSize(15);
            texts.addView(stem);

            LinearLayout metaRow = new LinearLayout(this);
            metaRow.setOrientation(LinearLayout.HORIZONTAL);
            metaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            metaRow.setPadding(0, dp(4), 0, 0);

            TextView chip = new TextView(this);
            if (removed) {
                chip.setText("已移出");
                chip.setTextColor(getColor(R.color.warn));
                chip.setBackgroundResource(R.drawable.bg_chip_warn);
            } else if (mastered) {
                chip.setText("已掌握");
                chip.setTextColor(getColor(R.color.ok));
                chip.setBackgroundResource(R.drawable.bg_chip_ok);
            } else {
                chip.setText("未掌握");
                chip.setTextColor(getColor(R.color.text2));
                chip.setBackgroundResource(R.drawable.bg_chip_gray);
            }
            chip.setTextSize(12);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            metaRow.addView(chip);

            TextView meta = new TextView(this);
            meta.setText("　" + subjectNameForKey(subjectArray, str(question, "subject", ""))
                    + " · 答 " + question.optInt("answered", 0) + " 次 · 对 "
                    + question.optInt("correct", 0) + " · 错 " + question.optInt("wrong", 0));
            meta.setTextColor(getColor(R.color.text2));
            meta.setTextSize(13);
            metaRow.addView(meta);
            texts.addView(metaRow);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button action = new Button(this);
            action.setText(removed ? "恢复" : "移出");
            action.setTextSize(13);
            action.setAllCaps(false);
            action.setTextColor(getColor(removed ? R.color.accent : R.color.danger));
            action.setBackgroundResource(removed ? R.drawable.bg_btn_ghost
                    : R.drawable.bg_btn_danger_ghost);
            action.setMinWidth(0);
            action.setMinHeight(0);
            action.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams actionParams =
                    new LinearLayout.LayoutParams(dp(64), dp(40));
            actionParams.leftMargin = dp(8);
            row.addView(action, actionParams);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (removed) {
                        doRestoreQuestion(id, truncate(text, 16));
                    } else {
                        confirmRemoveQuestion(id, truncate(text, 16));
                    }
                }
            });
            statQuestions.addView(row);
        }
    }

    private String subjectNameForKey(JSONArray subjectArray, String key) {
        if (subjectArray != null) {
            for (int i = 0; i < subjectArray.length(); i++) {
                JSONObject subject = subjectArray.optJSONObject(i);
                if (subject != null && key.equals(str(subject, "key", ""))) {
                    return str(subject, "name", key);
                }
            }
        }
        return key;
    }

    private void confirmRemoveQuestion(final String id, final String title) {
        new AlertDialog.Builder(this)
                .setTitle("移出此题")
                .setMessage("「" + title + "」将不再出现在孩子的答题中，可在统计页随时恢复。确定移出吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("移出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRemoveQuestion(id, title);
                    }
                })
                .show();
    }

    private void doRemoveQuestion(final String id, final String title) {
        if (!ensureConnected()) {
            return;
        }
        mutateStats(new StatCall() {
            @Override
            public TvClient.Response run() {
                return client.removeQuestion(id);
            }
        }, "已移出题目「" + title + "」");
    }

    private void doRestoreQuestion(final String id, final String title) {
        if (!ensureConnected()) {
            return;
        }
        mutateStats(new StatCall() {
            @Override
            public TvClient.Response run() {
                return client.restoreQuestion(id);
            }
        }, "已恢复题目「" + title + "」");
    }

    private void confirmResetStats() {
        if (!ensureConnected()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("重置统计")
                .setMessage("将把全部题目的答题次数、答对/答错次数清零，并取消所有「已移出」标记。此操作不可恢复，确定继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doResetStats();
                    }
                })
                .show();
    }

    private void doResetStats() {
        if (!ensureConnected()) {
            return;
        }
        mutateStats(new StatCall() {
            @Override
            public TvClient.Response run() {
                return client.resetStats();
            }
        }, "已重置全部答题统计");
    }

    private interface StatCall {
        TvClient.Response run();
    }

    private void mutateStats(final StatCall call, final String okText) {
        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response = call.run();
                final JSONObject newSummary;
                final JSONObject newStats;
                if (response.ok) {
                    TvClient.Response summaryResponse = client.summary();
                    newSummary = summaryResponse.ok ? summaryResponse.json() : null;
                    TvClient.Response statsResponse = client.stats();
                    newStats = statsResponse.ok ? statsResponse.json() : null;
                } else {
                    newSummary = null;
                    newStats = null;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (!response.ok) {
                            toast(response.error, true);
                            return;
                        }
                        if (newSummary != null) {
                            applySummary(newSummary);
                        }
                        if (newStats != null) {
                            applyStats(newStats);
                        } else {
                            refreshStats(false);
                        }
                        toast(okText);
                    }
                });
            }
        });
    }

    private void saveStatsSetting(final String field, final Object value, final String okText) {
        if (!ensureConnected()) {
            return;
        }
        enqueue(new Runnable() {
            @Override
            public void run() {
                final TvClient.Response response;
                final JSONObject newStats;
                try {
                    JSONObject body = new JSONObject();
                    body.put(field, value);
                    response = client.settings(body);
                    if (response.ok) {
                        TvClient.Response statsResponse = client.stats();
                        newStats = statsResponse.ok ? statsResponse.json() : null;
                    } else {
                        newStats = null;
                    }
                } catch (Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toast("请求参数错误：" + t.getMessage(), true);
                            renderStatsFromCache();
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (!response.ok) {
                            toast(response.error, true);
                            renderStatsFromCache();
                            return;
                        }
                        if (newStats != null) {
                            applyStats(newStats);
                            toast(okText + "（已回读确认）");
                        } else {
                            toast(okText + "（统计回读失败）", true);
                        }
                    }
                });
            }
        });
    }

    private void saveAutoRemove(final boolean value) {
        saveStatsSetting("autoRemove", value,
                "自动移出已掌握题目：" + (value ? "已开启" : "已关闭"));
    }

    private void renderStatsFromCache() {
        if (statsData != null) {
            applyStats(statsData);
        } else {
            clearStats("统计未加载。");
        }
    }

    // ---------------- 扫描 ----------------

    private void startScan() {
        scanning = true;
        btnScan.setText("取消扫描");
        tvConnState.setTextColor(getColor(R.color.text2));
        tvConnState.setText("正在扫描本机同网段的 8080 端口…");
        scanner = new LanScanner();
        scanner.start(new LanScanner.Listener() {
            @Override
            public void onProgress(int scanned, int total) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (scanning) {
                            tvConnState.setText("正在扫描同网段 8080 端口：" + scanned + "/" + total + " …");
                        }
                    }
                });
            }

            @Override
            public void onFinished(final List<String> hosts, final boolean cancelled) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        finishScan(hosts, cancelled);
                    }
                });
            }
        });
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.cancel();
        }
    }

    private void finishScan(List<String> hosts, boolean cancelled) {
        scanning = false;
        btnScan.setText("扫描同网段");
        if (cancelled) {
            tvConnState.setText("已取消扫描");
            tvConnState.setTextColor(getColor(R.color.text2));
            return;
        }
        if (hosts.isEmpty()) {
            tvConnState.setTextColor(getColor(R.color.danger));
            tvConnState.setText("未发现开着 8080 端口的设备。请确认电视端已打开「手机连接」页面，"
                    + "且手机和电视连在同一个 Wi-Fi。");
            toast("未发现电视，请检查电视端页面是否已打开", true);
            return;
        }
        etIp.setText(hosts.get(0));
        tvConnState.setTextColor(getColor(R.color.ok));
        StringBuilder sb = new StringBuilder("发现 ").append(hosts.size()).append(" 台设备：");
        for (int i = 0; i < hosts.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(hosts.get(i));
        }
        sb.append("。已填入第一个地址，请点「连接」。");
        tvConnState.setText(sb.toString());
    }

    // ---------------- 通用逻辑 ----------------

    private void updateActionStates() {
        btnRefresh.setEnabled(connected);
        btnRollback.setEnabled(connected);
        btnApps.setEnabled(connected);
        btnTarget.setEnabled(connected);
        btnPass30.setEnabled(connected && selectedPkg != null);
        btnRevoke.setEnabled(connected && selectedPkg != null);
        btnClearRecords.setEnabled(connected);
        btnUpload.setEnabled(connected && pickedPayload != null);
        btnPasteUpload.setEnabled(connected);
        btnPickSubject.setEnabled(connected && subjects != null && subjects.length() > 0);
        btnStatsRefresh.setEnabled(connected);
        btnResetStats.setEnabled(connected && statsData != null);

        float on = 1f;
        float off = 0.45f;
        btnRefresh.setAlpha(connected ? on : off);
        btnRollback.setAlpha(connected ? on : off);
        btnApps.setAlpha(connected ? on : off);
        btnTarget.setAlpha(connected ? on : off);
        btnPass30.setAlpha(connected && selectedPkg != null ? on : off);
        btnRevoke.setAlpha(connected && selectedPkg != null ? on : off);
        btnClearRecords.setAlpha(connected ? on : off);
        btnUpload.setAlpha(connected && pickedPayload != null ? on : off);
        btnPasteUpload.setAlpha(connected ? on : off);
        btnPickSubject.setAlpha(connected && subjects != null && subjects.length() > 0 ? on : off);
        btnStatsRefresh.setAlpha(connected ? on : off);
        btnResetStats.setAlpha(connected && statsData != null ? on : off);
    }

    private static final class Op {
        final boolean ok;
        final String message;
        final JSONObject summary;

        Op(boolean ok, String message, JSONObject summary) {
            this.ok = ok;
            this.message = message;
            this.summary = summary;
        }
    }

    private interface OpCall {
        Op run();
    }

    private void perform(final OpCall call) {
        enqueue(new Runnable() {
            @Override
            public void run() {
                Op op;
                try {
                    op = call.run();
                } catch (Throwable t) {
                    op = new Op(false, "操作失败：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage()), null);
                }
                final Op result = op;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (result.summary != null) {
                            applySummary(result.summary);
                        }
                        if (result.ok) {
                            if (result.message != null) {
                                toast(result.message);
                            }
                        } else {
                            toast(result.message == null ? "操作失败" : result.message, true);
                        }
                    }
                });
            }
        });
    }

    private boolean ensureConnected() {
        if (!connected || client == null) {
            toast("请先连接电视");
            return false;
        }
        return true;
    }

    private void loadLabels() {
        try {
            JSONObject stored = new JSONObject(sp.getString("labels", "{}"));
            for (java.util.Iterator<String> it = stored.keys(); it.hasNext(); ) {
                String pkg = it.next();
                String label = stored.optString(pkg, "");
                if (!label.isEmpty()) {
                    labels.put(pkg, label);
                }
            }
        } catch (Throwable ignored) {
            // 损坏时使用默认标签
        }
    }

    private void persistLabels() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                    json.put(entry.getKey(), entry.getValue());
                }
            }
            sp.edit().putString("labels", json.toString()).apply();
        } catch (Throwable ignored) {
            // 标签只是展示信息
        }
    }

    private String labelFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "未知应用";
        }
        String label = labels.get(pkg);
        if (label != null && !label.isEmpty()) {
            return label;
        }
        for (String[] known : KNOWN_APPS) {
            if (known[0].equals(pkg)) {
                return known[1];
            }
        }
        return pkg;
    }

    private String joinLabels(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "未设置";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < packages.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(labelFor(packages.get(i)));
        }
        return sb.toString();
    }

    private static List<String> stringList(JSONArray array) {
        List<String> list = new ArrayList<String>();
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty() && !list.contains(value)) {
                list.add(value);
            }
        }
        return list;
    }

    private static String sourceName(String source) {
        if ("local".equals(source)) {
            return "本机导入";
        }
        if ("asset".equals(source) || "builtin".equals(source)) {
            return "内置题库";
        }
        if ("custom".equals(source)) {
            return "自定义";
        }
        return source;
    }

    private static String formatRemain(long remainMs) {
        long seconds = Math.max(0L, remainMs / 1000L);
        long m = seconds / 60L;
        long s = seconds % 60L;
        if (m <= 0) {
            return s + " 秒";
        }
        return m + " 分 " + s + " 秒";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String messageFrom(String body, String fallback) {
        String text = body == null ? "" : body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(text);
                String message = json.optString("message", "");
                if (message != null && !message.trim().isEmpty()) {
                    return message.trim();
                }
                String error = json.optString("error", "");
                if (error != null && !error.trim().isEmpty()) {
                    return error.trim();
                }
            } catch (Throwable ignored) {
                // 回退到默认文案
            }
        }
        if (!text.isEmpty() && text.length() <= 200) {
            return text;
        }
        return fallback;
    }

    private static String str(JSONObject json, String key, String fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        String value = json.optString(key, "").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    private String text(EditText editText) {
        return editText.getText().toString().trim();
    }

    private int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void toast(String message) {
        toast(message, false);
    }

    private void toast(String message, boolean longDuration) {
        if (message == null || message.isEmpty() || isFinishing()) {
            return;
        }
        Toast.makeText(this, message, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }
}
