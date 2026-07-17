package com.mengwei.localgrowth.observationautomation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ProviderResponseParserTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final ProviderResponseParser parser = new ProviderResponseParser();

  @Test
  void parsesChatCompletion() throws Exception {
    var root = mapper.readTree("""
        {
          "id":"req-1",
          "model":"deepseek-chat",
          "choices":[{"message":{"content":"推荐示例品牌。"}}]
        }
        """);
    var result = parser.parseChatCompletions(root);
    assertThat(result.requestId()).isEqualTo("req-1");
    assertThat(result.model()).isEqualTo("deepseek-chat");
    assertThat(result.rawAnswer()).contains("示例品牌");
  }

  @Test
  void parsesResponsesAndCitations() throws Exception {
    var root = mapper.readTree("""
        {
          "id":"resp-1",
          "model":"doubao-seed",
          "output":[{
            "content":[{
              "type":"output_text",
              "text":"可以考虑示例品牌。",
              "annotations":[{
                "type":"url_citation",
                "url":"https://example.com/guide",
                "title":"Guide"
              }]
            }]
          }]
        }
        """);
    var result = parser.parseResponsesApi(root);
    assertThat(result.rawAnswer()).contains("示例品牌");
    assertThat(result.citedSources())
        .extracting(item -> item.get("url"))
        .contains("https://example.com/guide");
  }
}
