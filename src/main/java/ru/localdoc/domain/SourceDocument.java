package ru.localdoc.domain;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

public record SourceDocument(Path path, DocumentFormat format, DocumentModel document,
                             Charset encoding, String fingerprint, List<String> warnings) {
    public SourceDocument { warnings = List.copyOf(warnings); }
}
