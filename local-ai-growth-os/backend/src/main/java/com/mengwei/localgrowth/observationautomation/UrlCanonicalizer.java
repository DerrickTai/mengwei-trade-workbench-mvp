package com.mengwei.localgrowth.observationautomation;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class UrlCanonicalizer {
  private static final Set<String> TRACKING_KEYS = Set.of(
      "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
      "gclid", "fbclid", "spm", "from", "source");

  public NormalizedUrl normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("URL is required");
    }
    try {
      URI source = new URI(raw.trim());
      String scheme = source.getScheme() == null
          ? ""
          : source.getScheme().toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) {
        throw new IllegalArgumentException("Only HTTP and HTTPS URLs are allowed");
      }
      String host = source.getHost();
      if (host == null || host.isBlank()) {
        throw new IllegalArgumentException("URL host is required");
      }
      host = IDN.toASCII(host.toLowerCase(Locale.ROOT));
      int port = source.getPort();
      if ((scheme.equals("https") && port == 443)
          || (scheme.equals("http") && port == 80)) {
        port = -1;
      }
      String path = source.getRawPath();
      if (path == null || path.isBlank()) path = "/";
      path = path.replaceAll("/{2,}", "/");
      if (path.length() > 1 && path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      String query = normalizeQuery(source.getRawQuery());
      URI normalized = new URI(scheme, null, host, port, path, query, null);
      return new NormalizedUrl(normalized.toASCIIString(), host);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Invalid URL", e);
    }
  }

  private String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) return null;
    List<QueryPair> pairs = new ArrayList<>();
    for (String part : rawQuery.split("&")) {
      if (part.isBlank()) continue;
      String[] pieces = part.split("=", 2);
      String key = decode(pieces[0]);
      if (TRACKING_KEYS.contains(key.toLowerCase(Locale.ROOT))) continue;
      String value = pieces.length == 2 ? decode(pieces[1]) : "";
      pairs.add(new QueryPair(key, value));
    }
    pairs.sort(Comparator.comparing(QueryPair::key).thenComparing(QueryPair::value));
    if (pairs.isEmpty()) return null;
    return pairs.stream()
        .map(pair -> encode(pair.key()) + "=" + encode(pair.value()))
        .reduce((a, b) -> a + "&" + b)
        .orElse(null);
  }

  private String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private record QueryPair(String key, String value) {}

  public record NormalizedUrl(String url, String domain) {}
}
