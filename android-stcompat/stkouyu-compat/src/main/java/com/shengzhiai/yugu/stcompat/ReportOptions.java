// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

/** Options of one {@link ReportClient#getReport} call (SDK 2.2.0); null options use the defaults. */
public final class ReportOptions {
    private long waitForFinalMs;

    /**
     * How long to wait for the AI final draft. 0 (default): one lookup. Greater than 0: while the
     * answer's {@code reportStatus} is {@code pending} or {@code partial} (SDK 2.3.0: summary and
     * suggestions ready, comments per dimension still written), look up again every 1000 ms until it
     * is neither or the next lookup would start after this time; the latest answer is returned, a
     * pending or partial one included. Chinese drafts are usually ready about 6 s after the
     * evaluation (p90 about 11 s), English about 16 s.
     *
     * @throws IllegalArgumentException for a negative value
     */
    public ReportOptions setWaitForFinalMs(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("waitForFinalMs 不能小于 0");
        }
        this.waitForFinalMs = ms;
        return this;
    }

    public long getWaitForFinalMs() {
        return waitForFinalMs;
    }
}
