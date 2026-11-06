package com.family.quizlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 遥控器可操作的数字密码框：3×4 键盘，方向键移动、确定键输入。
 * 支持两种模式：校验已有 4 位密码、首次设置 4 位密码。
 */
public class PinDialog {

    public interface Listener {
        void onSuccess();

        void onCancel();
    }

    public interface SetListener {
        void onPin(String pin);

        void onCancel();
    }

    private static final int PIN_LENGTH = 4;

    public static void showVerify(final Activity activity, String title, final String correctPin,
                                  final Listener listener) {
        showCore(activity, title, true, correctPin, listener, null);
    }

    public static void showSet(final Activity activity, String title, final SetListener listener) {
        showCore(activity, title, false, null, null, listener);
    }

    private static void showCore(final Activity activity, String title, final boolean verifyMode,
                                 final String correctPin, final Listener listener,
                                 final SetListener setListener) {
        final Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_pin);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);

        final TextView titleView = (TextView) dialog.findViewById(R.id.pin_title);
        final TextView display = (TextView) dialog.findViewById(R.id.pin_display);
        titleView.setText(title);
        display.setText("----");

        final StringBuilder input = new StringBuilder();
        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清除", "0", "确定"};
        LinearLayout keysLayout = (LinearLayout) dialog.findViewById(R.id.pin_keys);
        int keyWidth = Ui.dp(activity, 96);
        int keyHeight = Ui.dp(activity, 56);
        int gap = Ui.dp(activity, 5);

        for (int row = 0; row < 4; row++) {
            LinearLayout rowLayout = new LinearLayout(activity);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                final String key = keys[row * 3 + col];
                Button button = new Button(activity);
                button.setText(key);
                button.setTextSize(20);
                button.setTextColor(activity.getResources().getColor(R.color.text_primary));
                button.setBackgroundResource(R.drawable.button_bg);
                button.setFocusable(true);
                button.setPadding(0, 0, 0, 0);
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setMinHeight(0);
                button.setMinimumHeight(0);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(keyWidth, keyHeight);
                lp.setMargins(gap, gap, gap, gap);
                rowLayout.addView(button, lp);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        handleKey(activity, dialog, display, input, key, verifyMode, correctPin,
                                listener, setListener);
                    }
                });
            }
            keysLayout.addView(rowLayout);
        }

        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (verifyMode && listener != null) {
                    listener.onCancel();
                } else if (setListener != null) {
                    setListener.onCancel();
                }
            }
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(Ui.dp(activity, 460), ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        if (keysLayout.getChildCount() > 0) {
            LinearLayout firstRow = (LinearLayout) keysLayout.getChildAt(0);
            if (firstRow.getChildCount() > 0) {
                firstRow.getChildAt(0).requestFocus();
            }
        }
    }

    private static void handleKey(Activity activity, Dialog dialog, TextView display,
                                  StringBuilder input, String key, boolean verifyMode,
                                  String correctPin, Listener listener, SetListener setListener) {
        if ("清除".equals(key)) {
            input.setLength(0);
            display.setText("----");
            return;
        }
        if ("确定".equals(key)) {
            String code = input.toString();
            if (verifyMode) {
                if (code.equals(correctPin)) {
                    dialog.dismiss();
                    if (listener != null) {
                        listener.onSuccess();
                    }
                } else {
                    input.setLength(0);
                    display.setText(R.string.pin_wrong);
                }
            } else {
                if (code.length() == PIN_LENGTH) {
                    dialog.dismiss();
                    if (setListener != null) {
                        setListener.onPin(code);
                    }
                } else {
                    input.setLength(0);
                    display.setText("请输入 4 位数字");
                }
            }
            return;
        }
        if (input.length() >= PIN_LENGTH) {
            return;
        }
        input.append(key);
        StringBuilder masked = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            masked.append('*');
        }
        display.setText(masked.toString());
    }
}
