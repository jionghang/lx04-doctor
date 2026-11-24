package com.lx04.doctor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机把 root 代理拉起来，这样设备重启后也不需要电脑。
 *
 * 注意这里不能直接跑网络操作（BroadcastReceiver 有 10 秒超时），
 * 所以丢到一个临时线程里做，失败了也不影响开机流程。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(8000);   // 等系统起稳、adbd 就绪
                    Daemon.ensure();
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }
}
