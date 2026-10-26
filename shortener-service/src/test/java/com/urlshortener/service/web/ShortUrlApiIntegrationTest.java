package com.urlshortener.service.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full stack: HTTP -> controller -> service -> JPA -> H2 with the real Flyway schema. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortUrlApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void createRedirectAndCountClicks() throws Exception {
        String code = create("{\"url\":\"https://example.com/integration/one\"}", 201);

        mvc.perform(get("/" + code).header("Referer", "https://news.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/integration/one"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(2))
                .andExpect(jsonPath("$.clicksByDay[0].clicks").value(2))
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("https://news.example"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(1));
    }

    @Test
    void createReturnsLocationAndShortUrl() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/integration/location\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/urls/")))
                .andExpect(jsonPath("$.shortUrl", startsWith("http://localhost:8080/")))
                .andExpect(jsonPath("$.customAlias").value(false));
    }

    @Test
    void sameUrlIsDeduplicated() throws Exception {
        String body = "{\"url\":\"https://example.com/integration/dedupe\"}";
        String first = create(body, 201);
        String second = create(body, 200);
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }

    @Test
    void customAliasAndConflict() throws Exception {
        String code = create("{\"url\":\"https://example.com/a\",\"alias\":\"my-launch\"}", 201);
        org.assertj.core.api.Assertions.assertThat(code).isEqualTo("my-launch");

        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/b\",\"alias\":\"my-launch\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Alias already in use"));
    }

    @Test
    void invalidInputIsRejectedAsProblemJson() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.detail").value("only http and https URLs can be shortened"));

        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("url")));

        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"ttlSeconds\":-5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mvc.perform(get("/zzzzzzz")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short URL not found"));
        mvc.perform(get("/api/v1/urls/zzzzzzz/stats")).andExpect(status().isNotFound());
    }

    @Test
    void expiredLinkIs410() throws Exception {
        String code = create("{\"url\":\"https://example.com/expiring\",\"ttlSeconds\":1}", 201);
        Thread.sleep(1100);
        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(get("/api/v1/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.totalClicks").value(0));
    }

    @Test
    void deletedLinkStopsRedirecting() throws Exception {
        String code = create("{\"url\":\"https://example.com/to-delete\"}", 201);
        mvc.perform(get("/" + code)).andExpect(status().isFound()); // warms the cache
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNoContent());
        mvc.perform(get("/" + code)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/urls/" + code)).andExpect(status().isNotFound());
    }

    @Test
    void healthAndOpenApiAreExposed() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/urls']").exists());
    }

    private String create(String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return node.get("code").asText();
    }
}
