package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DocumentTextExtractorTest {
  @Test
  void preservesNativePdfPageNumbersWithoutOcr() throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PDDocument pdf = new PDDocument()) {
      addPage(pdf, "Policy page one");
      addPage(pdf, "Policy page two");
      pdf.save(bytes);
    }
    DocumentTextExtractor extractor = new DocumentTextExtractor(false, "eng", 5, 10, 10, 45);

    DocumentTextExtractor.ExtractionResult result =
        extractor.extract(new ByteArrayInputStream(bytes.toByteArray()), "policy.pdf");

    assertEquals(2, result.getPages().size());
    assertEquals(Integer.valueOf(1), result.getPages().get(0).getPageNo());
    assertEquals(Integer.valueOf(2), result.getPages().get(1).getPageNo());
    assertTrue(result.getPages().get(0).getText().contains("page one"));
    assertTrue(result.getPages().get(1).getText().contains("page two"));
    assertFalse(result.isOcrUsed());
  }

  @Test
  void computesOcrConfidenceFromWordRowsOnly() throws Exception {
    Path tsv = Files.createTempFile("docpilot-ocr-confidence-", ".tsv");
    try {
      Files.write(
          tsv,
          ("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
                  + "5\t1\t1\t1\t1\t1\t0\t0\t1\t1\t90.0\tPolicy\n"
                  + "5\t1\t1\t1\t1\t2\t0\t0\t1\t1\t70.0\tRule\n")
              .getBytes(StandardCharsets.UTF_8));
      assertEquals(80.0, DocumentTextExtractor.meanConfidence(tsv), 0.001);
    } finally {
      Files.deleteIfExists(tsv);
    }
  }

  @Test
  void preservesPlainTextLineBreaks() throws Exception {
    DocumentTextExtractor extractor = new DocumentTextExtractor(false, "eng", 5, 10, 10, 45);
    DocumentTextExtractor.ExtractionResult result =
        extractor.extract(
            new ByteArrayInputStream("first line\r\nsecond line".getBytes(StandardCharsets.UTF_8)),
            "policy.txt");

    assertTrue(result.getPages().get(0).getText().contains("first line\nsecond line"));
    assertFalse(result.getPages().get(0).getText().contains("first linensecond"));
  }

  private void addPage(PDDocument pdf, String text) throws Exception {
    PDPage page = new PDPage();
    pdf.addPage(page);
    try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
      content.beginText();
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
      content.newLineAtOffset(72, 720);
      content.showText(text);
      content.endText();
    }
  }
}
