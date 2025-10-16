/*
 * Copyright (C) 2023, 2024, THL A29 Limited, a Tencent company. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package cn.byzk.example.sslserver.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Getter
@Configuration
@Order(1)
public class GmSSLConfig {


    /**
     * required：强制要求客户端提供证书，如果客户端没有证书或证书无效，连接会被拒绝
     */
    public static String CLIENT_AUTH_REQUIRED = "required";

    /**
     * optional：客户端可以选择提供证书，如果提供了会进行验证，不提供也允许连接
     */
    public static String CLIENT_AUTH_OPTIONAL = "optional";

    /**
     * none：不验证客户端证书（默认行为）
     */
    public static String CLIENT_AUTH_NONE = "none";

    @Value("${server.gm-ssl.ssl-gm-port}")
    private int port;

    @Value("${server.gm-ssl.enabled}")
    private boolean sslEnabled;

    @Value("${server.gm-ssl.provider:#{null}}")
    private String provider;

    @Value("${server.gm-ssl.trust-store-provider:#{null}}")
    private String trustStoreProvider;

    @Value("${server.gm-ssl.trust-store-type}")
    private String trustStoreType;

    @Value("${server.gm-ssl.trust-store}")
    private String trustStorePath;

    @Value("${server.gm-ssl.trust-store-password}")
    private String trustStorePassword;

    @Value("${server.gm-ssl.key-store-provider:#{null}}")
    private String keyStoreProvider;

    @Value("${server.gm-ssl.key-store-type}")
    private String keyStoreType;

    @Value("${server.gm-ssl.key-store}")
    private String keyStorePath;

    @Value("${server.gm-ssl.key-store-password}")
    private String keyStorePassword;

    @Value("${server.gm-ssl.protocol}")
    private String contextProtocol;

    /**
     * 客户端双向验证,服务端是否需要验证
     * required：强制要求客户端提供证书，如果客户端没有证书或证书无效，连接会被拒绝
     * optional：客户端可以选择提供证书，如果提供了会进行验证，不提供也允许连接
     * none：不验证客户端证书（默认行为）
     */
    @Value("${server.gm-ssl.client-auth:none}")
    private String clientAuth;

    @Value("${server.http2.enabled:false}")
    private boolean http2Enabled;


    public boolean isClientAuthEnabled() {
        return CLIENT_AUTH_REQUIRED.equals(clientAuth) || CLIENT_AUTH_OPTIONAL.equals(clientAuth);
    }

}
