package com.aidevplatform.common.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** Long/long 序列化为 String，防止前端 JS 对雪花 ID 大整数丢精度。下沉自各服务重复的 LongToStringJacksonConfig。 */
@Configuration
public class JacksonLongToStringConfig implements WebMvcConfigurer {

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> c : converters) {
            if (c instanceof MappingJackson2HttpMessageConverter jc) {
                ObjectMapper om = jc.getObjectMapper();
                SimpleModule m = new SimpleModule();
                m.addSerializer(Long.class, ToStringSerializer.instance);
                m.addSerializer(Long.TYPE, ToStringSerializer.instance);
                om.registerModule(m);
            }
        }
    }
}
