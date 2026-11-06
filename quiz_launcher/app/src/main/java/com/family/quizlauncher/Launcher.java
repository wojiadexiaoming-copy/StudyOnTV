package com.family.quizlauncher;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class Launcher {

    /** 用 ACTION_MAIN + CATEGORY_LAUNCHER 启动第三方应用，失败时回退到 getLaunchIntentForPackage。 */
    public static boolean launch(Context context, String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent);
            return true;
        } catch (Exception first) {
            try {
                Intent fallback = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (fallback != null) {
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(fallback);
                    return true;
                }
            } catch (Exception ignored) {
                // 交给调用方提示
            }
            Toast.makeText(context, "无法打开该应用，可能已被卸载", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /**
     * 回桌面。优先用 HOME 意图（由系统解析当前桌面）；失败再显式启动本机原桌面。
     * 注意：本应用绝不注册 CATEGORY_HOME，这里只是「请求」系统回到已有桌面。
     */
    public static void goHome(Context context) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(home);
            return;
        } catch (Throwable ignored) {
            // 继续尝试本机原桌面组件
        }
        try {
            Intent explicit = new Intent();
            explicit.setComponent(new android.content.ComponentName(
                    "com.ktcp.launcher", "com.ktcp.launcher.activity.LauncherHomeActivity"));
            explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(explicit);
        } catch (Throwable ignored) {
            // 两种方式都失败时只能留在原地
        }
    }
}
