package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.normalize.PublishTimeParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebMagicNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void followsOnlyConfiguredDetailLinksAndNeverRecursesFromDetail() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/list", "<div class='news-list'><a href='/news/1'>one</a><a href='/other'>other</a></div>");
        serve("/news/1", "<h1 class='title'>News one</h1><div class='body'>Enough news content for extraction.</div>"
            + "<a href='/news/2'>must not recurse</a>");
        serve("/news/2", "<h1 class='title'>News two</h1><div class='body'>Should never be collected.</div>");
        serve("/other", "<h1 class='title'>Other page</h1><div class='body'>Should never be collected.</div>");
        server.start();
        int port = server.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        NewsItemSink sink = raw -> {
            urls.add(raw.sourceUrl());
            return NewsAcceptance.ACCEPTED;
        };
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.setMaxItemsPerRun(10);
        WebMagicNewsCollector collector = new WebMagicNewsCollector(sink, properties, new PublishTimeParser());
        NewsSource source = new NewsSource(1L, "local", "Local", NewsSourceType.WEB_LIST,
            "http://localhost:" + port + "/list", "localhost",
            Map.of("linkSelector", ".news-list a", "titleSelector", ".title", "contentSelector", ".body",
                "urlPattern", "http://localhost:" + port + "/news/\\d+"), Map.of("sleepMillis", 0), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(urls).containsExactly("http://localhost:" + port + "/news/1");
    }

    private void serve(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
    }
}
