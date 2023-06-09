package com.matyrobbrt.metabase;

public class StatusCodeException extends RuntimeException {
    private final int code;
    public StatusCodeException(int code, String path) {
        super("Encountered non-200 status code: " + code + " in path: " + path);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
