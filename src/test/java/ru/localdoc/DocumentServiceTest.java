package ru.localdoc;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.localdoc.document.*;
import ru.localdoc.domain.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {
    @TempDir Path dir;
    DocumentService documents=new DocumentService();
    static Stream<Object[]> formats() { return Stream.of(DocumentFormat.values()).flatMap(a -> Stream.of(DocumentFormat.values()).map(b -> new Object[]{a,b})); }
    DocumentHandler handler(DocumentFormat format) { return switch (format) { case DOCX -> new DocxHandler();case ODT -> new OdtHandler();case TXT -> new TxtHandler(); }; }
    @ParameterizedTest @MethodSource("formats") void convertsAllNineCombinationsWithoutChangingSource(DocumentFormat input,DocumentFormat output) throws Exception {
        DocumentModel model=DocumentModel.fromText("  Привет, мир!\tТест 😀\n\nВторая строка  \n");
        Path original=dir.resolve("original."+input.extension());handler(input).write(model,original,StandardCharsets.UTF_8);
        byte[] before=Files.readAllBytes(original);
        SourceDocument source=documents.read(original,StandardCharsets.UTF_8,20*1024*1024);
        assertEquals(model.text(),source.document().text());
        Path destination=dir.resolve("result."+output.extension());
        documents.write(source,model,destination,output,StandardCharsets.UTF_8,false);
        assertEquals(model.text(),documents.read(destination,StandardCharsets.UTF_8,20*1024*1024).document().text());
        assertArrayEquals(before,Files.readAllBytes(original));
        try (var files=Files.list(dir)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".localdoc-"))); }
    }
    @Test void refusesOriginalAndItsAliases() throws Exception {
        Path original=dir.resolve("original.txt");Files.writeString(original,"Оригинал");
        var source=documents.read(original,StandardCharsets.UTF_8,1000);
        assertEquals("EXPORT_SOURCE",assertThrows(AppException.class,() -> documents.write(source,DocumentModel.fromText("Другой текст"),original,DocumentFormat.TXT,StandardCharsets.UTF_8,true)).code());
        Path hard=dir.resolve("hard.txt");Files.createLink(hard,original);
        assertThrows(AppException.class,() -> documents.write(source,source.document(),hard,DocumentFormat.TXT,StandardCharsets.UTF_8,true));
        Path link=dir.resolve("link.txt");Files.createSymbolicLink(link,original);
        assertThrows(AppException.class,() -> documents.write(source,source.document(),link,DocumentFormat.TXT,StandardCharsets.UTF_8,true));
        assertEquals("Оригинал",Files.readString(original));
    }
    @Test void refusesUnconfirmedOverwriteAndAllowsConfirmedSeparateFile() throws Exception {
        Path original=dir.resolve("original.txt");Files.writeString(original,"Оригинал");
        var source=documents.read(original,StandardCharsets.UTF_8,1000);
        Path target=dir.resolve("result.txt");Files.writeString(target,"Старое содержимое");
        assertEquals("EXPORT_EXISTS",assertThrows(AppException.class,() -> documents.write(source,source.document(),target,DocumentFormat.TXT,StandardCharsets.UTF_8,false)).code());
        assertEquals("Старое содержимое",Files.readString(target));
        documents.write(source,source.document(),target,DocumentFormat.TXT,StandardCharsets.UTF_8,true);
        assertEquals("Оригинал",Files.readString(target));
    }
    @Test void encodingErrorsAreExplicitAndAlternativeEncodingWorks() throws Exception {
        Path file=dir.resolve("text.txt");Files.write(file,"Привет".getBytes(Charset.forName("windows-1251")));
        assertEquals("IMPORT_ENCODING",assertThrows(AppException.class,() -> documents.read(file,StandardCharsets.UTF_8,1000)).code());
        assertEquals("Привет",documents.read(file,Charset.forName("windows-1251"),1000).document().text());
        Files.writeString(file,"\ufeffС BOM");assertEquals("С BOM",documents.read(file,StandardCharsets.UTF_8,1000).document().text());
    }
    @Test void unsupportedElementsAreReported() throws Exception {
        Path file=dir.resolve("table.docx");
        try (var doc=new XWPFDocument();var out=Files.newOutputStream(file)) {
            doc.createParagraph().createRun().setText("Основной текст");doc.createTable().getRow(0).getCell(0).setText("Ячейка");doc.write(out);
        }
        var read=documents.read(file,StandardCharsets.UTF_8,100000);
        assertEquals("Основной текст",read.document().text());assertTrue(read.warnings().stream().anyMatch(s -> s.contains("Таблицы")));
    }
    @Test void rejectsEmptyCorruptAndOversizedFiles() throws Exception {
        Path empty=dir.resolve("empty.txt");Files.writeString(empty," \n ");
        assertEquals("IMPORT_EMPTY",assertThrows(AppException.class,() -> documents.read(empty,StandardCharsets.UTF_8,1000)).code());
        Path corrupt=dir.resolve("fake.docx");Files.writeString(corrupt,"Это не DOCX");
        assertEquals("IMPORT_FORMAT",assertThrows(AppException.class,() -> documents.read(corrupt,StandardCharsets.UTF_8,1000)).code());
        assertEquals("IMPORT_SIZE",assertThrows(AppException.class,() -> documents.read(corrupt,StandardCharsets.UTF_8,2)).code());
    }
    @Test void failedExportLeavesExistingFileAndNoTemporaryFile() throws Exception {
        Path original=dir.resolve("original.txt");Files.writeString(original,"Оригинал 😀");
        var source=documents.read(original,StandardCharsets.UTF_8,1000);
        Path target=dir.resolve("target.txt");Files.writeString(target,"До записи");
        assertThrows(AppException.class,() -> documents.write(source,source.document(),target,DocumentFormat.TXT,StandardCharsets.US_ASCII,true));
        assertEquals("До записи",Files.readString(target));
        try (var files=Files.list(dir)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".localdoc-"))); }
    }
    @Test void detectsExternalSourceChange() throws Exception {
        Path original=dir.resolve("original.txt");Files.writeString(original,"Сначала");
        var source=documents.read(original,StandardCharsets.UTF_8,1000);assertFalse(documents.sourceChanged(source));
        Files.writeString(original,"Потом");assertTrue(documents.sourceChanged(source));
        assertEquals("Сначала",source.document().text());
    }
}
