package com.lx04.doctor;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 ADB 客户端 —— 只用来连本机 adbd（127.0.0.1:5555）跑固定命令。
 *
 * 为什么能这么干：
 *   1. 这台设备 adbd 没开认证（/data/misc/adb/ 里连 adb_keys 都没有，ro.adb.secure 为空）
 *   2. adbd 进程是 root 身份，所以它的 shell 就是 root shell
 *   3. SELinux 实测放行 untrusted_app 连 127.0.0.1:5555（连不通的端口会 Connection refused）
 *
 * 于是 app 不需要 root、不需要改 /vendor，也能执行需要 root 的修复动作。
 * 注意：这里刻意只支持"发一条命令、读回全部输出"，不提供任意交互式 shell。
 */
final class AdbShell {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int VERSION = 0x01000000;
    private static final int MAXDATA = 4096;

    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private int localId = 1;
    private int remoteId = 0;

    /** 连接并执行一条命令，返回 stdout+stderr 合并输出 */
    static String run(String cmd, int timeoutMs) throws IOException {
        AdbShell c = new AdbShell();
        try {
            return c.exec(cmd, timeoutMs);
        } finally {
            c.close();
        }
    }

    private String exec(String cmd, int timeoutMs) throws IOException {
        sock = new Socket();
        sock.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        sock.setSoTimeout(timeoutMs);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());

        send(A_CNXN, VERSION, MAXDATA, "host::\u0000".getBytes("UTF-8"));

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean opened = false;
        ByteArrayOutputStream result = new ByteArrayOutputStream();

        while (System.currentTimeMillis() < deadline) {
            Packet p = read();
            if (p == null) break;

            if (p.cmd == A_CNXN) {
                // 连上了，接下来开 shell 服务
                if (!opened) {
                    send(A_OPEN, localId, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
                    opened = true;
                }
            } else if (p.cmd == A_AUTH) {
                // 这台机器不该出现，出现说明认证被打开了 —— 明确报错，别装作能用
                throw new IOException("adbd 要求认证，本机自启通道不可用");
            } else if (p.cmd == A_OKAY) {
                if (p.arg0 != 0) remoteId = p.arg0;
                // 不再发 WRTE：命令已经在 OPEN 的服务名里，直接收输出
            } else if (p.cmd == A_WRTE) {
                if (p.data != null && p.data.length > 0) result.write(p.data);
                send(A_OKAY, remoteId == 0 ? localId : remoteId, p.arg0, new byte[0]);
            } else if (p.cmd == A_CLSE) {
                break;
            }
        }
        return result.toString("UTF-8");
    }

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Packet read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        Packet p = new Packet();
        p.cmd = le32(head, 0);
        p.arg0 = le32(head, 4);
        p.arg1 = le32(head, 8);
        int len = le32(head, 12);
        // 12..16 checksum, 16..20 magic
        if (len < 0 || len > 256 * 1024) throw new IOException("bad length " + len);
        if (len > 0) {
            p.data = new byte[len];
            in.readFully(p.data);
        } else {
            p.data = new byte[0];
        }
        return p;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        int sum = 0;
        for (byte b : data) sum += (b & 0xFF);

        byte[] head = new byte[24];
        put32(head, 0, cmd);
        put32(head, 4, arg0);
        put32(head, 8, arg1);
        put32(head, 12, data.length);
        put32(head, 16, sum);
        put32(head, 20, cmd ^ 0xFFFFFFFF);
        out.write(head);
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void close() {
        try {
            if (sock != null) sock.close();
        } catch (Exception ignored) {
        }
    }
}
