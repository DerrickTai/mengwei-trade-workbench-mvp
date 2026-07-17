package com.mengwei.localgrowth.observationautomation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PublicWebMetadataFetcher {
  private static final Pattern TITLE =
      Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
  private static final Pattern CANONICAL =
      Pattern.compile("(?is)<link[^>]+rel=[\'\\\"]?canonical[\'\\\"]?[^>]+href=[\'\\\"]([^\'\\\"]+)");
  private static final Pattern AUTHOR =
      Pattern.compile("(?is)<meta[^>]+name=[\'\\\"]author[\'\\\"][^>]+content=[\'\\\"]([^\'\\\"]+)");
  private static final Pattern PUBLISHER =
      Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\'\\\"]og:site_name[\'\\\"][^>]+content=[\'\\\"]([^\'\\\"]+)");

  private final SafePublicUrlPolicy policy;
  private final HttpClient client;
  private final int maxBytes;
  private final Duration timeout;

  public PublicWebMetadataFetcher(
      SafePublicUrlPolicy policy,
      @Value("${geo.public-fetch.max-bytes:2000000}") int maxBytes,
      @Value("${geo.public-fetch.timeout-seconds:20}") int timeoutSeconds) {
    this.policy = policy;
    this.maxBytes = maxBytes;
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public FetchResult fetch(String rawUrl) {
    URI current = policy.requirePublicHttpUrl(rawUrl);
    for (int redirect = 0; redirect <= 5; redirect++) {
      try {
        HttpRequest request = HttpRequest.newBuilder(current)
            .timeout(timeout)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("User-Agent", "MengweiGeoEvidenceBot/1.0")
            .GET()
            .build();
        HttpResponse<byte[]> response =
            client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
          String location = response.headers().firstValue("location")
              .orElseThrow(() -> new IllegalArgumentException("Redirect without Location"));
          current = policy.requirePublicHttpUrl(current.resolve(location).toString());
          continue;
        }
        byte[] body = response.body() == null ? new byte[0] : response.body();
        if (body.length > maxBytes) {
          return FetchResult.failed(
              current.toString(), status, "RESPONSE_TOO_LARGE",
              "Page exceeded " + maxBytes + " bytes");
        }
        String html = new String(body, StandardCharsets.UTF_8);
        return new FetchResult(
            true,
            current.toString(),
            status,
            text(TITLE, html),
            resolveCanonical(current, text(CANONICAL, html)),
            text(AUTHOR, html),
            text(PUBLISHER, html),
            sha256(body),
            OffsetDateTime.now(),
            null,
            null);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return FetchResult.failed(
            current.toString(), null, "FETCH_INTERRUPTED", "Fetch interrupted");
      } catch (Exception e) {
        return FetchResult.failed(
            current.toString(), null, "FETCH_FAILED", safeMessage(e));
      }
    }
    return FetchResult.failed(
        current.toString(), null, "TOO_MANY_REDIRECTS", "Too many redirects");
  }

  private String text(Pattern pattern, String html) {
    Matcher matcher = pattern.matcher(html);
    if (!matcher.find()) return null;
    return matcher.group(1)
        .replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String resolveCanonical(URI base, String canonical) {
    if (canonical == null || canonical.isBlank()) return null;
    try {
      return policy.requirePublicHttpUrl(base.resolve(canonical).toString()).toString();
    } catch (Exception ignored) {
      return null;
    }
  }

  private String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private String safeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) return e.getClass().getSimpleName();
    return message.length() > 1000 ? message.substring(0, 1000) : message;
  }

  public record FetchResult(
      boolean success,
      String finalUrl,
      Integer httpStatus,
      String title,
      String canonicalUrl,
      String author,
      String publisher,
      String contentSha256,
      OffsetDateTime fetchedAt,
      String errorCode,
      String errorMessage) {

    static FetchResult failed(
        String finalUrl,
        Integer status,
        String code,
        String message) {
      return new FetchResult(
          false, finalUrl, status, null, null, null, null, null,
          OffsetDateTime.now(), code, message);
    }
  }
}
