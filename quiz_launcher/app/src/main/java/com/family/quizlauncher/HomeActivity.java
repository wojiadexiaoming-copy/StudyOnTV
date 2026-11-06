package com.family.quizlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeActivity extends Activity implements View.OnClickListener {

    private static final int TICK_MS = 10000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private LinearLayout grid;
    private TextView clockView;
    private TextView passView;
    private TextView subtitleView;
    private String gridSignature = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.get(this).initRestricted(AppRepository.defaultRestricted(this));

        setContentView(R.layout.activity_home);
        grid = (LinearLayout) findViewById(R.id.grid);
        clockView = (TextView) findViewById(R.id.clock);
        passView = (TextView) findViewById(R.id.pass_state);
        subtitleView = (TextView) findViewById(R.id.subtitle);
        findViewById(R.id.btn_settings).setOnClickListener(this);
        findViewById(R.id.btn_records).setOnClickListener(this);

        // 启动期不阻塞：待导入检查放到首帧之后，异常也不影响界面显示
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                try {
                    checkPendingImport();
                } catch (Throwable ignored) {
                    // 导入提示失败不应影响桌面使用
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuildIfNeeded();
        updateStatus();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    public void onBackPressed() {
        // 桌面不允许返回退出，避免黑屏
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openParentSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_settings) {
            openParentSettings();
            return;
        }
        if (id == R.id.btn_records) {
            startActivity(new Intent(this, RecordsActivity.class));
            return;
        }
        Object tag = v.getTag();
        if (tag instanceof AppEntry) {
            openApp((AppEntry) tag);
        }
    }

    private void openApp(AppEntry entry) {
        Prefs prefs = Prefs.get(this);
        if (prefs.isRestricted(entry.packageName) && !prefs.isPassActive()) {
            Intent intent = new Intent(this, QuizActivity.class);
            intent.putExtra(QuizActivity.EXTRA_PACKAGE, entry.packageName);
            intent.putExtra(QuizActivity.EXTRA_LABEL, entry.label);
            startActivity(intent);
            return;
        }
        Launcher.launch(this, entry.packageName);
    }

    private void openParentSettings() {
        // 密码校验在家长设置页内部完成：首次进入要求设置，之后要求校验。
        startActivity(new Intent(this, ParentActivity.class));
    }

    /**
     * 备用导入路径：U 盘根目录或 adb push 到应用外部目录的 questions_import.json。
     * 只在桌面启动时检查一次，处理完文件会被改名，不会重复提示。
     */
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
                        QuestionImporter.Result result = QuestionImporter.importFile(HomeActivity.this, pending);
                        Toast.makeText(HomeActivity.this, result.message, Toast.LENGTH_LONG).show();
                        gridSignature = "";
                        rebuildIfNeeded();
                    }
                })
                .setNegativeButton("忽略", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        QuestionImporter.markIgnored(HomeActivity.this, pending);
                    }
                })
                .show();
    }

    private void updateStatus() {
        clockView.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));

        Prefs prefs = Prefs.get(this);
        subtitleView.setText(getString(R.string.home_subtitle, prefs.getRequired(), prefs.getMinutes()));

        long remain = prefs.getRemainMs();
        if (remain > 0) {
            long totalSeconds = remain / 1000L;
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            String text = minutes > 0 ? (minutes + " 分钟") : (seconds + " 秒");
            passView.setText(getString(R.string.pass_ok, text));
            passView.setTextColor(getResources().getColor(R.color.accent));
        } else {
            passView.setText(R.string.pass_none);
            passView.setTextColor(getResources().getColor(R.color.text_secondary));
        }
    }

    private void rebuildIfNeeded() {
        List<AppEntry> apps = AppRepository.load(this);
        StringBuilder signature = new StringBuilder();
        for (AppEntry entry : apps) {
            signature.append(entry.packageName).append(entry.restricted ? '1' : '0').append('|');
        }
        String current = signature.toString();
        if (!current.equals(gridSignature)) {
            gridSignature = current;
            buildGrid(apps);
        }
    }

    private void buildGrid(List<AppEntry> apps) {
        grid.removeAllViews();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有找到可打开的应用");
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setTextSize(20);
            empty.setPadding(Ui.dp(this, 8), Ui.dp(this, 32), 0, 0);
            grid.addView(empty);
            return;
        }

        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) {
            widthDp = getResources().getDisplayMetrics().widthPixels
                    / Math.max(1, (int) getResources().getDisplayMetrics().density);
        }
        int columns = Math.max(4, Math.min(7, widthDp / 240));
        int rows = (apps.size() + columns - 1) / columns;

        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = Ui.dp(this, 12);
            grid.addView(row, rowParams);

            for (int colIndex = 0; colIndex < columns; colIndex++) {
                int appIndex = rowIndex * columns + colIndex;
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                        0, Ui.dp(this, 132), 1f);
                if (colIndex > 0) {
                    tileParams.leftMargin = Ui.dp(this, 12);
                }
                if (appIndex < apps.size()) {
                    row.addView(makeTile(apps.get(appIndex)), tileParams);
                } else {
                    row.addView(new View(this), tileParams);
                }
            }
        }
    }

    private View makeTile(AppEntry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setBackgroundResource(R.drawable.tile_bg);
        tile.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 6));
        tile.setTag(entry);
        tile.setOnClickListener(this);

        int iconSize = Ui.dp(this, 62);
        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        icon.setFocusable(false);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (entry.icon != null) {
            icon.setImageDrawable(entry.icon);
        } else {
            icon.setImageResource(R.drawable.ic_launcher);
        }
        tile.addView(icon);

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextSize(17);
        label.setTextColor(getResources().getColor(R.color.text_primary));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setFocusable(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = Ui.dp(this, 5);
        tile.addView(label, labelParams);

        if (entry.restricted) {
            TextView badge = new TextView(this);
            badge.setText("需答题");
            badge.setTextSize(12);
            badge.setTextColor(getResources().getColor(R.color.danger));
            badge.setGravity(Gravity.CENTER);
            badge.setFocusable(false);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.topMargin = Ui.dp(this, 2);
            tile.addView(badge, badgeParams);
        } else {
            TextView spacer = new TextView(this);
            spacer.setTextSize(12);
            spacer.setText(" ");
            spacer.setFocusable(false);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            spacerParams.topMargin = Ui.dp(this, 2);
            tile.addView(spacer, spacerParams);
        }
        return tile;
    }
}
