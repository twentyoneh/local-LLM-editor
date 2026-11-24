package ru.localdoc.domain;

public final class AppException extends RuntimeException {
    private final String code;
    public AppException(String code, String message) { super(message); this.code = code; }
    public AppException(String code, String message, Throwable cause) { super(message, cause); this.code = code; }
    public String code() { return code; }
}
