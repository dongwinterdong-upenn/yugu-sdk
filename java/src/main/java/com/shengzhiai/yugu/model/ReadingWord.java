package com.shengzhiai.yugu.model;

import java.util.Objects;

/**
 * One unit of the reference text in a reading progress frame ({@link StreamPartial#getProgress()}): a
 * character for Chinese (punctuation and whitespace removed), a word for English.
 *
 * <p>Within a round {@code read} and {@code matched} only change from false to true, and {@code matched}
 * implies {@code read}. {@code read} without {@code matched} means the reading passed the unit but the
 * partial recognition did not match it: misread, skipped or misrecognized; the final result decides.
 *
 * @since 2.1.0
 */
public final class ReadingWord {
    private final String word;
    private final boolean read;
    private final boolean matched;

    /**
     * @param word    character or word taken from the reference text, null is stored as ""
     * @param read    the reading position has passed this unit
     * @param matched the partial recognition matched this unit
     */
    public ReadingWord(String word, boolean read, boolean matched) {
        this.word = word == null ? "" : word;
        this.read = read;
        this.matched = matched;
    }

    /** @return the character or word, never null */
    public String getWord() {
        return word;
    }

    /** @return true when the reading position has passed this unit */
    public boolean isRead() {
        return read;
    }

    /** @return true when the partial recognition matched this unit */
    public boolean isMatched() {
        return matched;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReadingWord)) {
            return false;
        }
        ReadingWord w = (ReadingWord) o;
        return read == w.read && matched == w.matched && word.equals(w.word);
    }

    @Override
    public int hashCode() {
        return Objects.hash(word, read, matched);
    }

    @Override
    public String toString() {
        return "ReadingWord{" + word + ", read=" + read + ", matched=" + matched + '}';
    }
}
