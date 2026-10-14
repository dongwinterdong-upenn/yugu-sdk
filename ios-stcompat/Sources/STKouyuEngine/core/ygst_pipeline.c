/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Pipelined evaluation (2.3.0, YuguCompat.maxPendingEvaluations): the stored value of the switch, the
 * busy rule of a start and the log lines, in the key=value form of the Android shim. The Objective-C
 * engine (KYTestEngine.m) keeps the recording session and the sessions waiting for their results, and
 * asks here what a start does.
 */
#include "ygst_core.h"

int ygst_pipe_clamp(long long n) {
    if (n <= 0) return 0;
    if (n > YGST_PIPE_MAX_PENDING) return YGST_PIPE_MAX_PENDING;
    return (int)n;
}

int ygst_pipe_hands_over(int max_pending) {
    return max_pending > 0;
}

ygst_pipe_decision ygst_pipe_decide_start(int max_pending, int session_active, int session_stopping, int pending) {
    int cap = max_pending > 0 ? max_pending : 0;
    if (session_active) {
        /* one at a time, the session is busy until its result; pipelined, a stop that is being handled
         * is finished first, any other state (starting, recording, paused, waiting for stopEngine) is busy */
        return (cap > 0 && session_stopping) ? YGST_PIPE_FINISH_STOP : YGST_PIPE_BUSY_SESSION;
    }
    /* up to cap evaluations wait for their results while the new session records */
    return pending > cap ? YGST_PIPE_BUSY_PENDING : YGST_PIPE_START;
}

/* " pending=<p> maxPendingEvaluations=<n>" */
static void put_counts(ygst_buf *out, int pending, int max_pending) {
    ygst_buf_append_str(out, "pending=");
    ygst_buf_append_int(out, pending);
    ygst_buf_append_str(out, " maxPendingEvaluations=");
    ygst_buf_append_int(out, max_pending);
}

int ygst_pipe_start_line(const char *token_id, int pending, int max_pending, int audio_path, ygst_buf *out) {
    if (!out) return -1;
    ygst_buf_append_str(out, "pipelined evaluation tokenId=");
    ygst_buf_append_str(out, token_id && token_id[0] ? token_id : "-");
    ygst_buf_append_char(out, ' ');
    put_counts(out, pending, max_pending);
    if (audio_path) ygst_buf_append_str(out, " (audioPath)");
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_pipe_busy_line(int pending, int max_pending, ygst_buf *out) {
    if (!out) return -1;
    ygst_buf_append_str(out, "start rejected: engine is busy (");
    put_counts(out, pending, max_pending);
    ygst_buf_append_char(out, ')');
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}

int ygst_pipe_setting_line(int old_value, int new_value, ygst_buf *out) {
    if (!out) return -1;
    ygst_buf_append_str(out, new_value > 0 ? "pipelined evaluation on: maxPendingEvaluations="
                                           : "pipelined evaluation off: maxPendingEvaluations=");
    ygst_buf_append_int(out, new_value);
    ygst_buf_append_str(out, " (was ");
    ygst_buf_append_int(out, old_value);
    ygst_buf_append_char(out, ')');
    ygst_buf_cstr(out);
    return out->oom ? -1 : 0;
}
