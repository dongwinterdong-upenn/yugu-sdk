/*
 * Copyright 2026 优谷雅言 open.shengzhiai.com
 * SPDX-License-Identifier: Apache-2.0
 *
 * SHA-256 (FIPS 180-4 examples), HMAC-SHA256 (RFC 4231), Base64 (RFC 4648) and the shared
 * signature vectors spec/fixtures/sign/vectors.json.
 */
#include "ygt.h"

static void hex_of(const unsigned char *d, size_t n, char *out) {
    ygst_hex_lower(d, n, out);
}

static void test_sha256_vectors(void) {
    unsigned char out[32];
    char hex[65];
    static const char *abc448 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    ygst_sha256("", 0, out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    ygst_sha256("abc", 3, out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    ygst_sha256(abc448, strlen(abc448), out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
}

static void test_sha256_million_a_incremental(void) {
    ygst_sha256_ctx c;
    unsigned char out[32];
    char hex[65];
    char chunk[997];
    size_t done = 0;
    memset(chunk, 'a', sizeof chunk);
    ygst_sha256_init(&c);
    while (done < 1000000) {
        size_t take = 1000000 - done < sizeof chunk ? 1000000 - done : sizeof chunk;
        ygst_sha256_update(&c, chunk, take);
        done += take;
    }
    ygst_sha256_final(&c, out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
}

static void test_sha256_split_equals_oneshot(void) {
    unsigned char msg[300];
    unsigned char a[32], b[32];
    size_t i, split;
    for (i = 0; i < sizeof msg; i++) msg[i] = (unsigned char)(i * 7 + 3);
    ygst_sha256(msg, sizeof msg, a);
    for (split = 0; split <= sizeof msg; split += 37) {
        ygst_sha256_ctx c;
        ygst_sha256_init(&c);
        ygst_sha256_update(&c, msg, split);
        ygst_sha256_update(&c, msg + split, sizeof msg - split);
        ygst_sha256_final(&c, b);
        CHECK(memcmp(a, b, 32) == 0);
    }
    /* 55, 56 and 64 byte messages exercise both padding branches */
    for (i = 54; i <= 65; i++) {
        ygst_sha256_ctx c;
        ygst_sha256_init(&c);
        ygst_sha256_update(&c, msg, i);
        ygst_sha256_final(&c, a);
        ygst_sha256_init(&c);
        ygst_sha256_update(&c, msg, 1);
        ygst_sha256_update(&c, msg + 1, i - 1);
        ygst_sha256_final(&c, b);
        CHECK(memcmp(a, b, 32) == 0);
    }
}

static void test_hmac_rfc4231(void) {
    unsigned char key[131];
    unsigned char out[32];
    char hex[65];
    static const char *big = "Test Using Larger Than Block-Size Key - Hash Key First";
    memset(key, 0x0b, 20);
    ygst_hmac_sha256(key, 20, "Hi There", 8, out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    ygst_hmac_sha256("Jefe", 4, "what do ya want for nothing?", 28, out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    memset(key, 0xaa, sizeof key);
    ygst_hmac_sha256(key, sizeof key, big, strlen(big), out);
    hex_of(out, 32, hex);
    CHECK_STR(hex, "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
}

static void test_base64_rfc4648(void) {
    static const char *in[] = {"", "f", "fo", "foo", "foob", "fooba", "foobar"};
    static const char *want[] = {"", "Zg==", "Zm8=", "Zm9v", "Zm9vYg==", "Zm9vYmE=", "Zm9vYmFy"};
    char out[16];
    size_t i;
    for (i = 0; i < 7; i++) {
        size_t n = ygst_base64_encode((const unsigned char *)in[i], strlen(in[i]), out, sizeof out);
        CHECK_INT(n, strlen(want[i]));
        CHECK_STR(out, want[i]);
    }
    CHECK_INT(ygst_base64_encode((const unsigned char *)"foobar", 6, out, 8), 0);
    CHECK_INT(ygst_base64_encode((const unsigned char *)"foobar", 6, NULL, 0), 0);
}

/* ---------------------------------------------------------------- signing */

static void test_sign_shared_vectors(void) {
    size_t len = 0;
    char *text = ygt_read_spec("fixtures/sign/vectors.json", &len);
    ygst_span root, cases, el;
    size_t pos = 0;
    int n = 0;
    CHECK(text != NULL);
    if (!text) return;
    CHECK_INT(ygst_json_parse(text, len, &root), 0);
    CHECK_INT(ygst_json_member(root, "cases", &cases), 1);
    while (ygst_json_array_next(cases, &pos, &el) == 1) {
        ygst_span name, secret, params, payload, sig, k, v;
        ygst_buf bname, bsecret, bpayload, bsig, got;
        ygst_fields f;
        size_t ppos = 0;
        char out[45];
        ygst_buf_init(&bname);
        ygst_buf_init(&bsecret);
        ygst_buf_init(&bpayload);
        ygst_buf_init(&bsig);
        ygst_buf_init(&got);
        ygst_fields_init(&f);
        CHECK_INT(ygst_json_member(el, "name", &name), 1);
        CHECK_INT(ygst_json_member(el, "secret", &secret), 1);
        CHECK_INT(ygst_json_member(el, "params", &params), 1);
        CHECK_INT(ygst_json_member(el, "payload", &payload), 1);
        CHECK_INT(ygst_json_member(el, "signature", &sig), 1);
        ygst_json_string(name, &bname);
        ygst_json_string(secret, &bsecret);
        ygst_json_string(payload, &bpayload);
        ygst_json_string(sig, &bsig);
        while (ygst_json_object_next(params, &ppos, &k, &v) == 1) {
            ygst_buf bk, bv;
            ygst_buf_init(&bk);
            ygst_buf_init(&bv);
            ygst_json_string(k, &bk);
            if (ygst_json_type(v) == 's') {
                ygst_json_string(v, &bv);
                ygst_fields_set(&f, ygst_buf_cstr(&bk), ygst_buf_cstr(&bv));
            } else if (ygst_json_type(v) == 'n' || ygst_json_type(v) == 't' || ygst_json_type(v) == 'f') {
                ygst_buf_append(&bv, v.p, v.n);
                ygst_fields_set(&f, ygst_buf_cstr(&bk), ygst_buf_cstr(&bv));
            } /* null: not a parameter */
            ygst_buf_free(&bk);
            ygst_buf_free(&bv);
        }
        CHECK_INT(ygst_sign_payload(&f, &got), 0);
        CHECK_STR(ygst_buf_cstr(&got), ygst_buf_cstr(&bpayload));
        CHECK_INT(ygst_sign(&f, ygst_buf_cstr(&bsecret), out), 0);
        CHECK_STR(out, ygst_buf_cstr(&bsig));
        if (strcmp(out, ygst_buf_cstr(&bsig)) != 0) fprintf(stderr, "    vector %s\n", ygst_buf_cstr(&bname));
        n++;
        ygst_fields_free(&f);
        ygst_buf_free(&bname);
        ygst_buf_free(&bsecret);
        ygst_buf_free(&bpayload);
        ygst_buf_free(&bsig);
        ygst_buf_free(&got);
    }
    CHECK_INT(n, 7);
    printf("     signature vectors matched: %d\n", n);
    free(text);
}

static void test_sign_contract_vector_direct(void) {
    ygst_fields f;
    char out[45];
    ygst_fields_init(&f);
    ygst_fields_set(&f, "refText", "北京你好");
    ygst_fields_set(&f, "coreType", "sent.eval.cn");
    ygst_fields_set(&f, "language", "zh-CN");
    CHECK_INT(ygst_sign(&f, "test_secret_key_123", out), 0);
    CHECK_STR(out, "A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=");
    CHECK_INT(ygst_sign(&f, NULL, out), -1);
    ygst_fields_free(&f);
}

static void test_fields_set_replace_remove(void) {
    ygst_fields f, g;
    ygst_buf b;
    int i;
    ygst_fields_init(&f);
    ygst_fields_init(&g);
    ygst_buf_init(&b);
    CHECK_INT(ygst_fields_set(&f, "b", "1"), 0);
    CHECK_INT(ygst_fields_set(&f, "a", "2"), 0);
    CHECK_INT(ygst_fields_set(&f, "b", "3"), 0);
    CHECK_INT(f.count, 2);
    CHECK_STR(f.items[0].name, "b");
    CHECK_STR(ygst_fields_get(&f, "b"), "3");
    CHECK(ygst_fields_get(&f, "zz") == NULL);
    CHECK_INT(ygst_fields_set(&f, "a", NULL), 0);
    CHECK_INT(f.count, 1);
    CHECK_INT(ygst_fields_remove(&f, "nope"), 0);
    CHECK_INT(ygst_fields_set(&f, NULL, "x"), -1);
    for (i = 0; i < 40; i++) {
        char k[8];
        snprintf(k, sizeof k, "k%02d", i % 100);
        ygst_fields_set(&f, k, i % 2 ? "" : "v");
    }
    CHECK_INT(f.count, 41);
    CHECK_INT(ygst_fields_copy(&f, &g), 0);
    CHECK_INT(g.count, 41);
    ygst_sign_payload(&g, &b);
    CHECK(strncmp(ygst_buf_cstr(&b), "b=3&k00=v&k02=v", 15) == 0);
    CHECK(strstr(ygst_buf_cstr(&b), "k01") == NULL);
    ygst_fields_free(&f);
    ygst_fields_free(&g);
    ygst_buf_free(&b);
}

static void test_utf16_order(void) {
    /* U+1F600 is D83D DE00 in UTF-16 and sorts before U+FFFD, the reverse of UTF-8 byte order. */
    const char *emoji = "\xF0\x9F\x98\x80";
    const char *repl = "\xEF\xBF\xBD";
    ygst_fields f;
    ygst_buf b;
    CHECK(ygst_utf16_compare(emoji, repl) < 0);
    CHECK(strcmp(emoji, repl) > 0);
    CHECK(ygst_utf16_compare("B", "a") < 0);
    CHECK(ygst_utf16_compare("a", "ab") < 0);
    CHECK(ygst_utf16_compare("ab", "a") > 0);
    CHECK(ygst_utf16_compare("same", "same") == 0);
    CHECK(ygst_utf16_compare("\xC3\xA9", "z") > 0);
    CHECK(ygst_utf16_compare("\xC3", "a") > 0); /* truncated sequence decodes as U+FFFD */
    CHECK(ygst_utf16_compare("\xFF", "\xC3\xA9") > 0);
    ygst_fields_init(&f);
    ygst_buf_init(&b);
    ygst_fields_set(&f, repl, "r");
    ygst_fields_set(&f, emoji, "e");
    ygst_fields_set(&f, "_x", "u");
    ygst_fields_set(&f, "Z", "z");
    ygst_sign_payload(&f, &b);
    CHECK_STR(ygst_buf_cstr(&b), "Z=z&_x=u&\xF0\x9F\x98\x80=e&\xEF\xBF\xBD=r");
    ygst_fields_free(&f);
    ygst_buf_free(&b);
}

static void test_buf_and_strings(void) {
    ygst_buf b;
    char out[8];
    char *d;
    ygst_buf_init(&b);
    CHECK_STR(ygst_buf_cstr(&b), "");
    ygst_buf_append_int(&b, -42);
    ygst_buf_append_char(&b, ':');
    ygst_buf_append_str(&b, NULL);
    ygst_buf_append(&b, "xyz", 0);
    CHECK_STR(ygst_buf_cstr(&b), "-42:");
    ygst_buf_reset(&b);
    CHECK_INT(b.len, 0);
    ygst_buf_free(&b);
    CHECK(ygst_strcaseeq("WaV", "wav"));
    CHECK(!ygst_strcaseeq("wav", "wave"));
    CHECK(!ygst_strcaseeq(NULL, "a"));
    CHECK(ygst_has_suffix_ci("a.WAV", ".wav"));
    CHECK(!ygst_has_suffix_ci("wav", ".wav"));
    CHECK(!ygst_has_suffix_ci(NULL, "x"));
    CHECK_INT(ygst_trim_copy("  ab c \n", out, sizeof out), 4);
    CHECK_STR(out, "ab c");
    CHECK_INT(ygst_trim_copy("123456789", out, 4), 3);
    CHECK_INT(ygst_trim_copy(NULL, out, sizeof out), 0);
    CHECK(ygst_is_blank("  \t"));
    CHECK(ygst_is_blank(NULL));
    CHECK(!ygst_is_blank(" x "));
    d = ygst_strdup("dup");
    CHECK_STR(d, "dup");
    free(d);
    CHECK(ygst_strdup(NULL) == NULL);
}

void suite_crypto(void) {
    RUN("crypto", test_sha256_vectors);
    RUN("crypto", test_sha256_million_a_incremental);
    RUN("crypto", test_sha256_split_equals_oneshot);
    RUN("crypto", test_hmac_rfc4231);
    RUN("crypto", test_base64_rfc4648);
    RUN("crypto", test_buf_and_strings);
}

void suite_sign(void) {
    RUN("sign", test_sign_shared_vectors);
    RUN("sign", test_sign_contract_vector_direct);
    RUN("sign", test_fields_set_replace_remove);
    RUN("sign", test_utf16_order);
}
