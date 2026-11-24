package ru.localdoc.document;

import org.apache.poi.xwpf.usermodel.*;
import ru.localdoc.domain.DocumentModel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DocxHandler implements DocumentHandler {
    @Override public Imported read(Path path, Charset ignored) throws Exception {
        try (var in = Files.newInputStream(path); var doc = new XWPFDocument(in)) {
            List<String> warnings = new ArrayList<>();
            if (!doc.getTables().isEmpty()) warnings.add("Таблицы не входят в текстовый импорт.");
            if (!doc.getAllPictures().isEmpty()) warnings.add("Изображения не переносятся в результат.");
            if (!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty() || !doc.getFootnotes().isEmpty() || !doc.getEndnotes().isEmpty())
                warnings.add("Колонтитулы и сноски не входят в текстовый импорт.");
            if (doc.getParagraphs().stream().anyMatch(p -> p.getNumID() != null || (p.getStyle() != null && !p.getStyle().equalsIgnoreCase("Normal")) || p.getRuns().stream().anyMatch(r -> r.getCTR().isSetRPr())))
                warnings.add("Стили, нумерация и оформление будут заменены обычными текстовыми абзацами.");
            // Text boxes, fields and tracked revisions require a dedicated rich-document model.
            String xml = doc.getDocument().xmlText();
            if (xml.contains("txbxContent") || xml.contains("<w:ins") || xml.contains("<w:del") || xml.contains("fldChar") || xml.contains("<w:sdt") || xml.contains("altChunk"))
                warnings.add("Есть текстовые поля, исправления или вычисляемые поля: поддерживается только основной текст.");
            return new Imported(new DocumentModel(doc.getParagraphs().stream().map(XWPFParagraph::getText).toList()), warnings);
        }
    }
    @Override public void write(DocumentModel model, Path path, Charset ignored) throws Exception {
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(path)) {
            for (String text : model.paragraphs()) {
                var run = doc.createParagraph().createRun();
                run.setFontFamily("Arial"); run.setFontSize(11);
                // Tabs are stored as Word tab elements rather than ambiguous literal XML characters.
                String[] pieces = text.split("\t", -1);
                for (int i = 0; i < pieces.length; i++) { if (i > 0) run.addTab(); run.setText(pieces[i]); }
            }
            doc.write(out);
        }
    }
}
