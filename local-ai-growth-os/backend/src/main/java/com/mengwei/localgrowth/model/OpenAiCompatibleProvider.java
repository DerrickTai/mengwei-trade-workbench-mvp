package com.mengwei.localgrowth.model;

import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Customer-authorized OpenAI-compatible Chat Completions client. Secrets are never logged or returned. */
@Component public class OpenAiCompatibleProvider implements ModelProviderPort {
  private final String baseUrl,apiKey,model; private final RestClient client;
  public OpenAiCompatibleProvider(@Value("${app.openai.base-url:}")String baseUrl,@Value("${app.openai.api-key:}")String apiKey,@Value("${app.openai.model:}")String model,@Value("${app.openai.connect-timeout-ms:5000}")int connect,@Value("${app.openai.read-timeout-ms:30000}")int read){this.baseUrl=baseUrl;this.apiKey=apiKey;this.model=model;SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(Duration.ofMillis(connect));f.setReadTimeout(Duration.ofMillis(read));this.client=RestClient.builder().requestFactory(f).build();}
  public String name(){return "OPENAI_COMPATIBLE";} public boolean configured(){return !baseUrl.isBlank()&&!apiKey.isBlank()&&!model.isBlank();} public String configuredModel(){return model.isBlank()?"UNCONFIGURED":model;}
  @SuppressWarnings("unchecked") public ModelAnswer invoke(String prompt,String brand,String services,String city){if(!configured())throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_NOT_CONFIGURED","未配置已授权的 OpenAI 兼容模型连接");long start=System.currentTimeMillis();Map<String,Object> payload=new LinkedHashMap<>();payload.put("model",model);payload.put("temperature",0);payload.put("response_format",Map.of("type","json_object"));payload.put("messages",List.of(Map.of("role","user","content",prompt)));try{ResponseEntity<Map> entity=client.post().uri(baseUrl.replaceAll("/$","")+"/chat/completions").contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer "+apiKey).body(payload).retrieve().toEntity(Map.class);Map<String,Object> response=entity.getBody()==null?Map.of():entity.getBody();List<Map<String,Object>> choices=(List<Map<String,Object>>)response.getOrDefault("choices",List.of());if(choices.isEmpty())throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_RESPONSE_INVALID","模型返回缺少choices");Map<String,Object> message=(Map<String,Object>)choices.getFirst().get("message");String text=String.valueOf(message==null?"":message.get("content"));Map<String,Object> usage=(Map<String,Object>)response.get("usage");Long tokens=usage==null?null:toLong(usage.get("total_tokens"));return new ModelAnswer(model,text,System.currentTimeMillis()-start,false,entity.getStatusCode().value(),tokens,summary(text));}catch(ApiException e){throw e;}catch(org.springframework.web.client.HttpClientErrorException.Unauthorized e){throw new ApiException(HttpStatus.UNAUTHORIZED,"MODEL_AUTH_FAILED","模型鉴权失败，请检查API Key");}catch(org.springframework.web.client.HttpClientErrorException.TooManyRequests e){throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"MODEL_RATE_LIMITED","模型请求受到限流，请稍后重试");}catch(org.springframework.web.client.HttpServerErrorException e){throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_PROVIDER_ERROR","模型供应商暂时不可用");}catch(Exception e){throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,"MODEL_CALL_FAILED","模型调用失败，请检查端点、网络和超时配置");}}
  private Long toLong(Object v){try{return v==null?null:Long.valueOf(String.valueOf(v));}catch(Exception e){return null;}} private String summary(String text){return text==null?"":text.substring(0,Math.min(text.length(),4000));}
}
