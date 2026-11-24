package ru.localdoc.domain;

import java.nio.file.Path;
import java.util.Locale;

public enum DocumentFormat {
    DOCX, ODT, TXT;
    public String extension() { return name().toLowerCase(Locale.ROOT); }
    public static DocumentFormat fromPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            try { return valueOf(name.substring(dot + 1).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
        }
        throw new AppException("IMPORT_FORMAT", "Поддерживаются только файлы DOCX, ODT и TXT.");
    }
}
