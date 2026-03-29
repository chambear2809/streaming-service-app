package io.github.marianciuc.streamingservice.moderation.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ControllerMappingsTest {

    @Test
    void exposesModerationRoutesUnderExpectedBasePaths() throws NoSuchMethodException {
        RequestMapping categoryRequestMapping = CategoryController.class.getAnnotation(RequestMapping.class);
        RequestMapping topicRequestMapping = TopicController.class.getAnnotation(RequestMapping.class);

        assertNotNull(categoryRequestMapping);
        assertArrayEquals(new String[]{"/api/v1/moderation/categories"}, categoryRequestMapping.value());

        assertNotNull(topicRequestMapping);
        assertArrayEquals(new String[]{"/api/v1/moderation/topics"}, topicRequestMapping.value());

        GetMapping getAllCategories = CategoryController.class
                .getMethod("getAllCategories", Boolean.class)
                .getAnnotation(GetMapping.class);
        PostMapping createTopic = TopicController.class
                .getMethod("createTopic",
                        io.github.marianciuc.streamingservice.moderation.dto.requests.CreateTopicRequest.class)
                .getAnnotation(PostMapping.class);

        assertNotNull(getAllCategories);
        assertArrayEquals(new String[]{"/all"}, getAllCategories.value());

        assertNotNull(createTopic);
        assertArrayEquals(new String[]{}, createTopic.value());
    }
}
