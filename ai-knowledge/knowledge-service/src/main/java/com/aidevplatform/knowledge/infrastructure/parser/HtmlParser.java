package com.aidevplatform.knowledge.infrastructure.parser;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * HTML 文档解析器（基于 Jsoup）。
 * 优先提取语义标签（main/article/[role=main]），去除导航栏、页脚、广告等噪声。
 */
@Slf4j
@Component
public class HtmlParser implements DocumentParser {

    @Override
    public String parse(byte[] content) {
        Document doc = Jsoup.parse(new String(content, StandardCharsets.UTF_8));

        // 优先提取语义化内容区，降级到 body
        Element main = doc.selectFirst("main, article, [role=main], .content, #content");
        String text = (main != null ? main : doc.body()).text();
        log.debug("HTML 解析完成，字符数={}", text.length());
        return text;
    }

    @Override
    public String supportedType() {
        return "HTML";
    }
}
