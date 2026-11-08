package com.magpie.android;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机 HTTP / CONNECT 代理 —— 存在的唯一理由：DNS。
 *
 * <p>magpie 是 CGO 关掉的静态 Go 程序，域名解析走 Go 自己那套：读
 * {@code /etc/resolv.conf}，读不到就退回 {@code 127.0.0.1:53} 和 {@code ::1:53}
 * （Android 上没人监听，于是日志里一片
 * {@code lookup usemagpie.ai on [::1]:53: connection refused}）。
 *
 * <p>Android 的解析要经过 netd，Go 的纯解析器不会用；而普通 App 也绑不了 53 端口
 * （{@code ip_unprivileged_port_start = 1024}）。所以换个方向：让 magpie 的
 * <b>HTTP 请求</b>走这个本机代理，域名交给 Java 的 {@link InetSocketAddress}
 * 解析 —— 那走的就是系统 netd，能正常出网。magpie 的 netproxy 在没设 Proxy 时
 * 会读环境变量，所以 GatewayService 只要往子进程的环境里塞
 * {@code HTTPS_PROXY} / {@code HTTP_PROXY} 就生效，magpie 自己的设置一个字都不用改。
 *
 * <p>HTTPS 走 {@code CONNECT} 隧道（不解密，纯转发字节），明文 HTTP 把
 * 请求行从 absolute-form 改回 origin-form 再转发。
 */
public final class LocalProxy {

    private static final String TAG = "MagpieProxy";

    /** 试这些端口，第一个能绑上的就是它。 */
    private static final int FIRST_PORT = 7899;
    private static final int LAST_PORT = 7919;

    private static LocalProxy current;

    private final ServerSocket server;
    private final int port;
    private final ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread accept;

    private LocalProxy(ServerSocket server, int port) {
        this.server = server;
        this.port = port;
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "magpie-proxy");
            t.setDaemon(true);
            return t;
        });
    }

    /** 已经在跑就复用，否则挑一个空闲端口起来。返回代理地址。 */
    public static synchronized String ensure() throws IOException {
        if (current != null && current.running.get()) {
            return current.url();
        }
        IOException last = null;
        for (int p = FIRST_PORT; p <= LAST_PORT; p++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p));
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            current = new LocalProxy(s, p);
            current.begin();
            Log.i(TAG, "本地代理起来了：http://127.0.0.1:" + p);
            return current.url();
        }
        throw last == null ? new IOException("没有空闲端口给本地代理") : last;
    }

    public static synchronized void stop() {
        LocalProxy p = current;
        current = null;
        if (p != null) {
            p.close();
        }
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    private void begin() {
        accept = new Thread(() -> {
            while (running.get()) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    if (running.get()) {
                        Log.w(TAG, "accept 失败: " + e);
                    }
                    return;
                }
            }
        }, "magpie-proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    private void close() {
        running.set(false);
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
        Log.i(TAG, "本地代理停了");
    }

    // ------------------------------------------------------------------

    private void serve(Socket client) {
        Socket up = null;
        try {
            client.setSoTimeout(60000);
            client.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(client.getInputStream(), 8192);
            OutputStream out = client.getOutputStream();

            String request = readLine(in);
            if (request == null || request.length() == 0) {
                return;
            }
            StringBuilder head = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = readLine(in)) != null && line.length() > 0) {
                if (++guard > 200) {
                    return;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:")) {
                    continue; // 只对代理有意义的头，别带给上游
                }
                head.append(line).append("\r\n");
            }

            String[] parts = request.split(" ");
            if (parts.length < 3) {
                writeError(out, "400 Bad Request", "bad request line");
                return;
            }
            boolean connect = "CONNECT".equalsIgnoreCase(parts[0]);
            String host;
            int port;
            String originPath = null;

            if (connect) {
                String target = parts[1];
                int colon = target.lastIndexOf(':');
                host = colon > 0 ? target.substring(0, colon) : target;
                port = colon > 0 ? port(target.substring(colon + 1), 443) : 443;
            } else {
                URI u;
                try {
                    u = URI.create(parts[1]);
                } catch (Exception e) {
                    writeError(out, "400 Bad Request", "bad url");
                    return;
                }
                host = u.getHost();
                port = u.getPort() > 0 ? u.getPort() : 80;
                String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
                originPath = u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
            }
            if (host == null || host.length() == 0) {
                writeError(out, "400 Bad Request", "no host");
                return;
            }

            up = new Socket();
            up.setTcpNoDelay(true);
            up.connect(new InetSocketAddress(host, port), 20000);
            up.setSoTimeout(180000);

            if (connect) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("UTF-8"));
                out.flush();
            } else {
                String rebuilt = parts[0] + " " + originPath + " " + parts[2] + "\r\n"
                        + head + "Connection: close\r\n\r\n";
                up.getOutputStream().write(rebuilt.getBytes("UTF-8"));
                up.getOutputStream().flush();
            }

            final Socket upstream = up;
            Thread toUp = new Thread(() -> copy(in, upstream), "magpie-proxy-up");
            toUp.setDaemon(true);
            toUp.start();
            copy(up.getInputStream(), out);
            try {
                toUp.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            Log.i(TAG, "一条连接结束: " + e);
        } finally {
            closeQuietly(up);
            closeQuietly(client);
        }
    }

    private static void copy(InputStream in, Socket to) {
        try {
            copy(in, to.getOutputStream());
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeError(OutputStream out, String status, String body) {
        try {
            String s = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n" + body;
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** 读一行（到 \n），去掉行尾的 \r。最多 8 KB，防跑偏。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                throw new IOException("请求头太长");
            }
        }
        if (c == -1 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    private static int port(String s, int fallback) {
        try {
            int p = Integer.parseInt(s.trim());
            return p > 0 && p < 65536 ? p : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
