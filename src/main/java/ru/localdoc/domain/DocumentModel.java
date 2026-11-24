package ru.localdoc.domain;

import java.util.Arrays;
import java.util.List;

public record DocumentModel(List<String> paragraphs) {
    public DocumentModel { paragraphs = List.copyOf(paragraphs); }
    public static DocumentModel fromText(String text) {
        return new DocumentModel(Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
    }
    public String text() { return String.join("\n", paragraphs); }
    public boolean isBlank() { return text().isBlank(); }
}
