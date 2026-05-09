package com.example.esrag.utils;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class FileParser {

    @Data
    public static class ParsedResult {
        private String content;
        private int totalPages;
        
        public ParsedResult(String content, int totalPages) {
            this.content = content;
            this.totalPages = totalPages;
        }
    }

    public ParsedResult parseFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }

        byte[] fileBytes = file.getBytes();
        return parseFile(fileBytes, fileName);
    }

    public ParsedResult parseFile(byte[] fileBytes, String fileName) throws IOException {
        if (fileName == null) {
            throw new IOException("文件名不能为空");
        }

        String lowerCaseFileName = fileName.toLowerCase();

        if (lowerCaseFileName.endsWith(".pdf")) {
            return parsePdf(fileBytes);
        } else if (lowerCaseFileName.endsWith(".docx")) {
            return parseDocx(fileBytes);
        } else if (lowerCaseFileName.endsWith(".doc")) {
            return parseDoc(fileBytes);
        } else if (lowerCaseFileName.endsWith(".txt")) {
            return parseTxt(fileBytes);
        } else {
            throw new IOException("不支持的文件格式: " + fileName);
        }
    }

    private ParsedResult parsePdf(byte[] fileBytes) throws IOException {
        try (PDDocument document = PDDocument.load(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String content = stripper.getText(document);
            int pageCount = document.getNumberOfPages();
            return new ParsedResult(content, pageCount);
        }
    }

    private ParsedResult parseDocx(byte[] fileBytes) throws IOException {
        ZipSecureFile.setMinInflateRatio(-1.0d);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileBytes))) {
            StringBuilder text = new StringBuilder();
            document.getParagraphs().forEach(paragraph -> {
                if (paragraph.getText() != null && !paragraph.getText().isEmpty()) {
                    text.append(paragraph.getText()).append("\n");
                }
            });
            
            Integer pages = document.getProperties().getExtendedProperties()
                .getUnderlyingProperties().getPages();
            int pageCount = pages != null ? pages : 1;
            
            return new ParsedResult(text.toString(), pageCount);
        }
    }

    private ParsedResult parseDoc(byte[] fileBytes) throws IOException {
        ZipSecureFile.setMinInflateRatio(-1.0d);
        try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(fileBytes))) {
            String content = extractor.getText();
            Integer pageCount = extractor.getSummaryInformation().getPageCount();
            return new ParsedResult(content, pageCount != null ? pageCount : 1);
        }
    }

    private ParsedResult parseTxt(byte[] fileBytes) throws IOException {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        long lineCount = content.lines().count();
        return new ParsedResult(content, lineCount > 0 ? (int) lineCount : 1);
    }
}

