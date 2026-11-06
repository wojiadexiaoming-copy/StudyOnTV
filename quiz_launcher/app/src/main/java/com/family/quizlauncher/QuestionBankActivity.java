package com.family.quizlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * 题库管理页：打开时在局域网内启动极简 HTTP 服务，退出即关闭。
 * 页面本身用遥控器可操作，真正的换题在手机浏览器里完成。
 */
public class QuestionBankActivity extends Activity {

    private static final int REFRESH_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private Prefs prefs;
    private QuestionHttpServer server;
    private TextView urlView;
    private TextView stateView;
    private TextView statusView;
    private LinearLayout subjectRows;
    private boolean askedPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bank);
        prefs = Prefs.get(this);
        urlView = (TextView) findViewById(R.id.bank_url);
        stateView = (TextView) findViewById(R.id.server_state);
        statusView = (TextView) findViewById(R.id.bank_status);
        subjectRows = (LinearLayout) findViewById(R.id.bank_subject_rows);

        ((Button) findViewById(R.id.btn_restart)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
                startServer();
                refresh();
            }
        });
        ((Button) findViewById(R.id.btn_rollback)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRollback();
            }
        });
        ((Button) findViewById(R.id.btn_exit)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.hasPin()) {
            startServer();
            refresh();
        } else {
            urlView.setText("请先设置 4 位家长密码");
            stateView.setText("设置密码后才能上传题库");
            statusView.setText(buildBankStatus());
            askPinOnce();
        }
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
        stopServer();
    }

    private void askPinOnce() {
        if (askedPin) {
            return;
        }
        askedPin = true;
        PinDialog.showSet(this, "设置 4 位家长密码", new PinDialog.SetListener() {
            @Override
            public void onPin(String pin) {
                prefs.setPin(pin);
                startServer();
                refresh();
            }

            @Override
            public void onCancel() {
                finish();
            }
        });
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            return;
        }
        server = new QuestionHttpServer(this);
        if (!server.start()) {
            server = null;
            urlView.setText("服务启动失败");
            stateView.setText("端口 8080~8089 都被占用，请重启服务重试");
            return;
        }
        urlView.setText(server.getUrl());
        stateView.setText("服务已启动（端口 " + server.getPort() + "）。退出本页即自动关闭。");
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void refresh() {
        if (server != null && server.isRunning()) {
            urlView.setText(server.getUrl());
        }
        if (statusView != null) {
            statusView.setText(buildBankStatus());
        }
        buildSubjectRows();
    }

    private String buildBankStatus() {
        File external = QuestionImporter.externalFile(this);
        File backup = QuestionImporter.backupFile(this);
        boolean imported = external != null && external.isFile();
        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        String version = readVersion(external);
        StringBuilder sb = new StringBuilder();
        sb.append("当前题库：").append(imported ? "导入题库（外部文件）" : "内置题库（assets）");
        sb.append("\n学科数量：").append(bank.subjects.size())
                .append(" 个 · 题目共 ").append(bank.total()).append(" 题");
        if (!version.isEmpty()) {
            sb.append("\n题库版本：").append(version);
        }
        sb.append("\n上一版备份：").append(backup != null && backup.isFile() ? "有，可回退" : "暂无");
        return sb.toString();
    }

    /** v2.9：按学科列出名称、题数、已掌握、已移出。 */
    private void buildSubjectRows() {
        if (subjectRows == null) {
            return;
        }
        subjectRows.removeAllViews();
        QuestionBank.Bank bank = QuestionBank.loadBank(this);
        QuestionStats stats = QuestionStats.get(this);
        if (bank.subjects.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("题库为空");
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setTextSize(16);
            subjectRows.addView(empty);
            return;
        }
        for (QuestionBank.Subject subject : bank.subjects) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 6, 0, 6);

            TextView name = new TextView(this);
            name.setText(subject.name);
            name.setTextSize(18);
            name.setTextColor(getResources().getColor(R.color.text_primary));
            row.addView(name, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView detail = new TextView(this);
            detail.setText(subject.questions.size() + " 题 · 掌握 "
                    + stats.masteredCount(subject.questions) + " · 移出 "
                    + stats.removedCount(subject.questions));
            detail.setTextSize(16);
            detail.setTextColor(getResources().getColor(R.color.text_secondary));
            row.addView(detail);

            subjectRows.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void confirmRollback() {
        new AlertDialog.Builder(this)
                .setTitle("回退上一版题库")
                .setMessage("用 questions.bak 覆盖当前题库，导入时自动备份的上一版会恢复。")
                .setPositiveButton("回退", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        QuestionImporter.Result result = QuestionImporter.rollback(
                                QuestionBankActivity.this);
                        Toast.makeText(QuestionBankActivity.this, result.message,
                                Toast.LENGTH_LONG).show();
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String readVersion(File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try {
            String text = QuestionImporter.readText(file);
            if (text.trim().startsWith("{")) {
                return new JSONObject(text).optString("version", "");
            }
        } catch (Exception ignored) {
            // 版本只是展示信息，读不到就算了
        }
        return "";
    }
}
