package com.mengwei.localgrowth.report;

import com.mengwei.localgrowth.asset.AssetService;
import com.mengwei.localgrowth.identity.AuthService.Identity;
import com.mengwei.localgrowth.shared.ApiExceptionHandler.ApiException;
import com.mengwei.localgrowth.shared.TenantAccess;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportExportController {
  private static final Pattern EVIDENCE_IDS = Pattern.compile("\"evidenceIds\":\\[(.*?)\\]");
  private static final int PAGE_WIDTH = 1240;
  private static final int PAGE_HEIGHT = 1754;
  private static final int MARGIN_X = 86;
  private static final int TOP_Y = 110;
  private static final int BOTTOM_PADDING = 90;
  private static final int LINE_HEIGHT = 34;
  private static final int FONT_SIZE = 24;
  private final JdbcTemplate jdbc;
  private final TenantAccess access;
  private final AssetService assets;

  public ReportExportController(JdbcTemplate jdbc, TenantAccess access, AssetService assets) {
    this.jdbc = jdbc;
    this.access = access;
    this.assets = assets;
  }

  @GetMapping(value = "/{id}/pdf", produces = "application/pdf")
  public ResponseEntity<byte[]> pdf(@RequestHeader("Authorization") String auth, @PathVariable UUID id) throws Exception {
    return file(pdfBytes(identity(auth), id), "application/pdf", "geo-report-" + id + ".pdf");
  }

  @GetMapping(value = "/{id}/evidence.zip", produces = "application/zip")
  public ResponseEntity<byte[]> zip(@RequestHeader("Authorization") String auth, @PathVariable UUID id) throws Exception {
    Identity identity = identity(auth);
    Map<String, Object> report = report(identity, id);
    byte[] pdf = pdfBytes(identity, id);
    List<Map<String, Object>> evidence = evidenceRows(identity, report);
    String csv = csv(evidence);
    byte[] archive;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
      entry(zip, "report.pdf", pdf);
      entry(zip, "evidence.csv", csv.getBytes(StandardCharsets.UTF_8));
      for (Map<String, Object> row : evidence) {
        if (row.get("screenshot_asset_id") == null) {
          continue;
        }
        UUID assetId = UUID.fromString(String.valueOf(row.get("screenshot_asset_id")));
        Map<String, Object> meta = assets.metadata(identity, assetId);
        String filename = safeFilename(assetId + "-" + String.valueOf(meta.get("originalFilename")));
        entry(zip, "screenshots/" + filename, assets.bytes(identity, assetId));
      }
      zip.finish();
      archive = out.toByteArray();
    }
    return file(archive, "application/zip", "geo-evidence-" + id + ".zip");
  }

  private byte[] pdfBytes(Identity identity, UUID reportId) throws Exception {
    String markdown = String.valueOf(report(identity, reportId).get("report_markdown")).replace("\r", "");
    Font font = font();
    List<String> lines = wrap(markdown, font);
    int usableHeight = PAGE_HEIGHT - TOP_Y - BOTTOM_PADDING;
    int linesPerPage = Math.max(1, usableHeight / LINE_HEIGHT);
    try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int start = 0; start < lines.size(); start += linesPerPage) {
        BufferedImage image = new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(font);
        int y = TOP_Y;
        int end = Math.min(lines.size(), start + linesPerPage);
        for (int index = start; index < end; index++) {
          graphics.drawString(lines.get(index), MARGIN_X, y);
          y += LINE_HEIGHT;
        }
        graphics.dispose();

        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDImageXObject pageImage = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
          stream.drawImage(pageImage, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        }
      }
      document.save(out);
      return out.toByteArray();
    }
  }

  private Font font() {
    Font font = new Font("Noto Sans CJK SC", Font.PLAIN, FONT_SIZE);
    if (font.canDisplayUpTo("千色坊GEO诊断报告") == -1) {
      return font;
    }
    Font fallback = new Font("PingFang SC", Font.PLAIN, FONT_SIZE);
    if (fallback.canDisplayUpTo("千色坊GEO诊断报告") == -1) {
      return fallback;
    }
    return new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
  }

  private List<String> wrap(String text, Font font) {
    BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setFont(font);
    FontMetrics metrics = graphics.getFontMetrics();
    int maxWidth = PAGE_WIDTH - (MARGIN_X * 2);
    List<String> lines = new ArrayList<>();
    for (String paragraph : text.split("\n")) {
      if (paragraph.isBlank()) {
        lines.add(" ");
        continue;
      }
      StringBuilder current = new StringBuilder();
      for (int index = 0; index < paragraph.length(); index++) {
        String candidate = current + String.valueOf(paragraph.charAt(index));
        if (metrics.stringWidth(candidate) > maxWidth && current.length() > 0) {
          lines.add(current.toString());
          current = new StringBuilder().append(paragraph.charAt(index));
        } else {
          current.append(paragraph.charAt(index));
        }
      }
      if (current.length() > 0) {
        lines.add(current.toString());
      }
    }
    graphics.dispose();
    return lines;
  }

  private List<Map<String, Object>> evidenceRows(Identity identity, Map<String, Object> report) {
    List<UUID> ids = evidenceIds(String.valueOf(report.get("score_snapshot")));
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
    List<Object> args = new ArrayList<>();
    args.add(identity.tenantId());
    args.addAll(ids);
    return jdbc.queryForList(
        "select id,platform,model_name,source_type,search_enabled,captured_at,brand_mention_count,brand_first_position,citation_links,screenshot_asset_id,operator_notes from evidence_observations where tenant_id=? and id in ("
            + placeholders
            + ") order by captured_at",
        args.toArray());
  }

  private List<UUID> evidenceIds(String snapshot) {
    Matcher matcher = EVIDENCE_IDS.matcher(snapshot);
    if (!matcher.find()) {
      return List.of();
    }
    String body = matcher.group(1).trim();
    if (body.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>();
    for (String part : body.split(",")) {
      String value = part.replace("\"", "").trim();
      if (!value.isEmpty()) {
        ids.add(UUID.fromString(value));
      }
    }
    return ids;
  }

  private String csv(List<Map<String, Object>> rows) {
    StringBuilder csv = new StringBuilder("evidence_id,platform,model_name,source_type,search_enabled,captured_at,brand_mentions,first_position,citation_links,screenshot_asset_id,operator_notes\n");
    for (Map<String, Object> row : rows) {
      csv.append(row.get("id")).append(',');
      csv.append(quoted(row.get("platform"))).append(',');
      csv.append(quoted(row.get("model_name"))).append(',');
      csv.append(quoted(row.get("source_type"))).append(',');
      csv.append(row.get("search_enabled")).append(',');
      csv.append(row.get("captured_at")).append(',');
      csv.append(row.get("brand_mention_count")).append(',');
      csv.append(row.get("brand_first_position")).append(',');
      csv.append(quoted(row.get("citation_links"))).append(',');
      csv.append(quoted(row.get("screenshot_asset_id"))).append(',');
      csv.append(quoted(row.get("operator_notes"))).append('\n');
    }
    return csv.toString();
  }

  private void entry(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(bytes);
    zip.closeEntry();
  }

  private String quoted(Object value) {
    return "\"" + String.valueOf(value == null ? "" : value).replace("\"", "\"\"") + "\"";
  }

  private String safeFilename(String name) {
    return name.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private Map<String, Object> report(Identity identity, UUID id) {
    List<Map<String, Object>> rows = jdbc.queryForList(
        "select id,diagnostic_run_id,report_markdown,score_snapshot from diagnostic_reports where id=? and tenant_id=?",
        id,
        identity.tenantId());
    if (rows.isEmpty()) {
      throw new ApiException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "报告不存在");
    }
    return new LinkedHashMap<>(rows.getFirst());
  }

  private Identity identity(String auth) {
    return access.identity(auth);
  }

  private ResponseEntity<byte[]> file(byte[] bytes, String type, String filename) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
        .body(bytes);
  }
}
