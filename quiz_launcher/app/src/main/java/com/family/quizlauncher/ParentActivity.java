package com.family.quizlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.family.quizlauncher.ai.ApiKeys;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ParentActivity extends Activity {

    private Prefs prefs;
    private TextView minutesLabel;
    private TextView requiredLabel;
    private TextView bankInfo;
    private TextView serviceState;
    private TextView serviceHint;
    private TextView controlState;
    private TextView controlHint;
    private Button controlButton;
    private TextView aiStatus;
    private LinearLayout appsList;
    private LinearLayout subjectsList;
    private SeekBar minutesBar;
    private SeekBar requiredBar;
    private SeekBar thresholdBar;
    private TextView thresholdLabel;
    private TextView autoRemoveLabel;
    private Button autoRemoveButton;
    private TextView strictState;
    private TextView strictHint;
    private Button strictButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent);
        prefs = Prefs.get(this);
        prefs.initRestricted(AppRepository.defaultRestricted(this));
        prefs.ensureV30Defaults(this);

        minutesLabel = (TextView) findViewById(R.id.minutes_label);
        requiredLabel = (TextView) findViewById(R.id.required_label);
        bankInfo = (TextView) findViewById(R.id.bank_info);
        serviceState = (TextView) findViewById(R.id.service_state);
        serviceHint = (TextView) findViewById(R.id.service_hint);
        controlState = (TextView) findViewById(R.id.control_state);
        controlHint = (TextView) findViewById(R.id.control_hint);
        controlButton = (Button) findViewById(R.id.btn_control);
        aiStatus = (TextView) findViewById(R.id.ai_status);
        appsList = (LinearLayout) findViewById(R.id.apps_list);
        subjectsList = (LinearLayout) findViewById(R.id.subjects_list);
        minutesBar = (SeekBar) findViewById(R.id.minutes_bar);
        requiredBar = (SeekBar) findViewById(R.id.required_bar);
        thresholdBar = (SeekBar) findViewById(R.id.threshold_bar);
        thresholdLabel = (TextView) findViewById(R.id.threshold_label);
        autoRemoveLabel = (TextView) findViewById(R.id.auto_remove_label);
        autoRemoveButton = (Button) findViewById(R.id.btn_auto_remove);
        strictState = (TextView) findViewById(R.id.strict_state);
        strictHint = (TextView) findViewById(R.id.strict_hint);
        strictButton = (Button) findViewById(R.id.btn_strict);

        setupMinutes();
        setupRequired();
        setupMastery();
        setupStrict();
        setupAiKeys();
        buildAppsList();

        autoRemoveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setAutoRemove(!prefs.isAutoRemove());
                updateAutoRemove();
                Toast.makeText(ParentActivity.this,
                        prefs.isAutoRemove() ? "已开启：答对达到阈值后自动移出" : "已关闭自动移出",
                        Toast.LENGTH_SHORT).show();
            }
        });
        ((Button) findViewById(R.id.btn_stats)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ParentActivity.this, StatsActivity.class));
            }
        });

        controlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleControlService();
            }
        });

        ((Button) findViewById(R.id.btn_default)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.replaceRestricted(AppRepository.defaultRestricted(ParentActivity.this));
                buildAppsList();
            }
        });
        ((Button) findViewById(R.id.btn_exit)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((Button) findViewById(R.id.btn_bank)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ParentActivity.this, QuestionBankActivity.class));
            }
        });
        ((Button) findViewById(R.id.btn_pin)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changePin();
            }
        });
        ((Button) findViewById(R.id.btn_accessibility)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        ((Button) findViewById(R.id.btn_test)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(ParentActivity.this, QuizActivity.class);
                intent.putExtra(QuizActivity.EXTRA_PRACTICE, true);
                startActivity(intent);
            }
        });
        ((Button) findViewById(R.id.btn_records)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ParentActivity.this, RecordsActivity.class));
            }
        });

        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        StringBuilder info = new StringBuilder("当前题库：").append(bank.title.isEmpty() ? "未命名" : bank.title)
                .append(" · ").append(bank.subjects.size()).append(" 个学科 · 共 ")
                .append(bank.total()).append(" 题（外部导入优先，其次内置 assets）。\n");
        for (QuestionBank.Subject subject : bank.subjects) {
            info.append("· ").append(subject.name).append(" ").append(subject.questions.size())
                    .append(" 题\n");
        }
        info.append("换题：点「题库管理」，可整库上传或单学科替换，也可 adb push 到 /sdcard/Android/data/")
                .append(getPackageName()).append("/files/questions_import.json。");
        bankInfo.setText(info.toString());

        // 首帧之后再检查 U 盘 / adb push 的待导入题库，避免拖慢启动。
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    checkPendingImport();
                } catch (Throwable ignored) {
                    // 导入提示失败不应影响设置页
                }
            }
        });

        requestStoragePermission();
        gate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        updateAiStatus();
        // 开关是打开状态但服务没跑（进程被系统回收过）→ 回到本页时兜底拉起。
        if (prefs.isControlEnabled() && !ControlService.isRunning()) {
            ControlService.start(this);
        }
        updateControlStatus();
        // 手机端改完设置后回到本页要看到最新值（阈值 / 自动移出 / 学科计数）。
        if (thresholdBar != null) {
            thresholdBar.setProgress(prefs.getMasteredThreshold() - 1);
            updateThresholdLabel(prefs.getMasteredThreshold());
        }
        if (subjectsList != null) {
            buildSubjectsList();
        }
        updateAutoRemove();
        updateStrict();
        // 服务是异步启动的，稍后再刷新一次，避免一直显示「启动中」。
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                updateControlStatus();
            }
        }, 1200);
    }

    private void toggleControlService() {
        if (prefs.isControlEnabled()) {
            ControlService.stop(this);
            Toast.makeText(this, "已关闭局域网控制服务，端口已释放", Toast.LENGTH_SHORT).show();
        } else {
            ControlService.start(this);
            Toast.makeText(this, "正在开启局域网控制服务", Toast.LENGTH_SHORT).show();
        }
        updateControlStatus();
        // 服务创建是异步的，稍等再刷新一次，让「运行中（端口…）」及时显示出来。
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                updateControlStatus();
            }
        }, 1200);
    }

    private void updateControlStatus() {
        boolean enabled = prefs.isControlEnabled();
        boolean running = ControlService.isRunning();
        int port = ControlService.getPort();
        if (enabled && running && port > 0) {
            controlState.setText("局域网控制服务：运行中（端口 " + port + "）");
            controlState.setTextColor(getResources().getColor(R.color.ok));
            controlHint.setText("手机与电视连同一 Wi-Fi，打开 http://"
                    + QuestionHttpServer.localIp() + ":" + port
                    + "\n接口：/api/summary、/api/settings、/api/pass、/api/revoke、/api/clear-records"
                    + "（写操作需要 X-Pin）。关闭开关即停止服务并释放端口。");
            controlButton.setText("关闭局域网控制服务");
        } else if (enabled) {
            controlState.setText("局域网控制服务：启动中或启动失败");
            controlState.setTextColor(getResources().getColor(R.color.accent));
            String error = ControlService.getLastError();
            controlHint.setText(error.isEmpty()
                    ? "正在启动前台服务，稍候回到本页查看状态。"
                    : error + "，请关闭再开启重试。");
            controlButton.setText("关闭局域网控制服务");
        } else {
            controlState.setText("局域网控制服务：已关闭");
            controlState.setTextColor(getResources().getColor(R.color.text_secondary));
            controlHint.setText("开启后手机可与电视连同一 Wi-Fi 随时更新题库、修改放行时长与受限应用（需先设置 4 位 PIN）。");
            controlButton.setText("开启局域网控制服务");
        }
    }

    private void updateServiceStatus() {
        boolean connected = QuizAccessibilityService.isConnected();
        boolean enabled = isServiceEnabledInSettings();
        if (connected) {
            serviceState.setText("无障碍服务：运行中（拦截已生效）");
            serviceState.setTextColor(getResources().getColor(R.color.ok));
            serviceHint.setText("孩子打开受限应用时会自动弹出答题遮罩；答对后按应用单独放行。");
        } else if (enabled) {
            serviceState.setText("无障碍服务：已在系统开启，等待连接");
            serviceState.setTextColor(getResources().getColor(R.color.accent));
            serviceHint.setText("若长时间不生效：把本应用退到后台再打开一次，或在系统设置里关掉再开启。");
        } else {
            serviceState.setText("无障碍服务：未开启（受限应用不会被拦截）");
            serviceState.setTextColor(getResources().getColor(R.color.danger));
            serviceHint.setText("开启路径：电视「设置」→「无障碍」→「已下载的服务」→「答题家长控制」→ 开启。\n"
                    + "本机系统无障碍设置页可能打不开，点下方按钮会显示手动步骤和 adb 命令。");
        }
    }

    /** 只读判断系统设置里是否已勾选本服务，不需要额外权限。 */
    private boolean isServiceEnabledInSettings() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 本机（飞利浦 58PUF7695_T3 / Android 8）的系统设置无障碍页会在 onResume 里注册
     * user_dictionary 的 ContentObserver；该 provider 在 ROM 里被移除，注册直接抛
     * SecurityException，整个 com.android.settings 进程崩溃（try/catch 抓不到别的进程）。
     * 所以跳转前先探测 provider，缺失就只给手动路径，绝不把设置进程拉崩。
     */
    private void openAccessibilitySettings() {
        if (!isSystemSettingsSafe()) {
            showAccessibilityGuide();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            showAccessibilityGuide();
        }
    }

    /** user_dictionary provider 存在，系统无障碍页才不会在 onResume 崩溃。 */
    private boolean isSystemSettingsSafe() {
        try {
            return getPackageManager().resolveContentProvider("user_dictionary", 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 不能跳转时的兜底：给出遥控器手动路径 + 可一键复制的 adb 命令。 */
    private void showAccessibilityGuide() {
        final String commands =
                "adb shell settings put secure enabled_accessibility_services "
                        + "com.family.quizlauncher/com.family.quizlauncher.QuizAccessibilityService\n"
                        + "adb shell settings put secure accessibility_enabled 1";
        String message = "本机的系统无障碍设置页无法打开（ROM 缺少 user_dictionary 服务），"
                + "请用以下任一方式开启：\n\n"
                + "方式一（遥控器）：\n"
                + "电视「设置」→「无障碍」→「已下载的服务」→「答题家长控制」→ 开启\n\n"
                + "方式二（电脑 adb，连接电视后执行）：\n"
                + commands;
        new AlertDialog.Builder(this)
                .setTitle("开启无障碍服务")
                .setMessage(message)
                .setPositiveButton("复制 adb 命令", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(commands);
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("adb", text));
                Toast.makeText(this, "adb 命令已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // 没有剪贴板也不影响用户按上面的路径手动操作
        }
    }

    private void checkPendingImport() {
        final java.io.File pending = QuestionImporter.findPendingImport(this);
        if (pending == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("发现待导入题库")
                .setMessage("文件：" + pending.getAbsolutePath() + "\n\n导入后旧题库会自动备份为 questions.bak。")
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        QuestionImporter.Result result = QuestionImporter.importFile(ParentActivity.this, pending);
                        Toast.makeText(ParentActivity.this, result.message, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("忽略", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        QuestionImporter.markIgnored(ParentActivity.this, pending);
                    }
                })
                .show();
    }

    /** 设置页自己把关：已设密码则校验，未设则要求先设置 4 位密码。 */
    private void gate() {
        if (prefs.hasPin()) {
            PinDialog.showVerify(this, "请输入家长密码", prefs.getPin(), new PinDialog.Listener() {
                @Override
                public void onSuccess() {
                    // 密码正确，设置页可操作
                }

                @Override
                public void onCancel() {
                    finish();
                }
            });
        } else {
            PinDialog.showSet(this, "首次使用，请设置 4 位家长密码", new PinDialog.SetListener() {
                @Override
                public void onPin(String pin) {
                    prefs.setPin(pin);
                }

                @Override
                public void onCancel() {
                    finish();
                }
            });
        }
    }

    private void changePin() {
        PinDialog.showSet(this, "设置新的 4 位家长密码", new PinDialog.SetListener() {
            @Override
            public void onPin(String pin) {
                prefs.setPin(pin);
                android.widget.Toast.makeText(ParentActivity.this, "家长密码已更新", android.widget.Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancel() {
                // 取消则保留原密码
            }
        });
    }

    /** 仅 Android 8/9 需要存储权限读取 U 盘；应用专属外部目录本身不需要权限。 */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT > 28) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    private void setupMinutes() {
        // 滑杆 0 → 1 分钟，1..24 → 5..120 分钟（步进 5，但能设到 1）
        minutesBar.setMax(24);
        minutesBar.setProgress(progressOfMinutes(prefs.getMinutes()));
        updateMinutesLabel(prefs.getMinutes());
        minutesBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int minutes = minutesOfProgress(progress);
                updateMinutesLabel(minutes);
                if (fromUser) {
                    prefs.setMinutes(minutes);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private static int progressOfMinutes(int minutes) {
        if (minutes <= 1) {
            return 0;
        }
        return Math.min(24, minutes / 5);
    }

    private static int minutesOfProgress(int progress) {
        return progress <= 0 ? 1 : progress * 5;
    }

    private void updateMinutesLabel(int minutes) {
        minutesLabel.setText("放行时长：" + minutes + " 分钟");
    }

    private void setupRequired() {
        requiredBar.setProgress(prefs.getRequired() - 1);
        updateRequiredLabel(prefs.getRequired());
        requiredBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int required = progress + 1;
                updateRequiredLabel(required);
                if (fromUser) {
                    prefs.setRequired(required);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void updateRequiredLabel(int required) {
        requiredLabel.setText("及格线：答对 " + required + " 题（共 5 题）");
    }

    /** v2.9：掌握阈值、自动移出、启用学科。 */
    private void setupMastery() {
        thresholdBar.setProgress(prefs.getMasteredThreshold() - 1);
        updateThresholdLabel(prefs.getMasteredThreshold());
        thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int threshold = progress + 1;
                updateThresholdLabel(threshold);
                if (fromUser) {
                    prefs.setMasteredThreshold(threshold);
                    buildSubjectsList();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        updateAutoRemove();
        buildSubjectsList();
    }

    private void updateThresholdLabel(int threshold) {
        thresholdLabel.setText("掌握阈值：答对 " + threshold + " 次算掌握（1~10）");
    }

    private void updateAutoRemove() {
        boolean auto = prefs.isAutoRemove();
        autoRemoveLabel.setText("掌握后自动移出题库：" + (auto ? "已开启" : "关闭"));
        autoRemoveLabel.setTextColor(getResources().getColor(
                auto ? R.color.ok : R.color.text_primary));
        autoRemoveButton.setText(auto ? "关闭自动移出" : "开启自动移出");
    }

    /** 学科开关列表：显示每个学科的题数 / 已掌握 / 已移出，点击切换启用状态。 */
    private void buildSubjectsList() {
        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        QuestionStats stats = QuestionStats.get(this);
        subjectsList.removeAllViews();
        if (bank.subjects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("题库为空");
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setTextSize(16);
            subjectsList.addView(empty);
            return;
        }
        Set<String> allKeys = new LinkedHashSet<String>();
        for (QuestionBank.Subject subject : bank.subjects) {
            allKeys.add(subject.key);
        }
        for (final QuestionBank.Subject subject : bank.subjects) {
            boolean enabled = prefs.isSubjectEnabled(subject.key);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.row_bg);
            row.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
            rowParams.bottomMargin = Ui.dp(this, 8);
            subjectsList.addView(row, rowParams);

            TextView name = new TextView(this);
            name.setText(subject.name + " · " + subject.questions.size() + " 题");
            name.setTextSize(18);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView state = new TextView(this);
            int mastered = stats.masteredCount(subject.questions);
            int removed = stats.removedCount(subject.questions);
            state.setText((enabled ? "启用" : "未启用")
                    + " · 掌握 " + mastered + " · 移出 " + removed);
            state.setTextSize(16);
            state.setTextColor(getResources().getColor(enabled ? R.color.ok : R.color.text_secondary));
            row.addView(state);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSubject(subject.key, allKeys);
                }
            });
        }
    }

    /** 空集合 = 全部启用（契约语义）；切换时避免出现「全部关闭」的死状态。 */
    private void toggleSubject(String key, Set<String> allKeys) {
        Set<String> enabled = prefs.getEnabledSubjects();
        if (enabled.isEmpty()) {
            // 当前全部启用 → 关掉这一个
            Set<String> next = new LinkedHashSet<String>(allKeys);
            next.remove(key);
            prefs.setEnabledSubjects(next);
        } else if (enabled.contains(key)) {
            enabled.remove(key);
            prefs.setEnabledSubjects(enabled);
        } else {
            enabled.add(key);
            prefs.setEnabledSubjects(enabled.size() >= allKeys.size()
                    ? new HashSet<String>() : enabled);
        }
        buildSubjectsList();
    }

    private void setupAiKeys() {
        ((Button) findViewById(R.id.btn_llm_key)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyDialog(true);
            }
        });
        ((Button) findViewById(R.id.btn_tts_key)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showKeyDialog(false);
            }
        });
        ((Button) findViewById(R.id.btn_ai_page)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ParentActivity.this, AiSettingsActivity.class));
            }
        });
        ((Button) findViewById(R.id.btn_keys_clear)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(ParentActivity.this)
                        .setTitle("清除 AI Key")
                        .setMessage("只清除本机填写的 Key，随后回落到随 APK 内置的默认 Key；"
                                + "内置 Key 不可被清除。")
                        .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ApiKeys.clearAll(ParentActivity.this);
                                Prefs.get(ParentActivity.this).setAiTest("llm", "");
                                Prefs.get(ParentActivity.this).setAiTest("tts", "");
                                updateAiStatus();
                                Toast.makeText(ParentActivity.this, "已清除填写的 Key，回落到内置默认",
                                        Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        updateAiStatus();
    }

    /** 只显示「已配置 / 未配置」，不回显 Key，也不打印日志。 */
    private void updateAiStatus() {
        boolean llm = ApiKeys.hasLlmKey(this);
        boolean tts = ApiKeys.hasTtsKey(this);
        String llmState = ApiKeys.isLlmBuiltin(this) ? "已内置"
                : (llm ? "已配置（" + ApiKeys.mask(ApiKeys.getLlmKey(this)) + "）" : "未配置");
        String ttsState = ApiKeys.isTtsBuiltin(this) ? "已内置"
                : (tts ? "已配置（" + ApiKeys.mask(ApiKeys.getTtsKey(this)) + "）" : "未配置");
        Prefs prefs = Prefs.get(this);
        String lastLlm = prefs.getAiTest("llm");
        String lastTts = prefs.getAiTest("tts");
        String text = "解析服务（Agnes）：" + llmState
                + "　语音朗读（MiMo）：" + ttsState
                + "\n" + (llm
                ? "答题页会显示「不会？看提示」，只给思路不给答案。"
                : "当前没有可用的解析 Key（内置默认缺失），答题页提示按钮显示「未配置解析服务」。")
                + (tts ? "" : "\n未配置语音 Key 时，朗读会尝试系统中文语音；没有中文语音包则只显示文字。")
                + (lastLlm.isEmpty() ? "" : "\nAgnes 最近测试：" + lastLlm)
                + (lastTts.isEmpty() ? "" : "\nMiMo 最近测试：" + lastTts);
        if (aiStatus != null) {
            aiStatus.setText(text);
        }
    }

    private void showKeyDialog(final boolean llmKey) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(llmKey ? "粘贴 Agnes Key（sk-…）" : "粘贴 MiMo TTS Key");
        input.setTextSize(18);
        int pad = Ui.dp(this, 22);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle(llmKey ? "填写 Agnes 解析 Key" : "填写 MiMo 语音 Key")
                .setMessage("Key 会用 Android Keystore 加密后保存在本机，"
                        + "不会写进 APK、源码或日志。")
                .setView(input)
                .setPositiveButton("加密保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String key = input.getText() == null ? ""
                                : input.getText().toString().trim();
                        if (key.isEmpty()) {
                            Toast.makeText(ParentActivity.this, "没有填写，未保存",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (llmKey) {
                            ApiKeys.saveLlmKey(ParentActivity.this, key);
                            Prefs.get(ParentActivity.this).setAiTest("llm", "");
                        } else {
                            ApiKeys.saveTtsKey(ParentActivity.this, key);
                            Prefs.get(ParentActivity.this).setAiTest("tts", "");
                        }
                        updateAiStatus();
                        Toast.makeText(ParentActivity.this,
                                "已加密保存到本机", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** v3.0：严格模式开关、白名单编辑、防自锁提示。 */
    private void setupStrict() {
        strictButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setStrictMode(!prefs.isStrictMode());
                updateStrict();
                Toast.makeText(ParentActivity.this,
                        prefs.isStrictMode()
                                ? "严格模式已开启：白名单外的应用都要答题"
                                : "严格模式已关闭：恢复按受限名单拦截",
                        Toast.LENGTH_SHORT).show();
            }
        });
        ((Button) findViewById(R.id.btn_allow_edit)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllowListDialog();
            }
        });
        ((Button) findViewById(R.id.btn_allow_default)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetAllowList();
                updateStrict();
                Toast.makeText(ParentActivity.this, "已恢复默认白名单", Toast.LENGTH_SHORT).show();
            }
        });
        updateStrict();
    }

    private void updateStrict() {
        boolean on = prefs.isStrictMode();
        Set<String> allow = prefs.getAllowList();
        strictState.setText("严格模式：" + (on ? "已开启（白名单外全部要答题）" : "关闭"));
        strictState.setTextColor(getResources().getColor(on ? R.color.ok : R.color.text_primary));
        strictButton.setText(on ? "一键关闭严格模式" : "开启严格模式");

        boolean hasLauncher = AppRepository.isAllowListed(allow, "com.ktcp.launcher");
        boolean hasSettings = AppRepository.isAllowListed(allow, "com.android.settings");
        StringBuilder hint = new StringBuilder();
        hint.append("白名单 ").append(allow.size()).append(" 条（支持 com.android.providers.* 前缀）。")
                .append("\n桌面、系统设置、SystemUI、安装器、输入法与媒体 Provider 由内置硬白名单兜底，始终放行。");
        if (!hasLauncher || !hasSettings) {
            hint.append("\n⚠ 白名单缺少：")
                    .append(hasLauncher ? "" : "桌面 ")
                    .append(hasSettings ? "" : "系统设置 ")
                    .append("。硬白名单仍会放行，不会锁死；建议点「恢复默认白名单」。");
        }
        strictHint.setText(hint.toString());
        strictHint.setTextColor(getResources().getColor(
                (!hasLauncher || !hasSettings) ? R.color.accent : R.color.text_secondary));
    }

    /** 白名单编辑：列出本机所有应用，可逐个勾选放行；"*" 前缀条目保持不动。 */
    private void showAllowListDialog() {
        final List<AppEntry> apps = AppRepository.load(this);
        final Set<String> allow = prefs.getAllowList();
        CharSequence[] labels = new CharSequence[apps.size()];
        final boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppEntry app = apps.get(i);
            labels[i] = app.label + "（" + app.packageName + "）";
            checked[i] = AppRepository.isAllowListed(allow, app.packageName);
        }
        new AlertDialog.Builder(this)
                .setTitle("严格模式白名单（勾选 = 直接放行）")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> next = new HashSet<String>(prefs.getAllowList());
                        for (int i = 0; i < apps.size(); i++) {
                            String pkg = apps.get(i).packageName;
                            if (checked[i]) {
                                next.add(pkg);
                            } else if (!AppRepository.isAllowListed(
                                    prefs.getAllowList(), pkg)) {
                                // 本来就没在名单里，不用动
                            } else {
                                next.remove(pkg);
                            }
                        }
                        prefs.setAllowList(next);
                        updateStrict();
                        Toast.makeText(ParentActivity.this, "白名单已更新", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void buildAppsList() {
        List<AppEntry> apps = AppRepository.load(this);
        appsList.removeAllViews();

        for (AppEntry app : apps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setBackgroundResource(R.drawable.row_bg);
            row.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54));
            rowParams.bottomMargin = Ui.dp(this, 8);
            appsList.addView(row, rowParams);

            TextView name = new TextView(this);
            name.setText(app.label);
            name.setTextSize(18);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            name.setSingleLine(true);
            name.setFocusable(false);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, nameParams);

            TextView status = new TextView(this);
            status.setTextSize(16);
            status.setFocusable(false);
            row.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            styleStatus(status, prefs.isRestricted(app.packageName));

            final String packageName = app.packageName;
            final TextView statusView = status;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean restricted = !prefs.isRestricted(packageName);
                    prefs.setRestricted(packageName, restricted);
                    styleStatus(statusView, restricted);
                }
            });
        }
    }

    private void styleStatus(TextView view, boolean restricted) {
        if (restricted) {
            view.setText("受限 · 需答题");
            view.setTextColor(getResources().getColor(R.color.danger));
        } else {
            view.setText("放行 · 直接打开");
            view.setTextColor(getResources().getColor(R.color.ok));
        }
    }
}
