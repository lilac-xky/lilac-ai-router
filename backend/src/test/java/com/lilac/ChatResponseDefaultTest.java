package com.lilac;

import com.lilac.domain.dto.chat.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChatResponseDefaultTest {

    @Test
    void builderPathKeepsDefault() {
        assertEquals("chat.completion", ChatResponse.builder().build().getObject());
    }

    @Test
    void noArgsPathKeepsDefault() {
        assertEquals("chat.completion", new ChatResponse().getObject());
    }
}
