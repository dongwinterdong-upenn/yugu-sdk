package com.shengzhiai.yugu.errors;

/**
 * Which table of spec/errors.json a code was resolved from. Codes 1004 and 1005 exist both as
 * platform error codes and as audio warning codes, so the table matters for the retry decision.
 */
public enum CodeKind {
    /** No business code: the error is classified by HTTP status or by the local cause. */
    NONE,
    /** A platform error code from the {@code errors} table. */
    ERROR,
    /** An audio quality warning code from the {@code warnings} table. */
    WARNING,
    /** An SDK local code from the {@code local} table. */
    LOCAL,
    /** A non-zero code that no table lists. */
    UNLISTED
}
