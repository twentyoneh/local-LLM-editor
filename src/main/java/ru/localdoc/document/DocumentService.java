package ru.localdoc.document;

import ru.localdoc.domain.*;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

public final class DocumentService {
    private final Map<DocumentFormat, DocumentHandler> handlers;
    public DocumentService() { this(Map.of(DocumentFormat.TXT,new TxtHandler(),DocumentFormat.DOCX,new DocxHandler(),DocumentFormat.ODT,new OdtHandler())); }
    public DocumentService(Map<DocumentFormat, DocumentHandler> handlers) { this.handlers = Map.copyOf(handlers); }

    public SourceDocument read(Path path, Charset encoding, long maxBytes) {
        try {
            Path real = path.toRealPath();
            if (!Files.isRegularFile(real) || Files.size(real) > maxBytes) throw new AppException("IMPORT_SIZE", "Файл недоступен или превышает установленный лимит размера.");
            DocumentFormat format = DocumentFormat.fromPath(path);
            if (format != DocumentFormat.TXT) inspectArchive(real, format);
            String before = fingerprint(real);
            var imported = handler(format).read(real,encoding);
            if (imported.document().isBlank()) throw new AppException("IMPORT_EMPTY", "В файле нет поддерживаемого непустого текста.");
            if (!fingerprint(real).equals(before)) throw new AppException("IMPORT_CHANGED", "Файл изменился во время чтения. Откройте его заново.");
            return new SourceDocument(real, format, imported.document(), encoding, before, imported.warnings());
        } catch (AppException ex) { throw ex; }
        catch (CharacterCodingException ex) { throw new AppException("IMPORT_ENCODING", "Файл не удалось прочитать в кодировке " + encoding + ". Выберите другую кодировку.", ex); }
        catch (Exception ex) { throw new AppException("IMPORT_FORMAT", "Не удалось открыть документ. Проверьте формат, целостность файла и доступ к нему.", ex); }
    }
    public boolean sourceChanged(SourceDocument source) {
        try { return !fingerprint(source.path()).equals(source.fingerprint()); }
        catch (Exception ex) { return true; }
    }
    public Path write(SourceDocument source, DocumentModel result, Path destination, DocumentFormat format,
                      Charset outputEncoding, boolean allowOverwrite) {
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = null;
        try {
            if (DocumentFormat.fromPath(target) != format) throw new AppException("EXPORT_FORMAT", "Расширение файла не соответствует выбранному формату.");
            if (sameFile(source.path(),target)) throw new AppException("EXPORT_SOURCE", "Нельзя перезаписать исходный документ. Выберите другое имя.");
            if (Files.exists(target,LinkOption.NOFOLLOW_LINKS) && !allowOverwrite) throw new AppException("EXPORT_EXISTS", "Файл уже существует. Подтвердите замену или выберите другое имя.");
            temporary = Files.createTempFile(target.getParent(),".localdoc-","." + format.extension());
            handler(format).write(result,temporary,outputEncoding);
            if (!handler(format).read(temporary,outputEncoding).document().text().equals(result.text()))
                throw new AppException("EXPORT_VERIFY", "Проверка сохранённого документа выявила изменение текста.");
            // Check identity again immediately before committing the new file.
            if (sameFile(source.path(),target)) throw new AppException("EXPORT_SOURCE", "Выбранный путь указывает на исходный документ.");
            if (!allowOverwrite) {
                // A same-directory hard link publishes the complete file without replacing a racing target.
                // The normal move fallback also refuses to replace an existing destination.
                try { Files.createLink(target,temporary); Files.delete(temporary); }
                catch (FileAlreadyExistsException ex) { throw ex; }
                catch (UnsupportedOperationException | FileSystemException ex) { Files.move(temporary,target); }
            } else {
                try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { throw new AppException("EXPORT_ATOMIC", "Файловая система не поддерживает безопасную замену. Выберите новое имя.",ex); }
            }
            return target;
        } catch (AppException ex) { throw ex; }
        catch (Exception ex) { throw new AppException("EXPORT_FAILED", "Не удалось сохранить файл. Проверьте путь, свободное место и права доступа.",ex); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
    private DocumentHandler handler(DocumentFormat format) {
        var handler = handlers.get(format);
        if (handler == null) throw new AppException("IMPORT_FORMAT", "Нет обработчика формата " + format);
        return handler;
    }
    private static boolean sameFile(Path source, Path target) throws IOException {
        return source.toAbsolutePath().normalize().equals(target) || (Files.exists(target) && Files.isSameFile(source,target));
    }
    private static String fingerprint(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (var in=Files.newInputStream(path)) { byte[] buffer=new byte[16384]; int n; while ((n=in.read(buffer))!=-1) digest.update(buffer,0,n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private void inspectArchive(Path path, DocumentFormat format) throws IOException {
        long total = 0;
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.size() > 10000) throw new IOException("Слишком много элементов архива");
            var entries=zip.entries(); byte[] buffer=new byte[8192];
            while (entries.hasMoreElements()) {
                var entry=entries.nextElement(); if (entry.isDirectory()) continue;
                try (var in=zip.getInputStream(entry)) {
                    int n; while ((n=in.read(buffer))!=-1) { total+=n; if (total>100L*1024*1024) throw new IOException("Слишком большой распакованный документ"); }
                }
            }
            if (format == DocumentFormat.DOCX && (zip.getEntry("word/document.xml")==null || zip.getEntry("[Content_Types].xml")==null)) throw new IOException("Это не DOCX");
            if (format == DocumentFormat.ODT) {
                if (zip.getEntry("content.xml")==null || zip.getEntry("mimetype")==null) throw new IOException("Это не ODT");
                try (var in=zip.getInputStream(zip.getEntry("mimetype"))) {
                    if (!new String(in.readNBytes(100),java.nio.charset.StandardCharsets.US_ASCII).trim().equals("application/vnd.oasis.opendocument.text")) throw new IOException("Неверный тип ODT");
                }
            }
        }
    }
}
