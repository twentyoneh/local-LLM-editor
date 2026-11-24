package ru.localdoc.document;

import ru.localdoc.domain.DocumentModel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

public interface DocumentHandler {
    record Imported(DocumentModel document, List<String> warnings) { }
    Imported read(Path path, Charset encoding) throws Exception;
    void write(DocumentModel document, Path path, Charset encoding) throws Exception;
}
