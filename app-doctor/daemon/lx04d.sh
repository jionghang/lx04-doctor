#!/system/bin/sh
# ---------------------------------------------------------------
# LX04 设备医生 —— 设备端 root 代理
#
# 由 doctor_deploy.sh 以 root 启动（setsid + 守护壳），做两件事：
#   1. 循环读 APK 写的命令文件并执行，结果写回 out.txt
#   2. 定期写 status.json / samples.csv 给 APK 显示
#
# 开销原则：4 核满载的机器上，代理自己不能成为负担。
#   - 每轮只 fork 一次 ps，其余靠缓存
#   - netstat/df 每 10 轮一次，dumpsys 每 60 轮一次
#   - status.json 原地覆盖写（inode 不变，SELinux 标签不会回退）
#
# 调试：sh /data/local/tmp/lx04d.sh health   （带参数只执行一次）
# 停止：bash scripts/doctor_deploy.sh --stop
#       千万别用 pkill -f lx04d_super.sh —— 那条命令自身的 cmdline 里含该路径，
#       pkill 会连执行它的 shell 一起杀掉（退出码 143），后面的清理永远跑不到。
# ---------------------------------------------------------------

FILES=/data/data/com.lx04.doctor/files
LOG=$FILES/daemon.log
CMD=$FILES/cmd

mkdir -p "$FILES" 2>/dev/null

APP_UID=$(stat -c %u "$FILES" 2>/dev/null)
[ -z "$APP_UID" ] && APP_UID=10123
APP_GID=$(stat -c %g "$FILES" 2>/dev/null)
[ -z "$APP_GID" ] && APP_GID=$APP_UID

# root 建的文件标签是 s0，app 在 s0:c512,c768 下读不了 —— 必须 restorecon
fix_owner() {
    chown "$APP_UID:$APP_GID" "$1" 2>/dev/null
    chmod 600 "$1" 2>/dev/null
    restorecon "$1" >/dev/null 2>&1
}

log() {
    echo "$(date +%H:%M:%S) $*" >> $LOG
    cnt=$(wc -l < $LOG 2>/dev/null || echo 0)
    if [ "${cnt:-0}" -gt 200 ]; then
        tail -c 6000 $LOG > $LOG.tmp 2>/dev/null && mv $LOG.tmp $LOG
    fi
    fix_owner $LOG
}

# 累计 CPU ticks（utime+stime，单位 10ms）
# 注意：mksh 里 $14 等于 $1 接 "4"，必须写 ${14} ${15}
ticks_of() {
    [ -z "$1" ] && { echo 0; return; }
    set -- $(cat /proc/$1/stat 2>/dev/null)
    if [ -n "${14:-}" ] && [ -n "${15:-}" ]; then
        echo $(( ${14} + ${15} ))
    else
        echo 0
    fi
}

age_sec_of() {
    [ -z "$1" ] && { echo 0; return; }
    set -- $(cat /proc/$1/stat 2>/dev/null)
    st=${22:-0}
    [ "$st" -eq 0 ] && { echo 0; return; }
    up=$(cut -d. -f1 /proc/uptime)
    echo $(( up - st / 100 ))
}

PS_CACHE=""
refresh_ps() { PS_CACHE=$(ps -A -o PID,NAME 2>/dev/null); }

# 从缓存的 ps 结果里取 pid。grep -F 避免 "." 被当正则
pid_of() {
    echo "$PS_CACHE" | grep -F "$1" | head -1 | tr -s ' ' | sed 's/^ //' | cut -d' ' -f1
}

bt_pid() { pid_of "com.android.bluetooth"; }
vpm_pid() { pid_of "MiVpmService"; }
# 旧的写死版本（保留示例，已不再用于判定）：只认这两个包名
# yst_count() { echo "$PS_CACHE" | grep -cE "xiaodianshi|hpplay"; }

# ---------------------------------------------------------------
# 投屏类识别（不写死包名，换成别的投屏也能认出来）
#
# 教训：一开始只认 xiaodianshi|hpplay 两个包名，换 AirScreen / AirCast /
# 系统 Miracast 就一个都清不掉。只认端口也不行 —— 实测乐播现在监听的是
# 7100/49152/52266 而不是文档里写的 9958，端口是随机变的。
# 所以主判定用"名称特征词"，端口只作为展示用的旁证。
# ---------------------------------------------------------------

# 特征词（忽略大小写）。刻意避开会被系统进程误伤的词：
#   不用单独的 cast —— com.android.cellbroadcastreceiver 会中招
#   不用 castreceiver —— 它是 cellbroadCASTREVEIVER 的子串
CAST_RE='screencast|screenmirror|screen_mirror|wifidisplay|miracast|airplay|airscreen|airmirror|aircast|leancast|hpplay|xiaodianshi|lebo|tvcast|castproxy|chromecast|mirrorcast|projection_screen|smartview|multiscreen|wfdservice|wfd_'

# 常见投屏端口（仅用于在报告里说明"它在监听什么"，不参与判定）
CAST_PORTS='9958 7100 7000 7001 8008 8009 8443 7236 7237'

# 只数进程个数，给 status.json 用（每轮调用，绝不能 fork pm）
cast_count() { echo "$PS_CACHE" | grep -icE "$CAST_RE"; }

# 进程名 → 包名（去掉 ":remote" 之类子进程后缀）
pkg_of_proc() { echo "${1%%:*}"; }

# 该 pid 正在监听哪些端口（展示用）
# 注意：这台设备的 toybox 没编 awk（`awk: not found`），所以只能用
# grep + tr + cut + sed 拼，别图省事用 awk。
cast_ports_of() {
    [ -z "$1" ] && return 0
    netstat -ltnp 2>/dev/null | grep -F "$1/" | tr -s ' ' | sed 's/^ //' \
        | cut -d' ' -f4 | sed 's/.*://' | sort -u | tr '\n' ' '
}

# 该进程是否正被局域网设备连着（ESTABLISHED 且对端是私网）
# >0 说明投屏可能正在进行，此时不该杀
#
# 两个坑：
#   1. 必须按"监听端口"找连接，不能按 pid 找 —— 同一台机器上自连时，
#      netstat 会把 ESTABLISHED 那条记在客户端进程名下（实测记成 toybox），
#      按 pid 匹配就漏了；按端口匹配两种情况都能抓到。
#   2. 记得只算私网对端。投屏 app 常年挂着几条到云端的连接（1.14.x.x 之类），
#      算进去就会永远认为"正在投屏"，永远清不掉。
cast_lan_peers() {
    [ -z "$1" ] && { echo 0; return; }
    ports=$(cast_ports_of "$1")
    [ -z "$ports" ] && { echo 0; return; }
    ns=$(netstat -tn 2>/dev/null | grep ESTABLISHED | tr -s ' ' | sed 's/^ //' | cut -d' ' -f4,5)
    n=0
    for p in $ports; do
        c=$(echo "$ns" | grep ":$p " | cut -d' ' -f2 \
            | grep -cE '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)')
        n=$((n + ${c:-0}))
    done
    echo "$n"
}

# 扫一遍，把候选写到 $CAST_TMP，每行：pid|进程名|包名|归属|监听端口|局域网连接数
# 归属：third=第三方 system=系统自带 unknown=进程名不是包名，认不出
cast_scan() {
    CAST_TMP=/data/local/tmp/.cast.$$
    refresh_ps
    THIRD=$(pm list packages -3 2>/dev/null | sed 's/^package://' | tr -d '\r')
    SYS=$(pm list packages -s 2>/dev/null | sed 's/^package://' | tr -d '\r')

    echo "$PS_CACHE" | grep -iE "$CAST_RE" | tr -s ' ' | sed 's/^ //' > "$CAST_TMP.raw"
    : > "$CAST_TMP"
    while read -r pid name; do
        [ -z "$pid" ] && continue
        pkg=$(pkg_of_proc "$name")
        if echo "$THIRD" | grep -Fxq "$pkg"; then
            kind=third
        elif echo "$SYS" | grep -Fxq "$pkg"; then
            kind=system
        else
            kind=unknown
        fi
        echo "$pid|$name|$pkg|$kind|$(cast_ports_of "$pid")|$(cast_lan_peers "$pid")" >> "$CAST_TMP"
    done < "$CAST_TMP.raw"
    rm -f "$CAST_TMP.raw"
}

load1() { read l r < /proc/loadavg; echo $l; }
mem_avail() { grep MemAvailable /proc/meminfo | tr -dc '0-9\n'; }
swap_used() {
    t=$(grep SwapTotal /proc/meminfo | tr -dc '0-9\n')
    f=$(grep SwapFree /proc/meminfo | tr -dc '0-9\n')
    echo $(( (t - f) / 1024 ))
}

CACHE_DF=0
CACHE_PORT=0
CACHE_FRONT=""
FRONT_ACTIVITY() {
    dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | sed 's/.*u0 //; s/ .*//'
}

PREV_UP=0
PREV_BT=0
PREV_VPM=0
BT_PCT=0
VPM_PCT=0
LOOP=0

write_status() {
    LOOP=$((LOOP + 1))
    up=$(cut -d. -f1 /proc/uptime)
    refresh_ps
    b=$(bt_pid)
    v=$(vpm_pid)
    btk=$(ticks_of "$b")
    vtk=$(ticks_of "$v")

    if [ "$PREV_UP" -gt 0 ]; then
        du=$(( up - PREV_UP ))
        if [ $du -ge 5 ]; then
            BT_PCT=$(( (btk - PREV_BT) / du ))
            VPM_PCT=$(( (vtk - PREV_VPM) / du ))
            [ $BT_PCT -lt 0 ] && BT_PCT=0
            [ $VPM_PCT -lt 0 ] && VPM_PCT=0
            PREV_UP=$up; PREV_BT=$btk; PREV_VPM=$vtk
        fi
    else
        PREV_UP=$up; PREV_BT=$btk; PREV_VPM=$vtk
    fi

    # 贵命令降频
    if [ $((LOOP % 10)) -eq 1 ]; then
        CACHE_DF=$(df /data 2>/dev/null | tail -1 | tr -s ' ' | cut -d' ' -f4)
        CACHE_PORT=$(netstat -ltn 2>/dev/null | grep -c ":9958")
    fi
    if [ $((LOOP % 60)) -eq 1 ]; then
        CACHE_FRONT=$(FRONT_ACTIVITY)
    fi

    bt_age_min=$(( $(age_sec_of "$b") / 60 ))

    # 原地覆盖写：inode 不变，SELinux 标签保持 restorecon 后的状态
    printf '%s' "{\"uptime\":$up,\"bt_pid\":\"$b\",\"bt_ticks\":$btk,\"bt_pct\":$BT_PCT,\"bt_age_min\":$bt_age_min,\"vpm_pid\":\"$v\",\"vpm_ticks\":$vtk,\"vpm_pct\":$VPM_PCT,\"load1\":$(load1),\"mem_avail\":$(mem_avail),\"swap_used\":$(swap_used),\"data_free\":${CACHE_DF:-0},\"yst\":$(cast_count),\"port9958\":${CACHE_PORT:-0},\"front\":\"$CACHE_FRONT\",\"ts\":$(date +%s)}" > $FILES/status.json
    fix_owner $FILES/status.json
}

append_sample() {
    up=$(cut -d. -f1 /proc/uptime)
    refresh_ps
    b=$(bt_pid)
    echo "$(date +%s),$up,$b,$(ticks_of $b),$(ticks_of $(vpm_pid)),$(load1),$(mem_avail),$(swap_used)" >> $FILES/samples.csv
    cnt=$(wc -l < $FILES/samples.csv 2>/dev/null || echo 0)
    if [ "${cnt:-0}" -gt 2000 ]; then
        tail -1000 $FILES/samples.csv > $FILES/.s.tmp 2>/dev/null && mv $FILES/.s.tmp $FILES/samples.csv
    fi
    fix_owner $FILES/samples.csv
}

# ---------- 动作 ----------
do_fix_bt() {
    refresh_ps
    old=$(bt_pid)
    oldtk=$(ticks_of $old)
    oldage=$(dumpsys bluetooth_manager 2>/dev/null | grep -m1 "time since enabled" | sed 's/.*: //')
    echo "蓝牙进程 $old，累计 $((${oldtk:-0} / 100)) 秒 CPU，已运行 ${oldage:-未知}"
    am force-stop com.android.bluetooth
    echo "已终止蓝牙进程，等待系统重建"
    new=""
    i=0
    while [ $i -lt 15 ]; do
        sleep 1
        refresh_ps
        new=$(bt_pid)
        if [ -n "$new" ] && [ "$new" != "$old" ]; then break; fi
        i=$((i+1))
    done
    if [ -z "$new" ] || [ "$new" = "$old" ]; then
        echo "进程未自动重建，尝试重新开启蓝牙"
        settings put global bluetooth_on 0
        sleep 2
        settings put global bluetooth_on 1
        sleep 6
        refresh_ps
        new=$(bt_pid)
    fi
    if [ -n "$new" ] && [ "$new" != "$old" ]; then
        newtk=$(ticks_of $new)
        echo "蓝牙已重启，新进程 $new。累计 CPU 从 $((${oldtk:-0} / 100)) 秒归零，现在 $((${newtk:-0} / 100)) 秒"
        echo "蓝牙状态：$(dumpsys bluetooth_manager 2>/dev/null | grep -m1 'state:' | sed 's/.*state: *//' | sed 's/^ON$/已开启/; s/^OFF$/已关闭/; s/^TURNING_ON$/正在开启/; s/^TURNING_OFF$/正在关闭/')"
        log "fix_bt ok $old->$new"
        return 0
    else
        echo "蓝牙进程未恢复，可能需要重启设备"
        log "fix_bt FAILED"
        return 1
    fi
}

do_fix_yst() {
    cast_scan
    total=$(wc -l < "$CAST_TMP" | tr -d ' ')
    if [ "${total:-0}" -eq 0 ]; then
        echo "没有发现投屏类进程。可识别的是乐播、小电视、AirScreen、AirPlay、AirMirror、Miracast、WiDi"
        rm -f "$CAST_TMP"
        return 0
    fi

    front=$(FRONT_ACTIVITY)
    frontpkg=${front%%/*}
    echo "发现 $total 个投屏类进程，前台是 ${front:-无}"

    killed=0
    skipped=0
    done_pkgs=" "
    # 用重定向而不是管道：管道会让 while 跑在子 shell 里，killed/skipped 统计会丢
    while IFS='|' read -r pid name pkg kind ports peers; do
        [ -z "$pid" ] && continue
        case "$kind" in
            third)  note="第三方" ;;
            system) note="系统自带" ;;
            *)      note="认不出包名" ;;
        esac
        [ -n "$ports" ] && note="$note，监听 ${ports% }"
        [ "${peers:-0}" -gt 0 ] && note="$note，局域网连接 ${peers} 条"

        # 同一包的多进程（:remote 之类）只做一次决定。
        # 注意：跳过的包也要记进 done_pkgs —— 否则主进程被保护、子进程被清，
        # 而 am force-stop 是按包执行的，等于绕过保护把整个 app 杀了（实测踩过）。
        case "$done_pkgs" in
            *" $pkg "*) echo "  同包已处理：$name"; continue ;;
        esac

        # 保护 1：正停在这个 app 的界面上
        if [ -n "$frontpkg" ] && [ "$pkg" = "$frontpkg" ]; then
            echo "  跳过 $name，进程 $pid，正在前台使用，$note"
            skipped=$((skipped + 1))
            done_pkgs="$done_pkgs$pkg "
            continue
        fi
        # 保护 2：正被局域网设备连着，说明投屏进行中
        if [ "${peers:-0}" -gt 0 ]; then
            echo "  跳过 $name，进程 $pid，有局域网连接，可能正在投屏，$note"
            skipped=$((skipped + 1))
            done_pkgs="$done_pkgs$pkg "
            continue
        fi
        # 保护 3：系统自带的不动（Miracast/WiDi 之类的系统功能），认不出包名的也不动
        if [ "$kind" != "third" ]; then
            echo "  系统组件不处理 $name，进程 $pid，$note"
            skipped=$((skipped + 1))
            done_pkgs="$done_pkgs$pkg "
            continue
        fi

        echo "  清理 $name，进程 $pid，$note"
        am force-stop "$pkg"
        done_pkgs="$done_pkgs$pkg "
        killed=$((killed + 1))
    done < "$CAST_TMP"
    rm -f "$CAST_TMP"

    sleep 2
    refresh_ps
    left=$(cast_count)
    echo "结果：清理 $killed 个，跳过 $skipped 个，仍剩投屏类进程 $left 个"
    if [ "$killed" -eq 0 ]; then
        echo "没有需要清理的进程，均在使用中或属于系统组件"
    fi
    log "fix_yst killed=$killed skipped=$skipped left=$left"
}

do_clean_tmp() {
    before=$(du -sm /data/local/tmp 2>/dev/null | tr -dc '0-9\n')
    echo "清理前 /data/local/tmp 共 ${before:-?} MB"
    rm -f /data/local/tmp/*.apk
    after=$(du -sm /data/local/tmp 2>/dev/null | tr -dc '0-9\n')
    echo "临时目录中的安装包已删除，从 ${before:-?}MB 降到 ${after:-?}MB"
    log "clean_tmp ${before}->${after}"
}

# ---------------------------------------------------------------
# 空间清理
#
# 原则：只清"删了会自动重建"的东西；不可逆的东西只报告不动。
# 特别记住：/data/local/tmp 里住着代理自己（lx04d.sh / lx04d_super.sh / lx04d.lock），
# 清理时绝不能碰，否则一键清理会把医生自己删掉。
# ---------------------------------------------------------------

free_mb() {
    k=$(df /data 2>/dev/null | tail -1 | tr -s ' ' | sed 's/^ //' | cut -d' ' -f4)
    echo $(( ${k:-0} / 1024 ))
}

size_mb() {
    s=$(du -sk "$1" 2>/dev/null | cut -f1)
    echo $(( ${s:-0} / 1024 ))
}

# 多个目录（glob 展开）合计 MB
glob_mb() {
    t=0
    for d in $1; do
        [ -e "$d" ] || continue
        s=$(du -sk "$d" 2>/dev/null | cut -f1)
        t=$(( t + ${s:-0} ))
    done
    echo $(( t / 1024 ))
}

# 只删目录里的内容，保留目录本身。
# 删掉目录再重建会让它的 SELinux 标签变成 root 默认标签，app 之后可能写不进自己的 cache
# —— 这个坑在 status.json 上踩过一次（app 读不到 → 界面一直显示代理离线）。
wipe_dir() {
    [ -d "$1" ] || return 0
    rm -rf "$1"/* "$1"/.[!.]* 2>/dev/null
}

clean_space_core() {
    SPACE_FREED=0
    SPACE_DEL=0
    b0=$(free_mb)

    m=$(glob_mb "/data/data/*/cache")
    for d in /data/data/*/cache; do wipe_dir "$d"; done
    SPACE_DEL=$((SPACE_DEL + m))
    echo "应用私有缓存 ${m}MB"

    m=$(glob_mb "/data/media/0/Android/data/*/cache")
    for d in /data/media/0/Android/data/*/cache; do wipe_dir "$d"; done
    SPACE_DEL=$((SPACE_DEL + m))
    echo "外部缓存 ${m}MB"

    m=$(glob_mb "/data/anr /data/tombstones /data/system/dropbox /data/log /data/misc/logd /data/media/0/ims_log")
    for d in /data/anr /data/tombstones /data/system/dropbox /data/log /data/misc/logd /data/media/0/ims_log; do
        wipe_dir "$d"
    done
    SPACE_DEL=$((SPACE_DEL + m))
    echo "崩溃日志和系统日志 ${m}MB"

    m=$(glob_mb "/data/media/0/DCIM/.thumbnails")
    wipe_dir /data/media/0/DCIM/.thumbnails
    SPACE_DEL=$((SPACE_DEL + m))
    echo "相册缩略图 ${m}MB，会自动重建"

    # /data/local/tmp 残留。
    # 保护清单只留代理自己那三个文件 —— 早先按 "lx04d*" 前缀保护，
    # 结果部署留下的 lx04d.b64（23KB）永远清不掉。
    m=$(size_mb /data/local/tmp)
    for f in /data/local/tmp/* /data/local/tmp/.[!.]*; do
        [ -e "$f" ] || continue
        case "$f" in
            */lx04d.sh|*/lx04d_super.sh|*/lx04d.lock|*/.) continue ;;
        esac
        case "$f" in
            *.apk|*.zip|*.log|*.bmp|*.png|*.jpg|*.tmp|*.b64|*.cast.*) rm -rf "$f" 2>/dev/null ;;
        esac
    done
    m2=$(size_mb /data/local/tmp)
    SPACE_DEL=$((SPACE_DEL + m - m2))
    echo "临时目录从 ${m}MB 降到 ${m2}MB"

    b1=$(free_mb)
    SPACE_FREED=$(( b1 - b0 ))
    if [ "${SPACE_FREED:-0}" -gt 0 ]; then echo "共清理约 ${SPACE_DEL}MB，可用空间从 ${b0}MB 变成 ${b1}MB，实际增加 ${SPACE_FREED}MB"; else echo "共清理约 ${SPACE_DEL}MB，可用空间仍为 ${b1}MB，空间被系统立即占用，属于正常现象"; fi
    echo "统计差额属于正常现象，系统会立即重建部分日志与缓存，磁盘统计存在延迟"
}

do_space() {
    echo "=== 空间清理 ==="
    clean_space_core
    write_status      # 动作耗时几秒，顺手刷一次状态，免得界面以为代理挂了
    echo ""
    echo "以下不自动清理，仅报告："
    echo "  dalvik-cache $(size_mb /data/dalvik-cache)MB —— 删除后所有应用都需要重新编译，不建议清理"
    echo "  已安装包 /data/app $(size_mb /data/app)MB"
    echo "  用户空间中占用最多的几项："
    du -sm /data/media/0/* 2>/dev/null | sort -rn | head -5 | while read -r s p; do
        echo "    ${s}MB  $p"
    done
    echo ""
    if [ "${SPACE_DEL:-0}" -gt 0 ]; then
        echo "清理结束，共处理 ${SPACE_DEL}MB，可用空间 ${b1}MB"
    else
        echo "没有可清理的缓存，可用空间 ${b1}MB"
    fi
    log "space freed ${SPACE_FREED}MB"
}

# ---------------------------------------------------------------
# 一键优化：按当前状态决定做什么，每次都要说清"为什么做 / 为什么跳过"
# ---------------------------------------------------------------
do_optimize() {
    echo "=== 一键优化 ==="
    opt_done=0
    opt_skip=0
    fb=$(free_mb)

    # 1) 投屏残留（do_fix_yst 自带三道保护：前台 / 局域网连接 / 系统组件）
    refresh_ps
    n=$(cast_count)
    if [ "$n" -gt 0 ]; then
        echo "第 1 步，投屏进程 $n 个，开始清理"
        do_fix_yst | sed 's/^/      /'
        opt_done=$((opt_done + 1))
    else
        echo "第 1 步，没有投屏进程，跳过"
        opt_skip=$((opt_skip + 1))
    fi
    write_status

    # 2) 蓝牙（阈值 10% 单核，与界面红色阈值一致）
    if [ "${BT_PCT:-0}" -ge 10 ]; then
        echo "第 2 步，蓝牙占用 ${BT_PCT}%，超过 10%，重启蓝牙"
        do_fix_bt | sed 's/^/      /'
        opt_done=$((opt_done + 1))
    else
        echo "第 2 步，蓝牙占用 ${BT_PCT}%，未达到 10%，跳过"
        opt_skip=$((opt_skip + 1))
    fi
    write_status

    # 3) 空间：可用 < 200MB，或缓存 ≥ 40MB，或临时目录 ≥ 20MB
    fb2=$(free_mb)
    cache=$(glob_mb "/data/data/*/cache /data/media/0/Android/data/*/cache")
    tmp=$(size_mb /data/local/tmp)
    if [ "$fb2" -lt 200 ] || [ "$cache" -ge 40 ] || [ "$tmp" -ge 20 ]; then
        echo "第 3 步，可用 ${fb2}MB，缓存 ${cache}MB，临时 ${tmp}MB，开始清理"
        clean_space_core | sed 's/^/      /'
        opt_done=$((opt_done + 1))
    else
        echo "第 3 步，可用 ${fb2}MB，缓存 ${cache}MB，临时 ${tmp}MB，空间充足，跳过"
        opt_skip=$((opt_skip + 1))
    fi
    write_status

    # 4) 内存：swap ≥ 300MB 才动，且只释放页缓存（不杀后台，免得把正在放的音乐干掉）
    sw=$(swap_used)
    ma=$(mem_avail)
    ld=$(load1)
    if [ "$sw" -ge 300 ]; then
        echo "第 4 步，虚拟内存 ${sw}MB，超过 300MB，释放页缓存"
        b_ma=$ma
        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
        sleep 1
        echo "      可用内存从 $((b_ma / 1024))MB 变成 $(( $(mem_avail) / 1024 ))MB。虚拟内存不一定下降，本次释放的是文件缓存"
        opt_done=$((opt_done + 1))
    else
        echo "第 4 步，虚拟内存 ${sw}MB，可用内存 $((ma / 1024))MB，负载 $ld，跳过"
        opt_skip=$((opt_skip + 1))
    fi

    # 5) 语音引擎：只提示，绝不自动停（用户选择保留小爱同学唤醒）
    if [ "${VPM_PCT:-0}" -ge 60 ]; then
        echo "第 5 步，语音引擎占用 ${VPM_PCT}% 单核，按当前设置保持不变"
        echo "      如需释放算力，请手动暂停语音引擎，代价是无法唤醒"
    else
        echo "第 5 步，语音引擎 ${VPM_PCT}%，正常"
    fi

    echo ""
    if [ "${SPACE_DEL:-0}" -gt 0 ]; then
        fb2=$(free_mb)
        if [ "$fb2" -gt "$fb" ]; then echo "/data 可用空间从 ${fb}MB 变成 ${fb2}MB"; else echo "/data 可用空间仍为 ${fb2}MB，未释放出空间"; fi
        echo ""
    fi
    # 这一行必须留在最后：界面结果栏取的是输出的末行
    if [ "$opt_done" -eq 0 ]; then
        echo "结果：${opt_skip} 项均未达到阈值，未做改动"
    else
        echo "结果：执行 ${opt_done} 项，跳过 ${opt_skip} 项"
    fi
    log "optimize done=$opt_done skip=$opt_skip"
}

do_health() {
    refresh_ps
    b=$(bt_pid); v=$(vpm_pid)
    echo "运行 $(($(cut -d. -f1 /proc/uptime) / 3600)) 小时　负载 $(load1)　可用内存 $(($(mem_avail) / 1024))MB"
    echo "/data 剩余 $(( $(df /data 2>/dev/null | tail -1 | tr -s ' ' | cut -d' ' -f4) / 1024 ))MB　虚拟内存已用 $(swap_used)MB"
    echo "蓝牙 pid ${b:-无} 已运行 $(($(age_sec_of $b) / 60)) 分钟，累计 $(($(ticks_of $b) / 100)) 秒 CPU"
    echo "语音引擎 pid ${v:-无}，累计 $(($(ticks_of $v) / 100)) 秒 CPU"
    echo "投屏类进程 $(cast_count) 个　$(echo "$PS_CACHE" | grep -iE "$CAST_RE" | tr -s ' ' | cut -d' ' -f2- | tr '\n' ' ')"
    echo "前台 $(FRONT_ACTIVITY)"
}

run_action() {
    case "$1" in
        health)    do_health ;;
        fix_bt)    do_fix_bt ;;
        fix_yst)   do_fix_yst ;;
        space)     do_space ;;
        optimize)  do_optimize ;;
        clean_tmp) do_clean_tmp ;;
        vpm_stop)  setprop ctl.stop mivpm; sleep 2; echo "语音唤醒已暂停，期间无法通过小爱同学唤醒设备" ;;
        vpm_start) setprop ctl.start mivpm; sleep 3; refresh_ps; echo "语音唤醒已恢复，进程号 $(vpm_pid)" ;;
        sample)    append_sample; echo "已记录一条采样" ;;
        *)         echo "未知动作：$1" ;;
    esac
}

# ---------- 入口 ----------
echo "$$" > $FILES/daemon.pid
fix_owner $FILES/daemon.pid

# 调试：带参数执行一次就退出
# 采样两次（间隔 3 秒）才能真正算出 CPU 百分比 —— 第一次只能记基准值
if [ -n "$1" ]; then
    refresh_ps
    write_status
    sleep 3
    refresh_ps
    write_status
    run_action "$1"
    exit 0
fi

# 首次把状态/采样文件建出来并打上正确标签，之后原地覆盖写就一直是好标签
[ -f $FILES/status.json ] || : > $FILES/status.json
fix_owner $FILES/status.json
[ -f $FILES/samples.csv ] || : > $FILES/samples.csv
fix_owner $FILES/samples.csv

log "daemon start pid=$$ app_uid=$APP_UID"

tick=0
while true; do
    if [ -f "$CMD" ]; then
        # 命令格式 action@unix秒。带时间戳是为了防重放：
        # 代理重启（部署、崩溃拉起、开机）时如果 cmd 文件还留着上一条命令，
        # 新实例会把它当新命令执行一遍。实测语音引擎曾在部署后被莫名其妙停掉，
        # 停掉小爱同学唤醒这种事不能靠"应该不会发生"。
        raw=$(cat "$CMD" 2>/dev/null)
        rm -f "$CMD"
        act=${raw%@*}
        cts=${raw##*@}
        stale=0
        case "$cts" in
            ''|*[!0-9]*) ;;                       # 老格式没有时间戳，按新命令处理
            *) now=$(date +%s); [ $((now - cts)) -gt 90 ] && stale=1 ;;
        esac
        out=$FILES/.out.tmp
        if [ "$stale" = "1" ]; then
            echo "=== $(date +%H:%M:%S) 丢弃过期命令 $act ===" > $out
            echo "命令写入已超过 90 秒，可能是代理重启前残留的，已丢弃" >> $out
            log "discard stale $act age=$((now - cts))s"
        else
            echo "=== $(date +%H:%M:%S) 执行 $act ===" > $out
            run_action "$act" >> $out 2>&1
        fi
        mv $out $FILES/out.txt
        fix_owner $FILES/out.txt
    fi

    write_status

    tick=$((tick + 1))
    if [ $((tick % 40)) -eq 0 ]; then   # 约每 2 分钟一条采样
        append_sample
    fi

    sleep 3
done
