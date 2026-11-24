package ru.localdoc.document;

import ru.localdoc.domain.DocumentModel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStreamWriter;
import java.util.List;

public final class TxtHandler implements DocumentHandler {
    @Override public Imported read(Path path, Charset encoding) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        String text = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\ufeff")) text = text.substring(1);
        return new Imported(DocumentModel.fromText(text), List.of());
    }
    @Override public void write(DocumentModel document, Path path, Charset encoding) throws Exception {
        try (var out = new OutputStreamWriter(Files.newOutputStream(path), encoding.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            out.write(document.text());
        }
    }
}
