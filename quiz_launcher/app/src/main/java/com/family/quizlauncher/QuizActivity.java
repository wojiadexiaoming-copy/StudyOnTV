package com.family.quizlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 答题关卡（无障碍遮罩不可用时的降级页，也用于家长页的「练习答题」）。
 * v2.9：与遮罩共用深色卡片视觉；选项按字符数自适应、绝不裁切。
 */
public class QuizActivity extends Activity {

    public static final String EXTRA_PACKAGE = "extra_package";
    public static final String EXTRA_LABEL = "extra_label";
    public static final String EXTRA_PRACTICE = "extra_practice";

    private static final int QUESTION_COUNT = 5;
    private static final int COUNTDOWN_SECONDS = 10;
    private static final int WRONG_REVIEW_SECONDS = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private String targetPackage;
    private String targetLabel;
    private boolean practice;

    private LinearLayout body;
    private LinearLayout resultPanel;
    private TextView headerView;
    private TextView progressView;
    private TextView metaView;
    private View progressFill;
    private View progressRest;
    private TextView questionView;
    private ScrollView questionScroll;
    private LinearLayout optionsView;
    private TextView feedbackView;
    private TextView resultTitle;
    private TextView resultDetail;
    private TextView resultReview;
    private Button primaryButton;
    private Button secondaryButton;
    private Button exitButton;

    private List<Question> questions = new ArrayList<Question>();
    /** 答错记录：{题号, 正确选项下标, 用户选择下标}。 */
    private final List<int[]> wrongAnswers = new ArrayList<int[]>();
    private int index = 0;
    private int correct = 0;
    private int required = 4;
    private boolean locked = false;
    private boolean launched = false;
    private Runnable countdown;
    private Runnable wrongCountdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz);

        prefs = Prefs.get(this);
        practice = getIntent().getBooleanExtra(EXTRA_PRACTICE, false);
        targetPackage = getIntent().getStringExtra(EXTRA_PACKAGE);
        targetLabel = getIntent().getStringExtra(EXTRA_LABEL);
        if (practice) {
            targetPackage = "";
            targetLabel = "练习";
        } else {
            if (TextUtils.isEmpty(targetLabel)) {
                targetLabel = targetPackage;
            }
            if (TextUtils.isEmpty(targetPackage)) {
                finish();
                return;
            }
        }

        body = (LinearLayout) findViewById(R.id.quiz_body);
        resultPanel = (LinearLayout) findViewById(R.id.quiz_result);
        headerView = (TextView) findViewById(R.id.quiz_header);
        progressView = (TextView) findViewById(R.id.quiz_progress);
        metaView = (TextView) findViewById(R.id.quiz_meta);
        progressFill = findViewById(R.id.quiz_progress_fill);
        progressRest = findViewById(R.id.quiz_progress_rest);
        questionView = (TextView) findViewById(R.id.quiz_question);
        questionScroll = (ScrollView) findViewById(R.id.quiz_question_scroll);
        optionsView = (LinearLayout) findViewById(R.id.quiz_options);
        feedbackView = (TextView) findViewById(R.id.quiz_feedback);
        resultTitle = (TextView) findViewById(R.id.result_title);
        resultDetail = (TextView) findViewById(R.id.result_detail);
        resultReview = (TextView) findViewById(R.id.result_review);
        primaryButton = (Button) findViewById(R.id.btn_primary);
        secondaryButton = (Button) findViewById(R.id.btn_secondary);
        exitButton = (Button) findViewById(R.id.quiz_exit);

        setUpButton(exitButton);
        setUpButton(primaryButton);
        setUpButton(secondaryButton);
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        required = prefs.getRequired();
        startQuiz();
    }

    private void setUpButton(Button button) {
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handler.removeCallbacksAndMessages(null);
        if (!practice) {
            // 被拦截时返回键只能回桌面，不能把受限应用留在前台。
            goHomeAndFinish();
            return;
        }
        super.onBackPressed();
    }

    private void goHomeAndFinish() {
        if (countdown != null) {
            handler.removeCallbacks(countdown);
        }
        Launcher.goHome(this);
        finish();
    }

    private void startQuiz() {
        List<Question> picked = QuestionBank.pick(this, QUESTION_COUNT);
        if (picked.isEmpty()) {
            Toast.makeText(this, "题库为空，无法开始答题", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        questions = new ArrayList<Question>(picked);

        index = 0;
        correct = 0;
        wrongAnswers.clear();
        locked = false;
        launched = false;
        required = Math.min(prefs.getRequired(), questions.size());
        if (countdown != null) {
            handler.removeCallbacks(countdown);
        }
        if (wrongCountdown != null) {
            handler.removeCallbacks(wrongCountdown);
        }

        body.setVisibility(View.VISIBLE);
        resultPanel.setVisibility(View.GONE);
        renderQuestion();
    }

    private void renderQuestion() {
        Question question = questions.get(index);
        headerView.setText(practice
                ? "练习答题（不影响放行）"
                : "给《" + targetLabel + "》解锁");
        progressView.setText("第 " + (index + 1) + " / " + questions.size() + " 题");
        updateProgressBar();
        metaView.setText("已对 " + correct + " · 需对 " + required);
        if (question.caseSensitive) {
            metaView.append(" · 区分大小写");
        }
        // 严格按题库原文渲染，不做任何大小写转换。
        questionView.setText(question.text);
        questionView.setTextSize(adaptiveQuestionSp(question.text));
        ViewGroup.LayoutParams questionParams = questionScroll.getLayoutParams();
        boolean longQuestion = question.text != null && question.text.length() > 70;
        questionParams.height = longQuestion
                ? (int) (getResources().getDisplayMetrics().heightPixels * 0.24f)
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        questionScroll.setLayoutParams(questionParams);
        questionScroll.scrollTo(0, 0);
        feedbackView.setText("");
        feedbackView.setVisibility(View.GONE);
        optionsView.removeAllViews();
        buildOptions(question);
        locked = false;
    }

    private void updateProgressBar() {
        int total = Math.max(1, questions.size());
        float ratio = Math.max(0.03f, Math.min(1f, (float) index / total));
        LinearLayout.LayoutParams fill = (LinearLayout.LayoutParams) progressFill.getLayoutParams();
        LinearLayout.LayoutParams rest = (LinearLayout.LayoutParams) progressRest.getLayoutParams();
        fill.weight = ratio;
        rest.weight = Math.max(0.001f, 1f - ratio);
        progressFill.setLayoutParams(fill);
        progressRest.setLayoutParams(rest);
    }

    /** 题干按长度自适应字号，配合 ScrollView 保证 200 字以内不压到选项。 */
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

    /** 选项卡片：与遮罩同规则——按字符数决定列数与字号，高度随内容，绝不省略。 */
    private void buildOptions(Question question) {
        int count = question.options.length;
        if (count <= 0) {
            return;
        }
        int maxWeight = 0;
        for (String option : question.options) {
            maxWeight = Math.max(maxWeight, displayWeight(option));
        }
        int columns = optionColumns(count, maxWeight);
        float textSp = optionTextSp(maxWeight);
        int rows = (count + columns - 1) / columns;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                rowParams.topMargin = Ui.dp(this, 10);
            }
            optionsView.addView(row, rowParams);

            for (int c = 0; c < columns; c++) {
                int optionIndex = r * columns + c;
                if (optionIndex >= count) {
                    View spacer = new View(this);
                    LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (c > 0) {
                        spacerParams.leftMargin = Ui.dp(this, 14);
                    }
                    row.addView(spacer, spacerParams);
                    continue;
                }
                LinearLayout card = buildOptionCard(question, optionIndex, textSp);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) {
                    params.leftMargin = Ui.dp(this, 14);
                }
                row.addView(card, params);
                if (optionIndex == 0) {
                    card.requestFocus();
                }
            }
        }
    }

    private LinearLayout buildOptionCard(Question question, int optionIndex, float textSp) {
        String text = question.options[optionIndex];
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.option_card);
        card.setFocusable(true);
        card.setClickable(true);
        card.setMinimumHeight(Ui.dp(this, 56));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));

        final View bar = new View(this);
        bar.setBackgroundResource(R.drawable.option_bar);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 5), ViewGroup.LayoutParams.MATCH_PARENT);
        barParams.rightMargin = Ui.dp(this, 14);
        card.addView(bar, barParams);

        TextView letter = new TextView(this);
        letter.setText(String.valueOf((char) ('A' + Math.min(25, optionIndex))));
        letter.setTextSize(19f);
        letter.setTextColor(getResources().getColor(R.color.accent));
        letter.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        letter.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(letter, new LinearLayout.LayoutParams(
                Ui.dp(this, 34), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView optionText = new TextView(this);
        optionText.setText(text);
        optionText.setTextSize(textSp);
        optionText.setTextColor(getResources().getColor(R.color.text_primary));
        optionText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        // 允许换行，且绝不使用省略号截断。
        optionText.setSingleLine(false);
        optionText.setMaxLines(Integer.MAX_VALUE);
        optionText.setEllipsize(null);
        optionText.setLineSpacing(Ui.dp(this, 3), 1f);
        optionText.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(optionText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                bar.setBackgroundResource(hasFocus
                        ? R.drawable.option_bar_focus : R.drawable.option_bar);
                v.animate().scaleX(hasFocus ? 1.02f : 1f).scaleY(hasFocus ? 1.02f : 1f)
                        .setDuration(120).start();
            }
        });
        card.setOnClickListener(new OptionClickListener(optionIndex, question));
        return card;
    }

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

    private static int optionColumns(int count, int maxWeight) {
        int columns = maxWeight <= 8 ? 3 : (maxWeight <= 14 ? 2 : 1);
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

    private void answer(int chosen, Question question) {
        if (locked) {
            return;
        }
        locked = true;
        boolean ok = chosen == question.answer;
        // v2.9：每题统计（answered/correct/wrong），必要时按阈值自动移出。
        QuestionStats.get(this).record(question, ok);
        setOptionsEnabled(false);

        if (ok) {
            correct++;
            showFeedback(true, "✓  答对了！");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    advance();
                }
            }, 600);
        } else {
            wrongAnswers.add(new int[]{index, question.answer, chosen});
            String correctText = question.answerText();
            String chosenText = (chosen >= 0 && chosen < question.options.length)
                    ? question.options[chosen] : "?";
            // 答错后至少停留 8 秒，并在界面上显示倒计时。
            final Runnable tick = new Runnable() {
                private int left = WRONG_REVIEW_SECONDS;

                @Override
                public void run() {
                    if (isFinishing() || left <= 0) {
                        advance();
                        return;
                    }
                    showFeedback(false, "✕  第 " + (index + 1) + " 题答错了，正确答案是「"
                            + correctText + "」 · " + left + " 秒后继续");
                    left--;
                    handler.postDelayed(this, 1000);
                }
            };
            wrongCountdown = tick;
            handler.post(tick);
        }
    }

    private void showFeedback(boolean ok, String text) {
        feedbackView.setText(text);
        feedbackView.setTextColor(getResources().getColor(ok ? R.color.ok : R.color.danger));
        feedbackView.setBackgroundResource(ok ? R.drawable.card_ok : R.drawable.card_danger);
        feedbackView.setVisibility(View.VISIBLE);
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
        index++;
        if (index >= questions.size()) {
            showResult();
        } else {
            renderQuestion();
        }
    }

    private void showResult() {
        boolean passed = correct >= required;
        addRecord(passed);

        body.setVisibility(View.GONE);
        resultPanel.setVisibility(View.VISIBLE);

        if (practice) {
            resultTitle.setText("练习：答对 " + correct + "/" + questions.size() + " 题");
            resultTitle.setTextColor(getResources().getColor(passed ? R.color.ok : R.color.danger));
            resultDetail.setText(passed
                    ? "练习完成。练习不会写入放行时间。"
                    : "正式答题需要答对 " + required + " 题才能解锁。");
            resultReview.setText(wrongReviewText());
            primaryButton.setText("再答一次");
            secondaryButton.setText("结束练习");
            primaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startQuiz();
                }
            });
            secondaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            primaryButton.requestFocus();
            return;
        }

        if (passed) {
            prefs.grantPass(targetPackage);
            resultTitle.setText("答对 " + correct + "/" + questions.size() + "，通过！");
            resultTitle.setTextColor(getResources().getColor(R.color.ok));
            resultDetail.setText("已放行《" + targetLabel + "》" + prefs.getMinutes()
                    + " 分钟时间。\n时间到了以后，需要重新答题。");
            resultReview.setText(wrongReviewText());
            primaryButton.setText("立即观看");
            secondaryButton.setText("回桌面");
            primaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchTarget();
                }
            });
            secondaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goHomeAndFinish();
                }
            });
            primaryButton.requestFocus();
            startCountdown();
        } else {
            resultTitle.setText("答对 " + correct + "/" + questions.size() + "，没有通过");
            resultTitle.setTextColor(getResources().getColor(R.color.danger));
            resultDetail.setText("需要答对 " + required + " 题才能解锁。\n按「回桌面」返回，稍后再试。");
            resultReview.setText(wrongReviewText());
            primaryButton.setText("再答一次");
            secondaryButton.setText("回桌面");
            primaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startQuiz();
                }
            });
            secondaryButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    goHomeAndFinish();
                }
            });
            primaryButton.requestFocus();
        }
    }

    private void startCountdown() {
        if (countdown != null) {
            handler.removeCallbacks(countdown);
        }
        countdown = new Runnable() {
            private int left = COUNTDOWN_SECONDS;

            @Override
            public void run() {
                if (isFinishing() || launched) {
                    return;
                }
                if (left <= 0) {
                    launchTarget();
                    return;
                }
                primaryButton.setText("立即观看（" + left + " 秒）");
                left--;
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(countdown);
    }

    private void launchTarget() {
        if (launched) {
            return;
        }
        launched = true;
        if (countdown != null) {
            handler.removeCallbacks(countdown);
        }
        Launcher.launch(this, targetPackage);
        finish();
    }

    /** 结果页附上答错题的题号、你选的、正确答案。 */
    private String wrongReviewText() {
        if (wrongAnswers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("答错的题：");
        for (int[] wrong : wrongAnswers) {
            Question question = questions.get(wrong[0]);
            String chosenText = (wrong[2] >= 0 && wrong[2] < question.options.length)
                    ? question.options[wrong[2]] : "?";
            sb.append("\n第 ").append(wrong[0] + 1).append(" 题：正确答案「")
                    .append(question.answerText()).append("」，你选了「")
                    .append(chosenText).append("」");
        }
        return sb.toString();
    }

    private void addRecord(boolean passed) {
        if (practice) {
            return;
        }
        JSONObject record = new JSONObject();
        try {
            record.put("t", System.currentTimeMillis());
            record.put("pkg", targetPackage);
            record.put("label", targetLabel);
            record.put("correct", correct);
            record.put("total", questions.size());
            record.put("pass", passed);
        } catch (JSONException ignored) {
            // 记录失败不影响放行
        }
        prefs.addRecord(record);
    }

    private class OptionClickListener implements View.OnClickListener {

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
}
