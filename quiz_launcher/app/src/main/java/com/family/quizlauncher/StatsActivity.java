package com.family.quizlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v2.9 题库统计页：
 * - 学科汇总（题数 / 已掌握 / 已移出）
 * - 逐题列表（题干截断 30 字、answered/correct/wrong、是否已掌握/已移出）
 * - 手动移出、恢复、恢复全部、重置统计
 */
public class StatsActivity extends Activity {

    private Prefs prefs;
    private QuestionStats stats;
    private LinearLayout subjectRows;
    private LinearLayout questionRows;
    private TextView subtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        prefs = Prefs.get(this);
        stats = QuestionStats.get(this);
        subjectRows = (LinearLayout) findViewById(R.id.subject_rows);
        questionRows = (LinearLayout) findViewById(R.id.question_rows);
        subtitle = (TextView) findViewById(R.id.stats_subtitle);

        Button exit = (Button) findViewById(R.id.stats_exit);
        setUpButton(exit);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        Button restoreAll = (Button) findViewById(R.id.btn_restore_all);
        setUpButton(restoreAll);
        restoreAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestoreAll();
            }
        });

        Button reset = (Button) findViewById(R.id.btn_reset_stats);
        setUpButton(reset);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });

        // v3.0：统计页也能切换严格模式（PIN 保护）。
        Button strict = (Button) findViewById(R.id.btn_strict_mode);
        setUpButton(strict);
        strict.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleStrictWithPin();
            }
        });

        refresh();
    }

    private void setUpButton(Button button) {
        if (button != null) {
            button.setAllCaps(false);
            button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            button.setMinHeight(0);
            button.setMinimumHeight(0);
        }
    }

    /** v3.0：严格模式切换前先校验 PIN，避免孩子自己关掉拦截。 */
    private void toggleStrictWithPin() {
        if (!prefs.hasPin()) {
            Toast.makeText(this, "还没有设置家长密码，请先在家长设置里设置", Toast.LENGTH_SHORT).show();
            return;
        }
        PinDialog.showVerify(this, "请输入家长密码", prefs.getPin(), new PinDialog.Listener() {
            @Override
            public void onSuccess() {
                prefs.setStrictMode(!prefs.isStrictMode());
                refresh();
                Toast.makeText(StatsActivity.this,
                        prefs.isStrictMode() ? "严格模式已开启" : "严格模式已关闭",
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancel() {
                // 取消则不改动
            }
        });
    }

    private void refresh() {
        subtitle.setText("掌握阈值：答对 " + prefs.getMasteredThreshold()
                + " 次 · 自动移出：" + (prefs.isAutoRemove() ? "已开启" : "关闭")
                + " · 严格模式：" + (prefs.isStrictMode() ? "已开启" : "关闭"));
        buildSubjectRows();
        buildQuestionRows();
    }

    private void buildSubjectRows() {
        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        subjectRows.removeAllViews();
        if (bank.subjects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("题库为空");
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setTextSize(18);
            subjectRows.addView(empty);
            return;
        }
        for (QuestionBank.Subject subject : bank.subjects) {
            int mastered = stats.masteredCount(subject.questions);
            int removed = stats.removedCount(subject.questions);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

            TextView name = new TextView(this);
            name.setText(subject.name);
            name.setTextSize(19);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView detail = new TextView(this);
            detail.setText("共 " + subject.questions.size() + " 题 · 掌握 " + mastered
                    + " · 移出 " + removed);
            detail.setTextSize(17);
            detail.setTextColor(getResources().getColor(mastered > 0 ? R.color.ok : R.color.text_secondary));
            row.addView(detail);

            subjectRows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void buildQuestionRows() {
        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        questionRows.removeAllViews();
        if (bank.total() == 0) {
            TextView empty = new TextView(this);
            empty.setText("题库为空");
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setTextSize(18);
            questionRows.addView(empty);
            return;
        }
        int index = 0;
        for (final QuestionBank.Subject subject : bank.subjects) {
            for (final Question question : subject.questions) {
                index++;
                questionRows.addView(buildQuestionRow(index, subject, question));
            }
        }
    }

    private View buildQuestionRow(int index, final QuestionBank.Subject subject, final Question question) {
        QuestionStats.Entry entry = stats.getEntry(question.id);
        boolean mastered = entry.correct >= prefs.getMasteredThreshold();
        boolean removed = stats.isRemoved(question.id);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.row_bg);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(params);

        TextView tag = new TextView(this);
        tag.setText(subject.name);
        tag.setTextSize(14);
        tag.setTextColor(getResources().getColor(R.color.accent));
        tag.setBackgroundResource(R.drawable.tag_bg);
        tag.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        LinearLayout.LayoutParams tagParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagParams.rightMargin = Ui.dp(this, 12);
        row.addView(tag, tagParams);

        TextView text = new TextView(this);
        text.setText(QuestionStats.clip30(question.text));
        text.setTextSize(17);
        text.setTextColor(getResources().getColor(R.color.text_primary));
        text.setSingleLine(false);
        text.setMaxLines(2);
        text.setEllipsize(null);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView counts = new TextView(this);
        counts.setText("答 " + entry.answered + " · 对 " + entry.correct + " · 错 " + entry.wrong);
        counts.setTextSize(15);
        counts.setTextColor(getResources().getColor(R.color.text_secondary));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.leftMargin = Ui.dp(this, 12);
        row.addView(counts, countParams);

        TextView badge = new TextView(this);
        if (removed) {
            badge.setText("已移出");
            badge.setTextColor(getResources().getColor(R.color.danger));
        } else if (mastered) {
            badge.setText("已掌握");
            badge.setTextColor(getResources().getColor(R.color.ok));
        } else {
            badge.setText("未掌握");
            badge.setTextColor(getResources().getColor(R.color.text_secondary));
        }
        badge.setTextSize(15);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.leftMargin = Ui.dp(this, 12);
        row.addView(badge, badgeParams);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQuestionDialog(question);
            }
        });
        return row;
    }

    private void showQuestionDialog(final Question question) {
        boolean removed = stats.isRemoved(question.id);
        QuestionStats.Entry entry = stats.getEntry(question.id);
        String message = "学科：" + question.subjectName
                + "\n答题 " + entry.answered + " 次 · 答对 " + entry.correct
                + " 次 · 答错 " + entry.wrong + " 次"
                + "\n正确答案：" + question.answerText()
                + "\nid：" + question.id;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(QuestionStats.clip30(question.text))
                .setMessage(message);
        if (removed) {
            builder.setPositiveButton("恢复此题", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    stats.restore(question.id);
                    refresh();
                    Toast.makeText(StatsActivity.this, "已恢复", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            builder.setPositiveButton("移出此题", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    stats.removeQuestion(question);
                    refresh();
                    Toast.makeText(StatsActivity.this, "已移出，可在统计页恢复", Toast.LENGTH_SHORT).show();
                }
            });
        }
        builder.setNegativeButton("取消", null).show();
    }

    private void confirmRestoreAll() {
        int count = stats.removedCount();
        if (count == 0) {
            Toast.makeText(this, "没有已移出的题", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复全部已移出题")
                .setMessage("共 " + count + " 道，恢复后重新进入抽题池。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int restored = stats.restoreAll();
                        refresh();
                        Toast.makeText(StatsActivity.this,
                                "已恢复 " + restored + " 题", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置统计")
                .setMessage("清空所有题的 answered/correct/wrong 计数（已移出的题不会自动恢复）。")
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        stats.resetCounters();
                        refresh();
                        Toast.makeText(StatsActivity.this, "统计已重置", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
