#!/system/bin/sh
# LX04 设备医生 —— 代理守护壳
#
# 1) 代理进程崩了就 5 秒后重建
# 2) 单实例：以前每部署一次就多一个守护壳，堆到 6 个互相抢着写 status.json。
#    这里用 mkdir 做原子锁（Android 8.1 的 toybox 没有 flock）。
#
# 启动方式：setsid sh /data/local/tmp/lx04d_super.sh
# 停止方式：见 doctor_deploy.sh --stop（按 pid 文件杀，别用 pkill -f，会连自己的 shell 一起杀）

LOCK=/data/local/tmp/lx04d.lock
WORKER=/data/local/tmp/lx04d.sh

got=0
i=0
while [ $i -lt 4 ]; do
    if mkdir "$LOCK" 2>/dev/null; then
        echo $$ > "$LOCK/pid"
        got=1
        break
    fi
    # 抢不到锁：看看锁里那个 pid 还活着吗
    OLD=$(cat "$LOCK/pid" 2>/dev/null)
    if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
        exit 0                      # 已有实例在跑，我安静退出
    fi
    rm -rf "$LOCK" 2>/dev/null      # 僵尸锁（上次被 kill -9）
    i=$((i + 1))
    sleep 1
done

[ "$got" = "1" ] || exit 0
trap 'rm -rf "$LOCK"' EXIT

while true; do
    sh $WORKER
    sleep 5
done
