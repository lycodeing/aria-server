package com.aria.conversation.infrastructure.dit.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * DIT 工具出站 URL 的 SSRF 防护校验器。
 *
 * <p>DIT 工具的 {@code url_template} 会被运行期用户消息抽取出的槽位值填充，
 * 若不加限制，攻击者可诱导服务端向内网服务、云 metadata 端点（169.254.169.254）
 * 等发起请求（SSRF）。本组件在请求发起前对最终 URL 做校验：
 * <ol>
 *   <li>协议仅允许 http / https；</li>
 *   <li>解析 host 得到的所有 IP 均不得为回环、私网、link-local、通配、
 *       多播或云 metadata 地址。</li>
 * </ol>
 *
 * <p>校验不通过时抛 {@link SsrfBlockedException}，由调用方转为工具调用失败。
 */
@Slf4j
@Component
public class SsrfGuard {

    /**
     * 校验出站 URL 是否安全，不安全时抛 {@link SsrfBlockedException}。
     *
     * @param url 已完成占位符替换的最终请求 URL
     */
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SsrfBlockedException("非法的 URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfBlockedException("仅允许 http/https 协议: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL 缺少合法 host: " + url);
        }

        // 解析 host 的所有 IP，任一命中内网/保留地址即拒绝，防止 DNS 重绑定绕过
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("host 无法解析: " + host);
        }
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                log.warn("[DIT][SSRF] 拒绝访问内网/保留地址 host={} ip={}", host, addr.getHostAddress());
                throw new SsrfBlockedException("目标地址不允许访问（内网/保留地址）: " + addr.getHostAddress());
            }
        }
    }

    /**
     * 判定 IP 是否属于应拦截的内网/保留地址范围。
     */
    private boolean isBlocked(InetAddress addr) {
        return addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isLinkLocalAddress()  // 169.254.0.0/16（含云 metadata）, fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()  // 224.0.0.0/4
                || isUniqueLocalIpv6(addr);   // fc00::/7
    }

    /**
     * 判定是否为 IPv6 唯一本地地址（fc00::/7），InetAddress 未内建此判定。
     */
    private boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    /**
     * SSRF 校验未通过异常。
     */
    public static class SsrfBlockedException extends RuntimeException {
        public SsrfBlockedException(String message) {
            super(message);
        }
    }
}
