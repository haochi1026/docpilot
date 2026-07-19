package com.internship.docpilot.service;

import com.internship.docpilot.model.PageText;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {
  private final boolean ocrEnabled;
  private final String ocrLanguage;
  private final int minPageChars;
  private final int maxOcrPages;
  private final int ocrTimeoutSeconds;
  private final double ocrMinConfidence;

  public DocumentTextExtractor(
      @Value("${app.ocr.enabled:true}") boolean ocrEnabled,
      @Value("${app.ocr.language:chi_sim+eng}") String ocrLanguage,
      @Value("${app.ocr.min-page-text-chars:20}") int minPageChars,
      @Value("${app.ocr.max-pages:100}") int maxOcrPages,
      @Value("${app.ocr.timeout-seconds:60}") int ocrTimeoutSeconds,
      @Value("${app.ocr.min-confidence:45}") double ocrMinConfidence) {
    this.ocrEnabled = ocrEnabled;
    this.ocrLanguage = ocrLanguage;
    this.minPageChars = Math.max(1, minPageChars);
    this.maxOcrPages = Math.max(1, maxOcrPages);
    this.ocrTimeoutSeconds = Math.max(5, ocrTimeoutSeconds);
    this.ocrMinConfidence = Math.max(0, Math.min(100, ocrMinConfidence));
  }

  public ExtractionResult extract(InputStream input, String originalName) throws Exception {
    if (originalName != null && originalName.toLowerCase().endsWith(".pdf")) {
      return extractPdf(input);
    }
    String text = normalize(new Tika().parseToString(input));
    List<PageText> pages = new ArrayList<PageText>();
    pages.add(new PageText(null, text, false));
    return new ExtractionResult(pages, false);
  }

  private ExtractionResult extractPdf(InputStream input) throws Exception {
    List<PageText> pages = new ArrayList<PageText>();
    boolean usedOcr = false;
    try (PDDocument pdf =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly())) {
      PDFTextStripper stripper = new PDFTextStripper();
      PDFRenderer renderer = ocrEnabled ? new PDFRenderer(pdf) : null;
      int ocrPages = 0;
      for (int pageIndex = 0; pageIndex < pdf.getNumberOfPages(); pageIndex++) {
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        String text = normalize(stripper.getText(pdf));
        boolean pageOcr = false;
        if (text.replaceAll("\\s+", "").length() < minPageChars && ocrEnabled) {
          if (ocrPages >= maxOcrPages) {
            throw new IllegalStateException("扫描版 PDF 超过单文档 OCR 页数上限 " + maxOcrPages);
          }
          BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200, ImageType.RGB);
          text = normalize(runTesseract(image));
          pageOcr = true;
          usedOcr = true;
          ocrPages++;
        }
        pages.add(new PageText(pageIndex + 1, text, pageOcr));
      }
    }
    return new ExtractionResult(pages, usedOcr);
  }

  private String runTesseract(BufferedImage image) throws Exception {
    Path imagePath = Files.createTempFile("docpilot-ocr-", ".png");
    Path outputBase = Files.createTempFile("docpilot-ocr-output-", "");
    Files.deleteIfExists(outputBase);
    Path textPath = outputBase.resolveSibling(outputBase.getFileName().toString() + ".txt");
    Path tsvPath = outputBase.resolveSibling(outputBase.getFileName().toString() + ".tsv");
    Path logPath = Files.createTempFile("docpilot-ocr-", ".log");
    try {
      ImageIO.write(image, "png", imagePath.toFile());
      Process process =
          new ProcessBuilder(
                  "tesseract",
                  imagePath.toString(),
                  outputBase.toString(),
                  "-l",
                  ocrLanguage,
                  "--psm",
                  "6",
                  "txt",
                  "tsv")
              // Never leave stderr/stdout attached to an unread pipe: a noisy
              // native process can otherwise fill the buffer and deadlock.
              .redirectOutput(logPath.toFile())
              .redirectErrorStream(true)
              .start();
      if (!process.waitFor(ocrTimeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("OCR 执行超时");
      }
      if (process.exitValue() != 0 || !Files.exists(textPath)) {
        String log = Files.exists(logPath)
            ? new String(Files.readAllBytes(logPath), StandardCharsets.UTF_8)
            : "";
        throw new IllegalStateException(
            "OCR 执行失败，请确认 Tesseract 与语言包已安装，exit="
                + process.exitValue()
                + ", detail="
                + log.substring(0, Math.min(300, log.length())));
      }
      double confidence = meanConfidence(tsvPath);
      if (confidence < ocrMinConfidence) {
        throw new IllegalStateException(
            "OCR 置信度低于质量门槛：confidence="
                + String.format(java.util.Locale.ROOT, "%.2f", confidence)
                + ", minimum="
                + ocrMinConfidence);
      }
      return new String(Files.readAllBytes(textPath), StandardCharsets.UTF_8);
    } finally {
      Files.deleteIfExists(imagePath);
      Files.deleteIfExists(textPath);
      Files.deleteIfExists(tsvPath);
      Files.deleteIfExists(logPath);
    }
  }

  static double meanConfidence(Path tsvPath) throws Exception {
    if (!Files.exists(tsvPath)) return 0;
    double sum = 0;
    int words = 0;
    for (String line : Files.readAllLines(tsvPath, StandardCharsets.UTF_8)) {
      String[] columns = line.split("\\t", -1);
      if (columns.length < 12 || columns[11].trim().isEmpty()) continue;
      try {
        double confidence = Double.parseDouble(columns[10]);
        if (confidence >= 0) {
          sum += confidence;
          words++;
        }
      } catch (NumberFormatException ignored) {
        // Header and malformed rows do not contribute to the confidence gate.
      }
    }
    return words == 0 ? 0 : sum / words;
  }

  private String normalize(String value) {
    return value == null
        ? ""
        : value.replace('\u0000', ' ')
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\r\\n?", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
  }

  public static class ExtractionResult {
    private final List<PageText> pages;
    private final boolean ocrUsed;

    ExtractionResult(List<PageText> pages, boolean ocrUsed) {
      this.pages = pages;
      this.ocrUsed = ocrUsed;
    }

    public List<PageText> getPages() {
      return pages;
    }

    public boolean isOcrUsed() {
      return ocrUsed;
    }
  }
}
