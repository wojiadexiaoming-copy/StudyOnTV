package com.family.quizlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 第二道保险：开机完成后清空所有放行记录。
 *
 * 主逻辑仍写在 {@link Prefs#isPassActive(String)} 里（开机计数 + elapsedRealtime 基线），
 * 因为部分定制固件会拦截第三方应用的自启动广播，不能只依赖这个接收器。
 *
 * v3.2：如果家长页里的控制服务开关是打开状态，开机后自动把 {@link ControlService} 拉起来，
 * 避免重启后手机网页控制台不可用。ControlService.onCreate 会立即 startForeground，
 * 满足 Android 8 上 startForegroundService 必须 5 秒内转前台的约束。
 */
public class BootCleanupReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            Prefs prefs = Prefs.get(context);
            prefs.clearAllPasses();
            if (prefs.isControlEnabled()) {
                Intent service = new Intent(context, ControlService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            }
        } catch (Throwable ignored) {
            // 失败只影响本次自启，不影响开机广播的其他逻辑
        }
    }
}
