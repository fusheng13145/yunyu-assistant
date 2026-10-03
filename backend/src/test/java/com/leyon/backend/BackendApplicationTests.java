package com.leyon.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

    /**
     * v2.77 · C-148（S-26 收口）：WebClient 的连接器必须是 reactor-netty——
     * 连接器按类路径择优，撤掉 reactor-netty-http 依赖会静默回落 JDK HttpClient，
     * 取消传播随之退化为"只撤订阅不拆交换"（S-26 复活）而全部单测仍绿，故取值必须钉住。
     */
    @Test
    void webClientConnectorIsReactorNetty(@Autowired ClientHttpConnector connector) {
        assertThat(connector).isInstanceOf(ReactorClientHttpConnector.class);
    }

}