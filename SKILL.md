---
name: lx04-doctor
description: 在小爱触屏音箱 LX04 上安装优化助手。当用户提到小爱触屏音箱、小爱音箱、LX04 的安装优化、设备变慢、蓝牙断连、蓝牙占用高、存储空间不足、投屏残留，或者需要查看该设备的运行状态时使用。本技能负责连接设备、写入持久调试端口、安装应用并验证后台代理是否上线。
---

# 小爱触屏音箱优化助手安装技能

本技能指导智能体为一台小爱触屏音箱 LX04 部署优化助手，整个过程需要在能够访问设备所在局域网的电脑上执行，需要 adb 命令行工具。

## 开始之前

向用户确认三件事，缺少任何一项都无法继续。

第一，设备的局域网地址。形如 192.168.1.100，不要使用主机名。

第二，设备已经开启网络调试。如果没有开启，本技能无法完成安装，需要用户先自行处理。

第三，电脑已经安装 adb 并且与设备在同一网段。

## 第一步，检查 adb 可用性

```bash
adb version
```

命令失败说明电脑没有安装 adb，需先提示用户安装，不要尝试自行下载。

## 第二步，连接设备

```bash
adb connect <设备地址>:5555
adb devices
```

在设备列表中确认目标地址后面是 device 而不是 offline 或者 unauthorized。状态为 offline 时先执行 `adb disconnect <设备地址>:5555` 再重新连接一次。连续三次仍然失败，停止并告知用户检查网络连通性。

连接成功后核对设备型号，避免装错设备。

```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

型号应当包含 LX04 或者 mi_lx04，系统版本应当是 8.1。型号不符时不要继续安装，应用在该设备之外未经验证。

## 第三步，写入持久调试端口

这一步保证设备重启之后仍然能通过网络调试连接，同时也是应用在开机时自行拉起后台代理的前提。

```bash
adb shell setprop persist.adb.tcp.port 5555
adb shell getprop persist.adb.tcp.port
```

第二条命令应当返回 5555。返回空值说明属性写入失败，通常是权限不足，此时不要继续，因为即使装上应用也无法在重启后自恢复。

## 第四步，安装应用

应用包位于本仓库的 apk 目录。如果本地没有仓库副本，先下载到临时目录再安装。

```bash
adb install -r apk/lx04-doctor.apk
```

输出 Success 表示安装完成。出现 INSTALL_FAILED_UPDATE_INCOMPATIBLE 时说明设备上已有相同包名但签名不同的版本，需要向用户确认是否卸载旧版后再装。

## 第五步，启动并等待代理上线

```bash
adb shell am start -n com.lx04.doctor/.MainActivity
```

启动之后等待大约八秒，然后读取状态文件确认后台代理已经工作。

```bash
adb shell cat /data/data/com.lx04.doctor/files/status.json
```

返回内容应当是一行 JSON，包含 uptime、bt_pct、mem_avail、data_free 等字段。文件不存在或者内容超过二十五秒没有更新，说明代理没有起来，按下面的故障处理逐项排查。

同时可以确认进程状态。

```bash
adb shell ps -A | grep lx04d
```

正常情况下应当看到一个守护进程和一个工作进程。

## 第六步，向用户交付

安装完成后告知用户以下内容。

应用已经装好，在音箱屏幕上可以找到优化助手图标。日常使用不需要电脑，设备重启后应用会自动恢复后台代理。

建议每周查看一次状态页，蓝牙占用超过百分之十时执行蓝牙重启，磁盘剩余低于一百五十 MB 时执行空间清理。

蓝牙重启会中断蓝牙连接约十二秒，配对信息不会丢失。

## 故障处理

**状态显示代理离线。** 先确认第四步的属性已经写入，然后手动触发一次启动并观察输出。

```bash
adb shell am start -n com.lx04.doctor/.MainActivity
adb shell cat /data/data/com.lx04.doctor/files/daemon.log
```

日志末尾会记录启动失败的原因。常见的两种情况是调试端口未就绪和后台脚本丢失。

**后台脚本丢失。** 设备的数据分区被清理过，或者安装过新版本系统，会导致后台脚本不存在。此时需要从仓库的 app-doctor/daemon 目录重新推送两个脚本到设备，并赋予执行权限。

```bash
adb push app-doctor/daemon/lx04d.sh /data/local/tmp/lx04d.sh
adb push app-doctor/daemon/lx04d_super.sh /data/local/tmp/lx04d_super.sh
adb shell chmod 755 /data/local/tmp/lx04d.sh /data/local/tmp/lx04d_super.sh
```

推送完成后重新打开应用，它会自动拉起后台代理。

**连接频繁断开。** 设备负载过高时调试服务可能不稳定，表现为 command not found 或者 connection reset。等待十秒后重试一次即可，不要在此时反复执行界面抓取类命令，那会进一步加重设备负担。

## 边界与注意事项

本技能只做安装和连接检查，不执行任何清理动作。清理、重启蓝牙、暂停语音引擎等操作必须由用户在设备屏幕上自行确认执行，因为每一项都有明确的副作用，需要使用者知情。

不要修改设备系统分区，不要替换系统组件，不要卸载预装应用。这些操作超出本技能范围，且存在让设备无法启动的风险。

不要在设备正在进行投屏或者蓝牙播放时执行重启类操作，即使只是验证。

设备型号或系统版本与预期不符时停止操作并说明原因，不要尝试兼容性适配。
