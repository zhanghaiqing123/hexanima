package com.hexanima.npu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** 只负责让进程保持前台服务的优先级：模型下载 / 生图 / 本地 8081 接口常驻。
 *
 *  类型用 specialUse（Android 14+）而不是 dataSync：dataSync 从 Android 15 起有「每天 6 小时」的
 *  配额，而这里想常驻的是接口。旧系统自动回退 dataSync（manifest 里两种都声明了）。
 *  注意：前台服务能提高「被杀」的优先级，但挡不住 ColorOS 那种把进程 SIGSTOP 的冻结。 */
public class DownloadService extends Service {
    private static DownloadService live;
    private static String mode = "dl";
    private static String label;
    private static int percent = -1;

    /** 生图 / 下载 / 接口常驻都用同一个服务，靠 mode 区分通知文案 */
    static void start(Context c, String m) {
        try {
            c.startForegroundService(new Intent(c, DownloadService.class).putExtra("mode", m));
        } catch (Throwable ignored) { }
    }

    static boolean isRunning() { return live != null; }

    /** 更新常驻通知（服务没在跑就什么都不做）。percent < 0 表示不显示进度条 */
    static void update(String m, String text, int pct) {
        DownloadService s = live;
        if (s == null) return;
        if (m != null) mode = m;
        label = text;
        percent = pct;
        s.show();
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        L.init(this);
        live = this;
        if (i != null && i.getStringExtra("mode") != null) mode = i.getStringExtra("mode");
        label = null;
        percent = -1;
        show();
        return START_NOT_STICKY;
    }

    private void show() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            boolean gen = "gen".equals(mode), api = "api".equals(mode);
            String ch = gen ? "gen" : api ? "api" : "dl";
            nm.createNotificationChannel(new NotificationChannel(ch,
                    gen ? L.s("Генерация", "Generation") : api ? L.s("Локальный интерфейс", "Local API") : L.s("Загрузка моделей", "Model downloads"),
                    NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = new Notification.Builder(this, ch)
                    .setSmallIcon(gen ? android.R.drawable.ic_menu_gallery
                            : api ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download)
                    .setContentTitle("HexAnima")
                    .setContentText(label != null ? label : gen ? L.s("Генерирую картинку…", "Generating a picture…")
                            : api ? L.s("Локальный интерфейс 8081 активен", "Local API on port 8081 is running")
                            : L.s("Скачиваю файлы модели…", "Downloading model files…"))
                    .setOngoing(true);
            if (percent >= 0) b.setProgress(100, Math.max(0, Math.min(100, percent)), false);
            startForeground(1, b.build(), type());
        } catch (Throwable ignored) { }
    }

    /** 仍是 dataSync：aapt2 2.19（arm64 上唯一能用的版本）不认识 specialUse / mediaProcessing 这两个
     *  新 flags，manifest 里写不进去。好在 App 的 targetSdk 是 34，dataSync 的「每天 6 小时」限制
     *  （Android 15 对 targetSdk 35 才生效）暂时管不到我们。 */
    private int type() {
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        if (live == this) live = null;
        super.onDestroy();
    }
}
