package com.family.quizlauncher;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.family.quizlauncher.ai.ApiKeys;
import com.family.quizlauncher.ai.Hint;
import com.family.quizlauncher.ai.HintCache;
import com.family.quizlauncher.ai.HintClient;
import com.family.quizlauncher.ai.SpeechRouter;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 全屏答题遮罩（v2.4）。
 *
 * 使用 WindowManager + TYPE_ACCESSIBILITY_OVERLAY（API 22+，无需 SYSTEM_ALERT_WINDOW，
 * 也不依赖从后台启动 Activity）。窗口可获取焦点，遥控器方向键 / 确定键都能操作；
 * 根视图 OverlayRootView 会吞掉返回键与 HOME 键，防止按键穿透到下层受限应用。
 *
 * v2.4 关键改动：
 * - 所有内容在安全区内（XML 四周 padding），题干/选项可滚动，底部操作区常驻；
 * - 选项按钮 setAllCaps(false)，严格按 JSON 原文大小写显示；
 * - 答错不再 1 秒闪走：复盘页展示题号 + 正确答案，按确定键继续；
 * - 结果页也不再自动消失，必须按确定键；
 * - 「不会？看提示」：Agnes 提示（13 秒硬超时）+ 泄露拦截 + 本地兜底 + MiMo TTS；
 * - 遮罩出现期间请求音频焦点并静音 STREAM_MUSIC，退出严格恢复。
 */
public class QuizOverlay {

    public interface Callback {
        /** 答对达标：已写入放行时间，遮罩即将移除。 */
        void onPass(String packageName, long remainMs);

        /** 未通过 / 主动取消 / 按返回：调用方应把用户送回桌面。 */
        void onFail(String packageName);
    }

    private static final int QUESTION_COUNT = 5;
    /**
     * 看门狗：遮罩出现后连续这么久没有任何按键/触摸，就自动撤销并回桌面。
     * 这是「遮罩卡住、遥控器按不动」时的最后一道逃生口——宁可错退，也不把电视锁死。
     */
    private static final long WATCHDOG_MS = 120000L;
    private static final long CORRECT_ADVANCE_MS = 800L;

    private static QuizOverlay sActive;
    /** 焦点恢复窗口期（时间戳），期间服务忽略系统窗口事件。 */
    private static volatile long sRecoveringUntil = 0L;

    private final Context context;
    private final WindowManager windowManager;
    private final Callback callback;
    private final String targetPackage;
    private final String targetLabel;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final OverlayRootView root;
    private WindowManager.LayoutParams windowParams;
    private final LinearLayout body;
    private final LinearLayout resultPanel;
    private final LinearLayout hintPanel;
    private final TextView appView;
    private final TextView metaView;
    private final TextView progressView;
    private final TextView questionView;
    private final ScrollView questionScroll;
    private final LinearLayout optionsView;
    private final TextView feedbackView;
    private final TextView resultTitle;
    private final TextView resultDetail;
    private final TextView resultReview;
    /** v2.9：复盘信息分层——你选的 / 正确答案 / 提示入口。 */
    private final LinearLayout reviewCards;
    private final TextView reviewChosen;
    private final TextView reviewCorrect;
    private final Button reviewHintButton;
    /** v2.9：顶部细进度条（两段权重实现，不引第三方控件）。 */
    private final View progressFill;
    private final View progressRest;
    /** v2.7：底部大按钮已移除，改成紧凑的「按确定键继续」文本提示（本身可点击/按确定触发）。 */
    private final TextView resultHint;
    /** 结果页按确定键要执行的动作（继续 / 通过后关闭 / 回桌面）。 */
    private Runnable resultAction;
    private final Button exitButton;
    private final Button hintButton;
    private final TextView hintSource;
    private final TextView hintText;
    private final TextView hintSteps;
    private final TextView hintConcept;
    private final TextView hintPitfall;
    private final Button hintSpeakButton;
    private final Button hintCloseButton;
    /** 当前题目的第一个选项 / 最后一排选项，用于遥控器焦点上下打通。 */
    private View firstOptionButton;
    private final List<View> lastRowButtons = new ArrayList<View>();
    /** 提示面板是否从「答错复盘」打开：关闭提示后要回到复盘页而不是答题页。 */
    private boolean hintFromReview = false;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            // 窗口一直盖在最上层却没人能操作：撤掉遮罩，把人送回桌面。
            failAndHome();
        }
    };

    /**
     * 焦点守卫：系统 TTS 引擎、语音包下载弹窗、截图/自动化工具都可能把窗口焦点
     * 抢走，此时遮罩还在、按键却打到了下层视频 App。每秒检查一次，丢焦点就重新
     * 申请，保证遥控器始终操作遮罩。
     */
    private final Runnable focusGuard = new Runnable() {
        @Override
        public void run() {
            if (dismissed) {
                return;
            }
            if (!root.hasWindowFocus()) {
                reassertFocus();
            }
            handler.postDelayed(this, 1000L);
        }
    };

    private List<Question> questions = new ArrayList<Question>();
    /** 答错的题：{题号, 正确选项下标, 用户选择下标}。 */
    private final List<int[]> wrongAnswers = new ArrayList<int[]>();
    private int index = 0;
    private int correct = 0;
    private int required = 4;
    private boolean locked = false;
    private boolean finished = false;
    private boolean dismissed = false;
    private boolean hintVisible = false;
    private boolean reasserting = false;

    private HintClient.Callback hintCallback;
    private Hint currentHint;

    private QuizOverlay(Context context, String packageName, String label, Callback callback) {
        this.context = context;
        this.targetPackage = packageName;
        this.targetLabel = label;
        this.callback = callback;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        Context themed = new ContextThemeWrapper(context, R.style.AppTheme);
        LayoutInflater inflater = LayoutInflater.from(themed);
        root = (OverlayRootView) inflater.inflate(R.layout.overlay_quiz, null);

        body = (LinearLayout) root.findViewById(R.id.overlay_body);
        resultPanel = (LinearLayout) root.findViewById(R.id.overlay_result);
        hintPanel = (LinearLayout) root.findViewById(R.id.overlay_hint_panel);
        // 语音/提示面板内的滚动区，不影响答题焦点。
        root.setKeyHandler(new OverlayRootView.KeyHandler() {
            @Override
            public boolean onKeyEvent(android.view.KeyEvent event) {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (hintPanel.getVisibility() == View.VISIBLE) {
                    return false;
                }
                if (resultPanel.getVisibility() == View.VISIBLE) {
                    // v2.7：底部大按钮已去掉，按确定键仍然继续；焦点在返回角标上时让给角标。
                    int key = event.getKeyCode();
                    if (resultAction != null
                            && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            || key == android.view.KeyEvent.KEYCODE_ENTER
                            || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                            && root.findFocus() != exitButton
                            && root.findFocus() != reviewHintButton) {
                        resultAction.run();
                        return true;
                    }
                    return false;
                }
                View focused = root.findFocus();
                if (focused == null) {
                    return false;
                }
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        && lastRowButtons.contains(focused)) {
                    // 最后一排选项再按下：进题干行的「提示」按钮。
                    hintButton.requestFocus();
                    return true;
                }
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_UP
                        && focused == hintButton) {
                    // 「提示」再按上：左上角返回角标。
                    exitButton.requestFocus();
                    return true;
                }
                if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        && focused == exitButton) {
                    hintButton.requestFocus();
                    return true;
                }
                return false;
            }
        });
        appView = (TextView) root.findViewById(R.id.overlay_app);
        metaView = (TextView) root.findViewById(R.id.overlay_meta);
        progressView = (TextView) root.findViewById(R.id.overlay_progress);
        questionView = (TextView) root.findViewById(R.id.overlay_question);
        questionScroll = (ScrollView) root.findViewById(R.id.overlay_question_scroll);
        optionsView = (LinearLayout) root.findViewById(R.id.overlay_options);
        feedbackView = (TextView) root.findViewById(R.id.overlay_feedback);
        resultTitle = (TextView) root.findViewById(R.id.overlay_result_title);
        resultDetail = (TextView) root.findViewById(R.id.overlay_result_detail);
        resultReview = (TextView) root.findViewById(R.id.overlay_result_review);
        reviewCards = (LinearLayout) root.findViewById(R.id.overlay_review_cards);
        reviewChosen = (TextView) root.findViewById(R.id.overlay_review_chosen);
        reviewCorrect = (TextView) root.findViewById(R.id.overlay_review_correct);
        reviewHintButton = (Button) root.findViewById(R.id.overlay_review_hint);
        progressFill = root.findViewById(R.id.overlay_progress_fill);
        progressRest = root.findViewById(R.id.overlay_progress_rest);
        resultHint = (TextView) root.findViewById(R.id.overlay_result_hint);
        exitButton = (Button) root.findViewById(R.id.overlay_exit);
        hintButton = (Button) root.findViewById(R.id.overlay_hint);
        hintSource = (TextView) root.findViewById(R.id.overlay_hint_source);
        hintText = (TextView) root.findViewById(R.id.overlay_hint_text);
        hintSteps = (TextView) root.findViewById(R.id.overlay_hint_steps);
        hintConcept = (TextView) root.findViewById(R.id.overlay_hint_concept);
        hintPitfall = (TextView) root.findViewById(R.id.overlay_hint_pitfall);
        hintSpeakButton = (Button) root.findViewById(R.id.overlay_hint_speak);
        hintCloseButton = (Button) root.findViewById(R.id.overlay_hint_close);

        setUpButton(exitButton);
        setUpButton(hintButton);
        setUpButton(hintSpeakButton);
        setUpButton(hintCloseButton);
        setUpButton(reviewHintButton);
        reviewHintButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHint();
            }
        });
        resultHint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        resultHint.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (resultAction != null) {
                    resultAction.run();
                }
            }
        });
        questionView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        root.setKeepScreenOn(true);
        root.setInteractionAction(new Runnable() {
            @Override
            public void run() {
                resetWatchdog();
            }
        });
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelToHome();
            }
        });
        hintButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openHint();
            }
        });
        hintCloseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeHint();
            }
        });
        hintSpeakButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speakHint();
            }
        });
        ((Button) root.findViewById(R.id.overlay_hint_stop))
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        SpeechRouter.stop();
                        hintSpeakButton.setText("朗读提示");
                        hintSource.setText("已停止朗读（提示文字仍可查看）");
                    }
                });
        setUpButton((Button) root.findViewById(R.id.overlay_hint_stop));
        root.setBackAction(new Runnable() {
            @Override
            public void run() {
                handleBack();
            }
        });
        root.setHomeAction(new Runnable() {
            @Override
            public void run() {
                cancelToHome();
            }
        });
    }

    /** 统一按钮字体与大小写：绝不使用系统默认的 textAllCaps。 */
    private void setUpButton(Button button) {
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
    }

    /** 添加窗口。addView 失败会抛出异常，由调用方降级为「弹 Activity」或「回桌面」。 */
    public static QuizOverlay show(Context context, String packageName, String label,
                                   Callback callback) throws Exception {
        QuizOverlay overlay = new QuizOverlay(context, packageName, label, callback);
        overlay.attach();
        sActive = overlay;
        overlay.begin();
        return overlay;
    }

    public static boolean isShowing() {
        return sActive != null;
    }

    public static String getTargetPackage() {
        return sActive == null ? null : sActive.targetPackage;
    }

    /** 遮罩当前是否真的拿着窗口焦点（用来忽略电视上后台窗口的噪音事件）。 */
    public static boolean hasWindowFocus() {
        QuizOverlay overlay = sActive;
        return overlay != null && !overlay.dismissed && overlay.root.hasWindowFocus();
    }

    /** 服务被系统中断 / 关闭时清理遮罩，不影响调用方。 */
    public static void dismissActive() {
        QuizOverlay overlay = sActive;
        if (overlay != null) {
            overlay.dismiss();
        }
    }

    private void attach() throws Exception {
        android.util.Log.d("QuizGate", "attach overlay target=" + targetPackage);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        // 不让输入法把这个遮罩当成输入目标，否则 IME 窗口会盖在遮罩上
                        // 抢走遥控器按键，导致选项按不动。
                        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        windowParams = params;
        windowManager.addView(root, params);
        // 窗口挂上之后再动音频：addView 失败时不会误静音。
        AudioSilencer.enter(context);
    }

    /**
     * 重新抢回窗口焦点：短暂把窗口设为不可获焦再改回来，强迫 WindowManager 重新
     * 计算焦点。期间置一个「焦点恢复中」标记，让拦截服务忽略这期间的系统窗口事件，
     * 避免被误判成「切回桌面」而撤罩。
     */
    private void reassertFocus() {
        if (dismissed || windowParams == null || reasserting) {
            return;
        }
        reasserting = true;
        sRecoveringUntil = System.currentTimeMillis() + 2500L;
        try {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(root, windowParams);
        } catch (Throwable ignored) {
            // ignore
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(root, windowParams);
                    if (!dismissed) {
                        root.requestFocus();
                        focusVisibleButton();
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
                reasserting = false;
            }
        }, 150L);
    }

    /** 焦点恢复窗口期内，服务应忽略系统窗口事件，不要撤罩。 */
    public static boolean isRecoveringFocus() {
        return System.currentTimeMillis() < sRecoveringUntil;
    }

    /** 焦点落到当前可见区域里的第一个可点击按钮。 */
    private void focusVisibleButton() {
        if (hintVisible) {
            hintCloseButton.requestFocus();
        } else if (resultPanel.getVisibility() == View.VISIBLE) {
            resultHint.requestFocus();
        } else if (body.getVisibility() == View.VISIBLE) {
            requestOptionFocus();
        }
    }

    private void startQuiz() {
        List<Question> picked = QuestionBank.pick(context, QUESTION_COUNT);
        if (picked.isEmpty()) {
            // 题库三重兜底后仍为空时不应把用户困住，直接视为失败。
            handler.post(new Runnable() {
                @Override
                public void run() {
                    failAndHome();
                }
            });
            return;
        }
        questions = new ArrayList<Question>(picked);

        index = 0;
        correct = 0;
        wrongAnswers.clear();
        locked = false;
        finished = false;
        required = Math.min(Prefs.get(context).getRequired(), questions.size());

        appView.setText("《" + targetLabel + "》");
        long remain = Prefs.get(context).getRemainMs(targetPackage);
        String remainText = remain > 0 ? ("剩余 " + describeMs(remain)) : "未解锁";
        metaView.setText("通过放行 " + Prefs.get(context).getMinutes() + " 分钟 · " + remainText);
        hintButton.setText("提示");

        body.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        hintPanel.setVisibility(View.GONE);
        hintVisible = false;
        renderQuestion();
        resetWatchdog();
        handler.removeCallbacks(focusGuard);
        handler.postDelayed(focusGuard, 1000L);

        root.post(new Runnable() {
            @Override
            public void run() {
                if (optionsView.getChildCount() > 0) {
                    optionsView.getChildAt(0).requestFocus();
                }
            }
        });
    }

    private void renderQuestion() {
        Question question = questions.get(index);
        progressView.setText("第 " + (index + 1) + " / " + questions.size() + " 题");
        updateProgressBar();
        long remain = Prefs.get(context).getRemainMs(targetPackage);
        String remainText = remain > 0 ? ("剩余 " + describeMs(remain)) : "未解锁";
        metaView.setText("已对 " + correct + " · 需对 " + required + " · " + remainText);
        if (question.caseSensitive) {
            // 选项只差大小写（或题干在考大小写）时明确提示，判题按原文严格比对。
            metaView.append(" · 区分大小写");
        }
        // 严格按 JSON 原文渲染，不改大小写。
        questionView.setText(question.text);
        questionView.setTextSize(adaptiveQuestionSp(question.text));
        // 长题干限高并单独滚动：始终从第一行开始显示，也不会把选项挤出屏幕。
        ViewGroup.LayoutParams questionParams = questionScroll.getLayoutParams();
        boolean longQuestion = question.text != null && question.text.length() > 70;
        questionParams.height = longQuestion
                ? (int) (context.getResources().getDisplayMetrics().heightPixels * 0.24f)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        questionScroll.setLayoutParams(questionParams);
        questionScroll.scrollTo(0, 0);
        feedbackView.setText("");
        feedbackView.setVisibility(View.GONE);

        body.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        hintPanel.setVisibility(View.GONE);
        hintVisible = false;
        hintFromReview = false;
        exitButton.setVisibility(View.VISIBLE);
        buildOptions(question);
        locked = false;

        hintButton.setText("提示");

        root.post(new Runnable() {
            @Override
            public void run() {
                requestOptionFocus();
            }
        });
        // 题量小，进入本题时就预取提示；未配置 Key 时该方法直接返回，不联网。
        HintClient.prefetch(context, question);
    }

    /** 顶部细进度条：已答完的题占比例，两段 View 权重实现，不引第三方库。 */
    private void updateProgressBar() {
        int total = Math.max(1, questions.size());
        float ratio = (float) index / total;
        ratio = Math.max(0.03f, Math.min(1f, ratio));
        LinearLayout.LayoutParams fill = (LinearLayout.LayoutParams) progressFill.getLayoutParams();
        LinearLayout.LayoutParams rest = (LinearLayout.LayoutParams) progressRest.getLayoutParams();
        fill.weight = ratio;
        rest.weight = Math.max(0.001f, 1f - ratio);
        progressFill.setLayoutParams(fill);
        progressRest.setLayoutParams(rest);
    }

    /**
     * 题干预设字号按长度自适应：200 字以内都能在 10 行内显示完，
     * 再长则由 ScrollView 滚动，绝不会压到选项上。
     */
    private static float adaptiveQuestionSp(String text) {
        int length = text == null ? 0 : text.length();
        if (length <= 40) {
            return 38f;
        }
        if (length <= 90) {
            return 34f;
        }
        if (length <= 150) {
            return 30f;
        }
        return 26f;
    }

    /**
     * 选项布局（v2.6）：按字符数自适应。
     *
     * 先按显示宽度加权（中文/全角≈2，英文/数字≈1）算出最长选项的权重：
     * - 权重决定列数（短选项 3 列、中等 2 列、长选项 1 列全宽）与字号档位；
     * - 行高与按钮高度一律 wrap_content，配合 minHeight 保证短选项也够大；
     * - 不设 maxLines / ellipsize，绝不允许「…」或半行裁切；
     * - 超出可用高度时由外层 overlay_options_scroll 滚动，焦点移动会自动滚到可见。
     */
    private void buildOptions(Question question) {
        optionsView.removeAllViews();
        int count = question.options.length;
        if (count <= 0) {
            return;
        }
        int maxWeight = 0;
        for (String option : question.options) {
            int weight = displayWeight(option);
            if (weight > maxWeight) {
                maxWeight = weight;
            }
        }
        int columns = optionColumns(count, maxWeight);
        float textSp = optionTextSp(maxWeight);
        int rows = (count + columns - 1) / columns;
        final List<View> builtOptions = new ArrayList<View>();
        firstOptionButton = null;
        lastRowButtons.clear();
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                rowParams.topMargin = Ui.dp(context, 10);
            }
            optionsView.addView(row, rowParams);
            if (r == rows - 1) {
                lastRowButtons.clear();
            }

            for (int c = 0; c < columns; c++) {
                int optionIndex = r * columns + c;
                if (optionIndex >= count) {
                    // 最后一排不足时用空占位，保证卡片宽度一致。
                    View spacer = new View(context);
                    LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c > 0) {
                        spacerParams.leftMargin = Ui.dp(context, 14);
                    }
                    row.addView(spacer, spacerParams);
                    continue;
                }
                LinearLayout card = buildOptionCard(question, optionIndex, textSp);
                card.setId(View.generateViewId());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) {
                    params.leftMargin = Ui.dp(context, 14);
                }
                row.addView(card, params);
                builtOptions.add(card);
                if (firstOptionButton == null) {
                    firstOptionButton = card;
                }
                if (r == rows - 1) {
                    lastRowButtons.add(card);
                }
            }
        }
        // v2.7 焦点路径保留：选项 ↑ → 题干行「提示」；「提示」↑ → 左上角返回角标；
        // 最后一排选项 ↓ → 「提示」；「提示」↓ → 第一个选项。
        for (View button : lastRowButtons) {
            button.setNextFocusDownId(hintButton.getId());
        }
        if (firstOptionButton != null) {
            firstOptionButton.setNextFocusUpId(hintButton.getId());
            hintButton.setNextFocusDownId(firstOptionButton.getId());
            hintButton.setNextFocusLeftId(exitButton.getId());
            hintButton.setNextFocusUpId(exitButton.getId());
            exitButton.setNextFocusDownId(hintButton.getId());
            exitButton.setNextFocusRightId(hintButton.getId());
        }
        // 布局完成后打印每个选项的实测高度/行数，用于真机核对「文字有没有被裁」。
        final int columnCount = columns;
        optionsView.post(new Runnable() {
            @Override
            public void run() {
                android.util.Log.d("QuizGate", "options layout cols=" + columnCount
                        + " viewportH=" + ((View) optionsView.getParent()).getHeight());
                for (int i = 0; i < builtOptions.size(); i++) {
                    View card = builtOptions.get(i);
                    android.util.Log.d("QuizGate", "option[" + i + "] h=" + card.getHeight()
                            + " w=" + card.getWidth()
                            + " len=" + (i < question.options.length ? question.options[i].length() : 0));
                }
            }
        });
        requestOptionFocus();
    }

    /** 显示宽度：中文/全角算 2，英文/数字/半角算 1。 */
    private static int displayWeight(String text) {
        if (text == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += text.charAt(i) >= 0x2E80 ? 2 : 1;
        }
        return total;
    }

    /** 短选项 3 列、中等 2 列、长选项 1 列全宽（且不超过选项个数）。 */
    private static int optionColumns(int count, int maxWeight) {
        int columns;
        if (maxWeight <= 8) {
            columns = 3;
        } else if (maxWeight <= 14) {
            columns = 2;
        } else {
            columns = 1;
        }
        return Math.min(columns, count);
    }

    private static float optionTextSp(int maxWeight) {
        if (maxWeight <= 8) {
            return 30f;
        }
        if (maxWeight <= 14) {
            return 26f;
        }
        if (maxWeight <= 22) {
            return 22f;
        }
        return 20f;
    }

    /**
     * 选项卡片（v2.9）：圆角卡片 + 左侧色条 + 字母角标 + 完整换行文本。
     * 聚焦时描边变强调色、左侧色条点亮、轻微放大（1.02 倍），遥控器一眼能看出选了哪个。
     */
    private LinearLayout buildOptionCard(Question question, int optionIndex, float textSp) {
        String text = question.options[optionIndex];
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.option_card);
        card.setFocusable(true);
        card.setClickable(true);
        card.setMinimumHeight(Ui.dp(context, 56));
        card.setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 18), Ui.dp(context, 10));

        final View bar = new View(context);
        bar.setBackgroundResource(R.drawable.option_bar);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 5), ViewGroup.LayoutParams.MATCH_PARENT);
        barParams.rightMargin = Ui.dp(context, 14);
        card.addView(bar, barParams);

        TextView letter = new TextView(context);
        letter.setText(optionLetter(optionIndex));
        letter.setTextSize(19f);
        letter.setTextColor(context.getResources().getColor(R.color.accent));
        letter.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        letter.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams letterParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 34), ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(letter, letterParams);

        TextView optionText = new TextView(context);
        // 不做任何大小写转换：显示的就是 JSON 原文；允许换行、绝不省略。
        optionText.setText(text);
        optionText.setTextSize(textSp);
        optionText.setTextColor(context.getResources().getColor(R.color.text_primary));
        optionText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        optionText.setSingleLine(false);
        optionText.setMaxLines(Integer.MAX_VALUE);
        optionText.setEllipsize(null);
        optionText.setLineSpacing(Ui.dp(context, 3), 1f);
        optionText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(optionText, textParams);

        final boolean[] focused = new boolean[]{false};
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                focused[0] = hasFocus;
                bar.setBackgroundResource(hasFocus
                        ? R.drawable.option_bar_focus : R.drawable.option_bar);
                v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                        .setDuration(120).start();
            }
        });
        card.setOnClickListener(new OptionClickListener(optionIndex, question));
        return card;
    }

    private static String optionLetter(int index) {
        char letter = (char) ('A' + Math.max(0, Math.min(25, index)));
        return String.valueOf(letter);
    }

    private void requestOptionFocus() {
        if (optionsView.getChildCount() == 0) {
            return;
        }
        LinearLayout firstRow = (LinearLayout) optionsView.getChildAt(0);
        for (int i = 0; i < firstRow.getChildCount(); i++) {
            View child = firstRow.getChildAt(i);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) {
                child.requestFocus();
                return;
            }
        }
    }

    private void answer(int chosen, Question question) {
        if (locked || finished || dismissed || hintVisible) {
            return;
        }
        locked = true;
        boolean ok = chosen == question.answer;
        // v2.9：按题目 id 累计 answered/correct/wrong；达到阈值且开启 autoRemove 时自动移出。
        QuestionStats.get(context).record(question, ok);
        if (ok) {
            correct++;
            showFeedback(true, "✓  答对了！");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    advance();
                }
            }, CORRECT_ADVANCE_MS);
        } else {
            showFeedback(false, "✕  答错了，看下正确答案");
            wrongAnswers.add(new int[]{index, question.answer, chosen});
            showWrongReview(question, chosen);
        }
        setOptionsEnabled(false);
    }

    /** 反馈条：答对绿色勾、答错红色叉，用卡片底色区分，空内容时隐藏。 */
    private void showFeedback(boolean ok, String text) {
        feedbackView.setText(text);
        feedbackView.setTextColor(context.getResources().getColor(ok ? R.color.ok : R.color.danger));
        feedbackView.setBackgroundResource(ok ? R.drawable.card_ok : R.drawable.card_danger);
        feedbackView.setVisibility(View.VISIBLE);
    }

    /** 答错复盘：信息分层——你选的 / 正确答案 / 提示入口，必须按确定键才继续。 */
    private void showWrongReview(Question question, int chosen) {
        body.setVisibility(View.GONE);
        hintPanel.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);

        resultTitle.setText("第 " + (index + 1) + " 题答错了");
        resultTitle.setTextColor(context.getResources().getColor(R.color.danger));
        String correctText = question.answerText();
        String chosenText = (chosen >= 0 && chosen < question.options.length)
                ? question.options[chosen] : "?";
        resultDetail.setText("别急，看完正确答案再继续。");

        reviewCards.setVisibility(View.VISIBLE);
        reviewChosen.setText(chosenText);
        reviewCorrect.setText(correctText);
        reviewHintButton.setVisibility(View.VISIBLE);
        resultReview.setText("");
        resultHint.setText("按确定键继续");
        resultAction = new Runnable() {
            @Override
            public void run() {
                continueAfterReview();
            }
        };
        resultHint.requestFocus();
        resultHint.setNextFocusUpId(exitButton.getId());
        exitButton.setNextFocusDownId(resultHint.getId());
        exitButton.setVisibility(View.VISIBLE);
        resetWatchdog();
    }

    private void continueAfterReview() {
        if (dismissed) {
            return;
        }
        resultPanel.setVisibility(View.GONE);
        body.setVisibility(View.VISIBLE);
        index++;
        if (index >= questions.size()) {
            showFinalResult();
        } else {
            renderQuestion();
        }
    }

    private void setOptionsEnabled(boolean enabled) {
        for (int i = 0; i < optionsView.getChildCount(); i++) {
            View child = optionsView.getChildAt(i);
            if (child instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    row.getChildAt(j).setEnabled(enabled);
                }
            }
        }
    }

    private void advance() {
        if (dismissed) {
            return;
        }
        index++;
        if (index >= questions.size()) {
            showFinalResult();
        } else {
            renderQuestion();
        }
    }

    private void showFinalResult() {
        boolean passed = correct >= required;
        finished = true;
        addRecord(passed);

        body.setVisibility(View.GONE);
        hintPanel.setVisibility(View.GONE);
        hintVisible = false;
        resultPanel.setVisibility(View.VISIBLE);
        reviewCards.setVisibility(View.GONE);
        reviewHintButton.setVisibility(View.GONE);

        StringBuilder review = new StringBuilder();
        for (int[] wrong : wrongAnswers) {
            int questionNo = wrong[0] + 1;
            Question question = questions.get(wrong[0]);
            String correctText = question.answerText();
            String chosenText = (wrong[2] >= 0 && wrong[2] < question.options.length)
                    ? question.options[wrong[2]] : "?";
            review.append("第 ").append(questionNo).append(" 题")
                    .append("：正确答案「").append(correctText).append("」")
                    .append("，你选了「").append(chosenText).append("」\n");
        }
        resultReview.setText(review.length() == 0 ? "" : review.toString().trim());

        if (passed) {
            Prefs prefs = Prefs.get(context);
            prefs.grantPass(targetPackage);
            final long remain = prefs.getRemainMs(targetPackage);
            resultTitle.setText("答对 " + correct + "/" + questions.size() + "，通过！");
            resultTitle.setTextColor(context.getResources().getColor(R.color.ok));
            resultDetail.setText("已放行 " + prefs.getMinutes() + " 分钟（"
                    + describeMs(remain) + "）。\n按确定键开始观看。");
            resultHint.setText("按确定键开始观看");
            resultAction = new Runnable() {
                @Override
                public void run() {
                    dismissAndPass(remain);
                }
            };
        } else {
            resultTitle.setText("答对 " + correct + "/" + questions.size() + "，没有通过");
            resultTitle.setTextColor(context.getResources().getColor(R.color.danger));
            resultDetail.setText("需要答对 " + required + " 题才能解锁。\n按确定键回桌面，稍后再试。");
            resultHint.setText("按确定键回桌面");
            resultAction = new Runnable() {
                @Override
                public void run() {
                    failAndHome();
                }
            };
        }
        resultHint.requestFocus();
        resultHint.setNextFocusUpId(exitButton.getId());
        exitButton.setNextFocusDownId(resultHint.getId());
        exitButton.setVisibility(View.VISIBLE);
        resetWatchdog();
    }

    // ---------------- AI 提示 ----------------

    private void openHint() {
        if (dismissed || finished || hintVisible || index < 0 || index >= questions.size()) {
            return;
        }
        android.util.Log.d("QuizGate", "openHint index=" + index
                + " hasLlmKey=" + ApiKeys.hasLlmKey(context)
                + " builtin=" + ApiKeys.isLlmBuiltin(context));
        hintVisible = true;
        hintFromReview = resultPanel.getVisibility() == View.VISIBLE;
        body.setVisibility(View.GONE);
        resultPanel.setVisibility(View.GONE);
        hintPanel.setVisibility(View.VISIBLE);
        exitButton.setVisibility(View.GONE);
        currentHint = null;
        hintText.setText("正在想提示…（网络慢时可能需要 30–60 秒）");
        hintText.setTextColor(context.getResources().getColor(R.color.text_primary));
        hintSteps.setText("");
        hintConcept.setText("");
        hintPitfall.setText("");
        hintSpeakButton.setText("朗读提示");
        hintSpeakButton.setEnabled(false);

        final Question question = questions.get(index);
        if (!ApiKeys.hasLlmKey(context)) {
            hintSource.setText("未配置解析服务（显示本地思路）");
            applyHint(Hint.localFor(question), "本地思路（未配置解析服务，家长可在设置页填写 Key）");
        } else {
            Hint cached = HintCache.get().get(question.text);
            if (cached != null) {
                applyHint(cached, "AI 提示 · 已缓存（只给思路，不含答案）");
            } else {
                hintSource.setText("AI 提示（最快几秒，免费档慢时约 30–60 秒；可随时关闭继续答题）");
                hintCallback = new HintClient.Callback() {
                    @Override
                    public void onHint(Hint hint, boolean fromCache) {
                        if (dismissed || !hintVisible) {
                            return;
                        }
                        applyHint(hint, fromCache
                                ? "AI 提示 · 已缓存（只给思路，不含答案）"
                                : "AI 提示（只给思路，不含答案）");
                    }

                    @Override
                    public void onUnavailable(int reason) {
                        android.util.Log.d("QuizGate", "hint unavailable reason=" + reason);
                        if (dismissed || !hintVisible) {
                            return;
                        }
                        applyHint(Hint.localFor(question),
                                "AI 暂不可用（" + reasonText(reason) + "），已显示本地思路");
                    }
                };
                HintClient.request(context, question, hintCallback);
            }
        }
        // 等提示面板完成布局后再把焦点落到关闭按钮上，确保遥控器看得见高亮、
        // 并且不会把焦点留在已经隐藏的答题页。
        root.post(new Runnable() {
            @Override
            public void run() {
                if (!dismissed && hintVisible) {
                    hintCloseButton.requestFocus();
                }
            }
        });
        resetWatchdog();
    }

    private void applyHint(Hint hint, String source) {
        if (hint == null) {
            return;
        }
        currentHint = hint;
        android.util.Log.d("QuizGate", "hint shown source=" + source
                + " hintLen=" + hint.hint.length() + " steps=" + hint.steps.size());
        hintSource.setText(source);
        hintText.setText(hint.hint);
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < hint.steps.size(); i++) {
            if (i > 0) {
                steps.append('\n');
            }
            steps.append(i + 1).append(". ").append(hint.steps.get(i));
        }
        hintSteps.setText(steps.toString());
        hintConcept.setText(hint.concept.isEmpty() ? "" : "知识点：" + hint.concept);
        hintPitfall.setText(hint.pitfall.isEmpty() ? "" : "易错点：" + hint.pitfall);
        hintSpeakButton.setEnabled(true);
        hintSpeakButton.setText("朗读提示");
    }

    private void closeHint() {
        if (!hintVisible) {
            return;
        }
        hintVisible = false;
        SpeechRouter.stop();
        if (hintCallback != null) {
            HintClient.cancel(hintCallback);
            hintCallback = null;
        }
        hintPanel.setVisibility(View.GONE);
        exitButton.setVisibility(View.VISIBLE);
        if (hintFromReview) {
            // 从复盘页打开的提示：关掉后回到复盘页，而不是漏掉正确答案直接进下一题。
            hintFromReview = false;
            body.setVisibility(View.GONE);
            resultPanel.setVisibility(View.VISIBLE);
            resultHint.requestFocus();
        } else {
            body.setVisibility(View.VISIBLE);
            requestOptionFocus();
        }
        resetWatchdog();
    }

    private void speakHint() {
        Hint hint = currentHint;
        if (hint == null) {
            hintSource.setText("提示还没准备好，等文字出来再朗读。");
            return;
        }
        String text = hint.speechText();
        if (text.isEmpty()) {
            hintSource.setText("没有可朗读的内容，请直接看文字提示。");
            return;
        }
        hintSpeakButton.setEnabled(false);
        hintSpeakButton.setText("合成中…");
        SpeechRouter.speakHint(context, text, new SpeechRouter.Listener() {
            @Override
            public void onDone() {
                if (!hintVisible) {
                    return;
                }
                hintSpeakButton.setText("朗读提示");
                hintSpeakButton.setEnabled(true);
                hintSource.setText("朗读完成（提示只读思路，不读答案）");
                reassertFocus();
            }

            @Override
            public void onError(String message) {
                if (!hintVisible) {
                    return;
                }
                hintSpeakButton.setText("朗读提示");
                hintSpeakButton.setEnabled(true);
                hintSource.setText("语音不可用：" + message + "（可直接看文字）");
                reassertFocus();
            }
        });
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case HintClient.REASON_AUTH:
                return "Key 无效";
            case HintClient.REASON_RATE:
                return "请求太频繁";
            case HintClient.REASON_TIMEOUT:
                return "超时";
            case HintClient.REASON_NO_KEY:
                return "未配置 Key";
            case HintClient.REASON_LEAK:
                return "已拦截可能的答案泄露";
            case HintClient.REASON_PARSE:
                return "返回内容异常";
            default:
                return "网络不可用";
        }
    }

    // ---------------- 生命周期 ----------------

    private void dismissAndPass(long remainMs) {
        if (dismissed) {
            return;
        }
        dismiss();
        callback.onPass(targetPackage, remainMs);
    }

    private void failAndHome() {
        if (dismissed) {
            return;
        }
        dismiss();
        callback.onFail(targetPackage);
    }

    /** 返回键 / 取消：直接回桌面，绝不停在受限应用里。 */
    private void cancelToHome() {
        if (dismissed) {
            return;
        }
        dismiss();
        callback.onFail(targetPackage);
    }

    /** 提示面板打开时，返回键先关面板；其余情况回桌面。 */
    private void handleBack() {
        if (dismissed) {
            return;
        }
        if (hintVisible) {
            closeHint();
            return;
        }
        cancelToHome();
    }

    /** 每次真实按键/触摸都重新计时；看门狗只针对「窗口在、但按键进不来」的卡死场景。 */
    private void resetWatchdog() {
        if (dismissed) {
            return;
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        android.util.Log.d("QuizGate", "dismiss overlay target=" + targetPackage
                + " finished=" + finished + " correct=" + correct);
        handler.removeCallbacksAndMessages(null);
        try {
            windowManager.removeViewImmediate(root);
        } catch (Throwable ignored) {
            // 窗口可能已经因为服务重启被系统移除
        } finally {
            // 语音、提示请求、音频焦点与音量都必须无条件恢复。
            if (hintCallback != null) {
                HintClient.cancel(hintCallback);
                hintCallback = null;
            }
            SpeechRouter.release();
            AudioSilencer.exit();
        }
        if (sActive == this) {
            sActive = null;
        }
    }

    private void addRecord(boolean passed) {
        JSONObject record = new JSONObject();
        try {
            record.put("t", System.currentTimeMillis());
            record.put("pkg", targetPackage);
            record.put("label", targetLabel);
            record.put("correct", correct);
            record.put("total", questions.size());
            record.put("pass", passed);
            record.put("overlay", true);
        } catch (JSONException ignored) {
            // 记录失败不影响放行
        }
        Prefs.get(context).addRecord(record);
    }

    /** 倒计时：不足 1 分钟按秒显示，其余显示 mm:ss（支持 1 分钟这种短时长）。 */
    private static String describeMs(long ms) {
        long totalSeconds = Math.max(0L, (ms + 999L) / 1000L);
        if (totalSeconds < 60L) {
            return totalSeconds + " 秒";
        }
        return String.format(java.util.Locale.US, "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    private final class OptionClickListener implements View.OnClickListener {

        private final int chosen;
        private final Question question;

        OptionClickListener(int chosen, Question question) {
            this.chosen = chosen;
            this.question = question;
        }

        @Override
        public void onClick(View v) {
            answer(chosen, question);
        }
    }

    /** 供服务在 onServiceConnected 之后调用，避免窗口已 attach 但题目未初始化。 */
    public void begin() {
        startQuiz();
    }
}
