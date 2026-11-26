package com.epfisztner.knowledgeassistant.support;

import com.epfisztner.knowledgeassistant.generation.ChatModelPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ScriptedChatModelConfiguration {

    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel(ObjectProvider<ChatModelPort> adapters) {
        return new ScriptedChatModel(adapters);
    }
}
