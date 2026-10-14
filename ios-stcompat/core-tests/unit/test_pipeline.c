/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * Pipelined evaluation (2.3.0, YuguCompat.maxPendingEvaluations): the stored value of the switch, the
 * busy rule of a start against an independent statement of BRIEF 2.3.0 point 2, the scenarios of the
 * brief replayed on a model of the engine's bookkeeping, and the log lines.
 */
#include <limits.h>

#include "ygt.h"

static void test_pipe_clamp(void) {
    CHECK_INT(ygst_pipe_clamp(0), 0);
    CHECK_INT(ygst_pipe_clamp(-1), 0);
    CHECK_INT(ygst_pipe_clamp(-100), 0);
    CHECK_INT(ygst_pipe_clamp(LLONG_MIN), 0);
    CHECK_INT(ygst_pipe_clamp(1), 1);
    CHECK_INT(ygst_pipe_clamp(3), 3);
    CHECK_INT(ygst_pipe_clamp(15), 15);
    CHECK_INT(ygst_pipe_clamp(16), 16);
    CHECK_INT(ygst_pipe_clamp(17), 16);
    CHECK_INT(ygst_pipe_clamp(1000), 16);
    CHECK_INT(ygst_pipe_clamp(LLONG_MAX), 16);
    CHECK_INT(YGST_PIPE_MAX_PENDING, 16);
}

static void test_pipe_hands_over(void) {
    CHECK_INT(ygst_pipe_hands_over(0), 0); /* one at a time: the session stays until its result */
    CHECK_INT(ygst_pipe_hands_over(-1), 0);
    CHECK_INT(ygst_pipe_hands_over(1), 1);
    CHECK_INT(ygst_pipe_hands_over(16), 1);
}

/* BRIEF 2.3.0 point 1 and 2 written out independently: one at a time, any session makes a start busy;
 * pipelined, a start is busy while a session records (or is not handed over yet), and while more than n
 * sessions wait for their results, so that up to n evaluate in the background while the next one records. */
static ygst_pipe_decision expected(int n, int active, int stopping, int pending) {
    if (n <= 0) return (active || pending > 0) ? (active ? YGST_PIPE_BUSY_SESSION : YGST_PIPE_BUSY_PENDING)
                                               : YGST_PIPE_START;
    if (active) return stopping ? YGST_PIPE_FINISH_STOP : YGST_PIPE_BUSY_SESSION;
    if (pending > n) return YGST_PIPE_BUSY_PENDING;
    return YGST_PIPE_START;
}

static void test_pipe_decide_table(void) {
    static const int settings[] = {-1, 0, 1, 2, 3, 15, 16};
    size_t i;
    int active, stopping, pending;
    for (i = 0; i < sizeof settings / sizeof settings[0]; i++) {
        for (active = 0; active <= 1; active++) {
            for (stopping = 0; stopping <= active; stopping++) {
                for (pending = 0; pending <= 18; pending++) {
                    ygst_pipe_decision d = ygst_pipe_decide_start(settings[i], active, stopping, pending);
                    ygst_pipe_decision want = expected(settings[i], active, stopping, pending);
                    if (d != want) {
                        char what[160];
                        snprintf(what, sizeof what, "decide(n=%d, active=%d, stopping=%d, pending=%d) = %d, want %d",
                                 settings[i], active, stopping, pending, (int)d, (int)want);
                        ygt_fail(__FILE__, __LINE__, what);
                    }
                    ygt_checks++;
                }
            }
        }
    }
    /* the corners named in the brief */
    CHECK_INT(ygst_pipe_decide_start(0, 1, 0, 0), YGST_PIPE_BUSY_SESSION); /* default: busy until the result */
    CHECK_INT(ygst_pipe_decide_start(0, 1, 1, 0), YGST_PIPE_BUSY_SESSION); /* default: a stop changes nothing */
    CHECK_INT(ygst_pipe_decide_start(0, 0, 0, 0), YGST_PIPE_START);
    CHECK_INT(ygst_pipe_decide_start(1, 0, 0, 1), YGST_PIPE_START);        /* n = 1: one pending, the next records */
    CHECK_INT(ygst_pipe_decide_start(1, 0, 0, 2), YGST_PIPE_BUSY_PENDING); /* two pending */
    CHECK_INT(ygst_pipe_decide_start(1, 1, 0, 1), YGST_PIPE_BUSY_SESSION); /* one pending, one recording */
    CHECK_INT(ygst_pipe_decide_start(3, 1, 1, 2), YGST_PIPE_FINISH_STOP);
    CHECK_INT(ygst_pipe_decide_start(16, 0, 0, 16), YGST_PIPE_START);
    CHECK_INT(ygst_pipe_decide_start(16, 0, 0, 17), YGST_PIPE_BUSY_PENDING);
}

/* The bookkeeping of KYTestEngine.m reduced to counters: the engine's session (active, stopping) with the
 * setting it started with, and the sessions handed over that wait for their results. */
typedef struct {
    int n;          /* YuguCompat.maxPendingEvaluations now, read by the next start */
    int active;     /* the engine's session, not handed over */
    int active_n;   /* the setting that session started with */
    int stopping;   /* its stop is being handled */
    int pending;    /* handed over, results not delivered */
    int started;    /* sessions started so far */
} engine_model;

/* the stop of the engine's session was handled (finalizeCapture): pipelined it is handed over */
static void stop_handled(engine_model *m) {
    if (!m->active) return;
    m->stopping = 0;
    if (ygst_pipe_hands_over(m->active_n)) {
        m->active = 0;
        m->pending++;
    }
}

/* beginSession: the decision, with FINISH_STOP carried out as the engine does it */
static ygst_pipe_decision start(engine_model *m, int file) {
    ygst_pipe_decision d = ygst_pipe_decide_start(m->n, m->active, m->stopping, m->pending);
    if (d == YGST_PIPE_FINISH_STOP) {
        stop_handled(m);
        d = ygst_pipe_decide_start(m->n, m->active, m->stopping, m->pending);
        CHECK(d != YGST_PIPE_FINISH_STOP);
    }
    if (d == YGST_PIPE_START) {
        m->active = 1;
        m->active_n = m->n;
        m->stopping = 0;
        m->started++;
        if (file) stop_handled(m); /* audioPath: evaluated from the start, like existsAudioTrans */
    }
    return d;
}

static void stop_requested(engine_model *m) {
    if (m->active) m->stopping = 1;
}

/* the result of the engine's session (one at a time) or of one pending session */
static void result_of_active(engine_model *m) {
    m->active = 0;
    m->stopping = 0;
}

static void result_of_pending(engine_model *m) {
    if (m->pending > 0) m->pending--;
}

static void cancel_all(engine_model *m) {
    m->active = 0;
    m->stopping = 0;
    m->pending = 0;
}

static void test_pipe_scenarios(void) {
    engine_model m;
    /* default off: start A, stop, start B before A's result: refused as before 2.3.0, before or after
     * the stop was handled; after A's result the next start works */
    memset(&m, 0, sizeof m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);
    stop_requested(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_SESSION);
    stop_handled(&m);
    CHECK_INT(m.active, 1); /* one at a time: still the engine's session while it is evaluated */
    CHECK_INT(m.pending, 0);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_SESSION);
    result_of_active(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);

    /* n = 3: A, B and C recorded back to back, each started right after the previous stop (still being
     * handled, or handled); D records while three wait; E is refused until one result arrives */
    memset(&m, 0, sizeof m);
    m.n = 3;
    CHECK_INT(start(&m, 0), YGST_PIPE_START);  /* A */
    stop_requested(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);  /* B: A's stop is finished first */
    CHECK_INT(m.pending, 1);
    stop_requested(&m);
    stop_handled(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);  /* C */
    CHECK_INT(m.pending, 2);
    stop_requested(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);  /* D, three waiting */
    CHECK_INT(m.pending, 3);
    stop_requested(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_PENDING); /* E: D's stop was handled for the decision */
    CHECK_INT(m.pending, 4);
    CHECK_INT(m.active, 0);
    result_of_pending(&m); /* C answered first */
    CHECK_INT(start(&m, 0), YGST_PIPE_START);  /* E */
    CHECK_INT(m.started, 5);

    /* cap n = 1: a third session is refused while one is pending and one recording, and while two are
     * pending; accepted again after a result */
    memset(&m, 0, sizeof m);
    m.n = 1;
    CHECK_INT(start(&m, 0), YGST_PIPE_START); /* A */
    stop_requested(&m);
    stop_handled(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START); /* B, A pending */
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_SESSION); /* C while B records */
    stop_requested(&m);
    stop_handled(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_PENDING); /* C while A and B wait */
    result_of_pending(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START); /* C after A's result, B pending */
    CHECK_INT(m.pending, 1);

    /* audioPath counts as a pending session the same way: n = 1, two files wait, a third start is refused */
    memset(&m, 0, sizeof m);
    m.n = 1;
    CHECK_INT(start(&m, 1), YGST_PIPE_START);
    CHECK_INT(m.active, 0);
    CHECK_INT(m.pending, 1);
    CHECK_INT(start(&m, 1), YGST_PIPE_START);
    CHECK_INT(start(&m, 1), YGST_PIPE_BUSY_PENDING);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_PENDING);
    result_of_pending(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START); /* a recording while one file is evaluated */
    CHECK_INT(start(&m, 1), YGST_PIPE_BUSY_SESSION);

    /* cancel ends the recording session and every pending one: the next start works */
    cancel_all(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);

    /* the setting is read at each start: back to 0 while two wait, nothing starts until both answered;
     * a session started one at a time stays the engine's session after its stop */
    memset(&m, 0, sizeof m);
    m.n = 2;
    CHECK_INT(start(&m, 0), YGST_PIPE_START);
    stop_requested(&m);
    stop_handled(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);
    stop_requested(&m);
    stop_handled(&m);
    m.n = 0;
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_PENDING);
    result_of_pending(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_PENDING);
    result_of_pending(&m);
    CHECK_INT(start(&m, 0), YGST_PIPE_START);
    stop_requested(&m);
    m.n = 3; /* switched on while a one-at-a-time session stops: that session keeps its rule */
    CHECK_INT(start(&m, 0), YGST_PIPE_BUSY_SESSION);
    CHECK_INT(m.active, 1);
    CHECK_INT(m.pending, 0);
}

static void test_pipe_lines(void) {
    ygst_buf b;
    ygst_buf_init(&b);
    CHECK_INT(ygst_pipe_start_line("3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c", 2, 3, 0, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation tokenId=3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c pending=2 "
                                 "maxPendingEvaluations=3");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_start_line("9a8b", 0, 1, 1, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation tokenId=9a8b pending=0 maxPendingEvaluations=1 (audioPath)");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_start_line(NULL, 0, 1, 0, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation tokenId=- pending=0 maxPendingEvaluations=1");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_start_line("", 0, 1, 0, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation tokenId=- pending=0 maxPendingEvaluations=1");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_busy_line(2, 1, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "start rejected: engine is busy (pending=2 maxPendingEvaluations=1)");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_busy_line(1, 0, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "start rejected: engine is busy (pending=1 maxPendingEvaluations=0)");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_setting_line(0, 3, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation on: maxPendingEvaluations=3 (was 0)");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_setting_line(3, 1, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation on: maxPendingEvaluations=1 (was 3)");
    ygst_buf_reset(&b);
    CHECK_INT(ygst_pipe_setting_line(3, 0, &b), 0);
    CHECK_STR(ygst_buf_cstr(&b), "pipelined evaluation off: maxPendingEvaluations=0 (was 3)");
    CHECK_INT(ygst_pipe_start_line("t", 0, 1, 0, NULL), -1);
    CHECK_INT(ygst_pipe_busy_line(0, 0, NULL), -1);
    CHECK_INT(ygst_pipe_setting_line(0, 0, NULL), -1);
    ygst_buf_free(&b);
}

void suite_pipeline(void) {
    RUN("pipeline", test_pipe_clamp);
    RUN("pipeline", test_pipe_hands_over);
    RUN("pipeline", test_pipe_decide_table);
    RUN("pipeline", test_pipe_scenarios);
    RUN("pipeline", test_pipe_lines);
}
