package ru.localdoc.processing;

public record WorkUnit(int id, int start, int end, String target, String context) { }
