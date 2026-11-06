package com.family.quizlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * v2.8：常驻前台服务，让手机端随时能通过局域网 /api/* 控制电视。
 *
 * Android 8 起后台进程容易被回收，只有前台服务（startForeground + 常驻通知）
 * 才能长期存活；系统回收后用 START_STICKY 重建，家长页也会在 onResume 时兜底拉起。
 * 关闭服务时停止 ServerSocket，端口立即释放，通知同时消失。
 */
public class ControlService extends Service {

    private static final String CHANNEL_ID = "quiz_control";
    private static final int NOTIFY_ID = 1001;
    private static final String TEXT_RUNNING = "家长控制服务运行中";
    private static final String TEXT_FAILED = "家长控制服务启动失败：端口 8080~8089 被占用";

    private static volatile QuestionHttpServer sServer;
    private static volatile boolean sRunning;
    private static volatile String sLastError = "";

    public static boolean isRunning() {
        return sRunning;
    }

    /** 服务未运行或端口启动失败时返回 0。 */
    public static int getPort() {
        QuestionHttpServer server = sServer;
        return sRunning && server != null ? server.getPort() : 0;
    }

    public static String getLastError() {
        return sLastError;
    }

    /** 打开开关并拉起前台服务；重复调用无副作用。 */
    public static void start(Context context) {
        Prefs.get(context).setControlEnabled(true);
        Intent intent = new Intent(context, ControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    /** 关闭开关并停掉服务（onDestroy 里释放端口、移除通知）。 */
    public static void stop(Context context) {
        Prefs.get(context).setControlEnabled(false);
        context.stopService(new Intent(context, ControlService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        sLastError = "";
        QuestionHttpServer server = new QuestionHttpServer(this, QuestionHttpServer.MODE_CONTROL);
        sServer = server;
        sRunning = server.start();
        if (!sRunning) {
            sLastError = TEXT_FAILED;
        }
        startForeground(NOTIFY_ID, buildNotification(sRunning ? TEXT_RUNNING : TEXT_FAILED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 进程被系统回收后重建：onCreate 已重新拉起 ServerSocket；这里只补通知与状态。
        if (sServer == null || !sServer.isRunning()) {
            QuestionHttpServer server = new QuestionHttpServer(this, QuestionHttpServer.MODE_CONTROL);
            sServer = server;
            sRunning = server.start();
            sLastError = sRunning ? "" : TEXT_FAILED;
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFY_ID, buildNotification(sRunning ? TEXT_RUNNING : TEXT_FAILED));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (sServer != null) {
            sServer.stop();
            sServer = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "局域网控制", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("手机端控制电视时保持服务常驻，关闭开关即停止");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("答题家长控制")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
