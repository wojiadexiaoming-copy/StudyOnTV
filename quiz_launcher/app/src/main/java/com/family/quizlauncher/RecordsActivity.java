package com.family.quizlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_records);

        TextView textView = (TextView) findViewById(R.id.records_text);
        Button back = (Button) findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        back.requestFocus();

        JSONArray records = Prefs.get(this).getRecords();
        if (records.length() == 0) {
            textView.setText("还没有答题记录。\n\n孩子第一次打开受限应用后，这里会出现每次答题的结果。");
            return;
        }

        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) {
                continue;
            }
            long time = record.optLong("t", 0L);
            String label = record.optString("label", record.optString("pkg", "未知应用"));
            int correct = record.optInt("correct", 0);
            int total = record.optInt("total", 5);
            boolean passed = record.optBoolean("pass", false);

            sb.append(format.format(new Date(time)))
                    .append("  ")
                    .append(label)
                    .append("  ")
                    .append(passed ? "通过" : "未通过")
                    .append("（")
                    .append(correct).append("/").append(total)
                    .append("）")
                    .append("\n\n");
        }
        textView.setText(sb.toString().trim());
    }
}
