package com.aria.conversation.infrastructure.dit.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class NoAuthStrategy implements HttpAuthStrategy {
    @Override public String authType() { return "NONE"; }
    @Override public void apply(HttpHeaders headers, String authConfig, ObjectMapper mapper) {}
}
