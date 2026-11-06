package com.family.quizlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.family.quizlauncher.ai.ApiKeys;

/**
 * 电视端「AI 解析设置（手机填写）」入口。
 *
 * 页面可见期间在本机局域网提供 /ai 填 Key 页（Keystore 加密保存）；
 * 退出页面立即停止服务。Key 不回显完整值，只显示前后各 4 位掩码。
 */
public class AiSettingsActivity extends Activity {

    /** 到 onCreate 才创建：Activity 构造时 getApplicationContext() 还是空的。 */
    private QuestionHttpServer server;

    private TextView urlView;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_settings);
        server = new QuestionHttpServer(this, true);
        urlView = (TextView) findViewById(R.id.ai_url);
        statusView = (TextView) findViewById(R.id.ai_page_status);

        Button clear = (Button) findViewById(R.id.ai_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        ((Button) findViewById(R.id.ai_close)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        server.start();
        urlView.setText(server.getUrl() + "/ai");
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        server.stop();
    }

    private void updateStatus() {
        Prefs prefs = Prefs.get(this);
        String llm = ApiKeys.getLlmKey(this);
        String tts = ApiKeys.getTtsKey(this);
        StringBuilder text = new StringBuilder();
        text.append("Agnes 解析：").append(ApiKeys.isLlmBuiltin(this) ? "已内置（" + ApiKeys.mask(llm) + "）"
                : (llm == null || llm.isEmpty() ? "未配置" : "已配置（" + ApiKeys.mask(llm) + "）"));
        text.append("\nMiMo 语音：").append(ApiKeys.isTtsBuiltin(this) ? "已内置（" + ApiKeys.mask(tts) + "）"
                : (tts == null || tts.isEmpty() ? "未配置" : "已配置（" + ApiKeys.mask(tts) + "）"));
        text.append("\n家长密码：").append(prefs.hasPin()
                ? "已设置（网页保存需要输入）" : "未设置（请先回「家长设置」设置 4 位密码）");
        String lastLlm = prefs.getAiTest("llm");
        String lastTts = prefs.getAiTest("tts");
        text.append("\nAgnes 最近测试：").append(lastLlm.isEmpty() ? "尚未测试" : lastLlm);
        text.append("\nMiMo 最近测试：").append(lastTts.isEmpty() ? "尚未测试" : lastTts);
        statusView.setText(text.toString());
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清除 AI Key")
                .setMessage("只清除本机填写的 Key，随后回落到随 APK 内置的默认 Key；"
                        + "内置 Key 不可被清除。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ApiKeys.clearAll(AiSettingsActivity.this);
                        Prefs.get(AiSettingsActivity.this).setAiTest("llm", "");
                        Prefs.get(AiSettingsActivity.this).setAiTest("tts", "");
                        updateStatus();
                        Toast.makeText(AiSettingsActivity.this, "已清除填写的 Key，回落到内置默认",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
