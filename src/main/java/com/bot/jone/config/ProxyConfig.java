package com.bot.jone.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * 出站代理配置（前缀 bot.proxy）。
 *
 * 设计目标：让 bot 在调用工具（网页搜索/图片下载/图库/二次元图/远程 git 等）时，
 * 经由用户指定的代理服务器出口去获取外网信息；而 DeepSeek 等模型 API 默认直连，
 * 避免代理拖慢或阻断核心对话。
 *
 * 实现方式：项目所有出站请求都走 JDK 内置的 HttpURLConnection，它默认服从 JVM 的
 * ProxySelector。因此这里只需安装一个自定义 ProxySelector，即可让全部 HttpURLConnection
 * 调用（含 JGit 的 HTTP 传输）自动走代理，无需逐个改工具代码。
 *
 * 关闭（enabled=false）时完全不触碰 JVM 全局设置，工具调用直连，无任何副作用。
 *
 * 热更新：WebUI 修改后调用 {@link #reapply()}，先复位为系统默认选择器再按当前字段重新安装，
 * 避免每次 reapply 都嵌套一层自定义 selector（否则 connectFailed 链会越套越深）。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "bot.proxy")
public class ProxyConfig {

    /** 是否启用代理。默认关闭，工具调用直连外网。 */
    private boolean enabled = false;

    /**
     * 代理类型：http / https / socks。
     * - http / https 均走 HTTP 类型代理（HTTPS 目标经 CONNECT 隧道）；
     * - socks 走 SOCKS4/5 代理。
     */
    private String type = "http";

    /** 代理服务器地址（域名或 IP），如 Clash/v2ray/shadowsocks 的本地监听地址。 */
    private String host = "127.0.0.1";

    /** 代理服务器端口。 */
    private int port = 7890;

    /** 代理认证用户名（可选，匿名代理留空）。 */
    private String username;

    /** 代理认证密码（可选）。 */
    private String password;

    /**
     * 不走代理的主机列表（| 分隔，支持 *.example.com 通配）。
     * 默认包含 localhost/127.0.0.1 与 DeepSeek API，使模型对话直连、工具调用走代理。
     * 若你的 AI api-url 不是 api.deepseek.com，请在此追加对应主机。
     */
    private String nonProxyHosts = "localhost|127.0.0.1|api.deepseek.com";

    /**
     * 系统默认 ProxySelector（首次安装时记录）。reapply 时作为 previous 与复位基准，
     * 避免多层嵌套自定义 selector。
     */
    private ProxySelector systemDefault;

    @PostConstruct
    public void apply() {
        if (systemDefault == null) {
            systemDefault = ProxySelector.getDefault();
        }
        install();
    }

    /**
     * 热更新：WebUI 修改配置后重新安装 ProxySelector。
     * 先复位为系统默认，再按当前字段决定是否安装，避免反复嵌套。
     */
    public synchronized void reapply() {
        if (systemDefault == null) {
            systemDefault = ProxySelector.getDefault();
        }
        ProxySelector.setDefault(systemDefault);
        install();
    }

    /** 按当前字段安装 ProxySelector（不重复处理 systemDefault 记录）。 */
    private void install() {
        if (!enabled) {
            log.info("[proxy] 代理未启用，所有出站请求直连外网（含工具调用；模型 API 亦始终直连）");
            return;
        }
        if (host == null || host.isBlank() || port <= 0) {
            log.warn("[proxy] 已启用但 host/port 无效（host={}, port={}），代理未生效，工具调用直连", host, port);
            return;
        }

        Proxy.Type proxyType = "socks".equalsIgnoreCase(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        Proxy proxy = new Proxy(proxyType, new InetSocketAddress(host, port));

        List<String> bypass = Arrays.stream(nonProxyHosts.split("\\|"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        ProxySelector previous = systemDefault;
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String h = uri.getHost();
                if (h != null) {
                    String lower = h.toLowerCase();
                    for (String b : bypass) {
                        if (b.equalsIgnoreCase(h)) {
                            return List.of(Proxy.NO_PROXY);
                        }
                        if (b.startsWith("*.") && lower.endsWith(b.substring(1).toLowerCase())) {
                            return List.of(Proxy.NO_PROXY);
                        }
                    }
                }
                return List.of(proxy);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                log.warn("[proxy] 经代理连接失败 {} ({}): {}", uri, sa, ioe.getMessage());
                if (previous != null) {
                    try {
                        previous.connectFailed(uri, sa, ioe);
                    } catch (Exception ignored) {
                        // 原选择器失败不影响本次
                    }
                }
            }
        });

        if (username != null && !username.isBlank() && password != null) {
            final String u = username;
            final String p = password;
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == Authenticator.RequestorType.PROXY) {
                        return new PasswordAuthentication(u, p.toCharArray());
                    }
                    return null;
                }
            });
            log.info("[proxy] 已启用 {} 代理 {}:{}（带认证），工具调用经此出口；nonProxyHosts={}", proxyType, host, port, nonProxyHosts);
        } else {
            log.info("[proxy] 已启用 {} 代理 {}:{}，工具调用经此出口；nonProxyHosts={}", proxyType, host, port, nonProxyHosts);
        }
    }
}
