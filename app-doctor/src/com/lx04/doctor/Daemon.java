package com.lx04.doctor;

/**
 * 通过本机 adbd 拉起 root 代理。
 *
 * 设备重启后代理会消失，以前必须从电脑跑 doctor_deploy.sh。
 * 现在 app 自己连 127.0.0.1:5555 走 adb 协议，就能用 root 身份把代理重新拉起来 ——
 * 开机广播也会调这里，所以重启后也不需要电脑。
 */
final class Daemon {

    static final String FILES = "/data/data/com.lx04.doctor/files";
    static final String SUPER = "/data/local/tmp/lx04d_super.sh";
    static final String LOCK = "/data/local/tmp/lx04d.lock";

    /** 代理是否还活着（读 pid 文件并 kill -0） */
    static boolean alive() {
        try {
            String out = AdbShell.run(checkCmd(), 8000);
            return out != null && out.contains("ALIVE");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保代理在跑。返回给界面看的一句话。
     * 失败原因要能区分：连不上 adbd / 脚本不存在 / 启动了但 pid 没出来。
     */
    static String ensure() {
        try {
            String out = AdbShell.run(ensureCmd(), 25000);
            if (out == null) return "本机 adb 无响应";
            if (out.contains("NOSCRIPT")) {
                return "代理脚本不存在，需要通过电脑重新部署";
            }
            if (out.contains("ALIVE")) return "代理已在运行";
            int i = out.indexOf("STARTED:");
            if (i >= 0) {
                String pid = out.substring(i + 8).trim();
                if (pid.matches("\\d+")) return "代理已启动，进程号 " + pid;
                return "代理启动超时，将自动重试";
            }
            return "代理启动失败，" + out.trim();
        } catch (Exception e) {
            String m = e.getMessage();
            return "无法连接本机 adb" + (m == null ? "" : "，" + m);
        }
    }

    private static String checkCmd() {
        return "P=$(cat " + FILES + "/daemon.pid 2>/dev/null); "
                + "if [ -n \"$P\" ] && kill -0 $P 2>/dev/null; then echo ALIVE:$P; else echo DEAD; fi";
    }

    private static String ensureCmd() {
        // 关键：这里绝不能用 pkill -f lx04d_super.sh —— 本命令自身的 cmdline 里就含这个路径，
        // pkill 会把执行它的 shell 一起杀掉（实测退出码 143 / SIGTERM），后面的 setsid 永远跑不到。
        // 改成按 pid 文件杀；并且用 kill -9 -PID 杀**整个进程组**：
        // 守护壳是 setsid 起的进程组组长，只杀它自己会把 while 里那个 worker 变成孤儿（实测堆过 5 个）。
        return "if [ ! -f " + SUPER + " ]; then echo NOSCRIPT; exit 0; fi; "
                + "P=$(cat " + FILES + "/daemon.pid 2>/dev/null); "
                + "if [ -n \"$P\" ] && kill -0 $P 2>/dev/null; then echo ALIVE:$P; exit 0; fi; "
                + "S=$(cat " + LOCK + "/pid 2>/dev/null); "
                + "if [ -n \"$S\" ] && [ \"$S\" -gt 1 ]; then kill -9 -$S 2>/dev/null; fi; "
                + "if [ -n \"$P\" ] && [ \"$P\" -gt 1 ]; then kill -9 $P 2>/dev/null; fi; "
                + "rm -rf " + LOCK + "; sleep 1; "
                + "setsid sh " + SUPER + " >/dev/null 2>&1 </dev/null & "
                + "sleep 4; echo STARTED:$(cat " + FILES + "/daemon.pid 2>/dev/null)";
    }
}
