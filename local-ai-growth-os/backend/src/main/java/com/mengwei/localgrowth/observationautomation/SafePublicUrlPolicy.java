package com.mengwei.localgrowth.observationautomation;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SafePublicUrlPolicy {

  public URI requirePublicHttpUrl(String rawUrl) {
    URI uri = URI.create(rawUrl);
    String scheme = uri.getScheme() == null
        ? ""
        : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      throw new IllegalArgumentException("Only HTTP and HTTPS URLs are allowed");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("URL host is required");
    }
    if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")) {
      throw new IllegalArgumentException("Localhost is blocked");
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(host)) {
        if (!isPublic(address)) {
          throw new IllegalArgumentException("Private or reserved address is blocked");
        }
      }
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("URL host cannot be resolved", e);
    }
    return uri;
  }

  boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      if (first == 0
          || first == 10
          || first == 127
          || first >= 224
          || (first == 169 && second == 254)
          || (first == 172 && second >= 16 && second <= 31)
          || (first == 192 && second == 168)
          || (first == 100 && second >= 64 && second <= 127)) {
        return false;
      }
    }
    if (address instanceof Inet6Address) {
      int first = Byte.toUnsignedInt(bytes[0]);
      int second = Byte.toUnsignedInt(bytes[1]);
      if ((first & 0xfe) == 0xfc
          || (first == 0xfe && (second & 0xc0) == 0x80)) {
        return false;
      }
    }
    return true;
  }
}
