package com.kean.common;

public final class Pages {

    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_SIZE = 20L;
    public static final long MAX_SIZE = 50L;
    public static final long CATALOG_MAX_SIZE = 500L;

    private Pages() {
    }

    public static long page(Long page) {
        return page == null || page < 1 ? DEFAULT_PAGE : page;
    }

    public static long size(Long size) {
        return size(size, MAX_SIZE);
    }

    public static long size(Long size, long max) {
        return size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, max);
    }
}
