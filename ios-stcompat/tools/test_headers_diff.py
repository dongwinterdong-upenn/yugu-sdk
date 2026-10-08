#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Tests of tools/headers-diff.py: every kind of declaration change must be reported.

The package headers are copied to a temporary "original" directory, one mutation is applied to
the copy, and the tool must exit 1 and name the expected declaration. Comment and whitespace
edits must not be reported. Run: python3 tools/test_headers_diff.py
"""
import importlib.util
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
OURS = os.path.join(os.path.dirname(HERE), "Sources", "STKouyuEngine", "include", "STKouyuEngine")
spec = importlib.util.spec_from_file_location("headers_diff", os.path.join(HERE, "headers-diff.py"))
hd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hd)

# (name, file, old, new, expected key fragment or None for "no difference", strict)
MUTATIONS = [
    ("superclass", "KYTestEngine.h", "@interface KYTestEngine : NSObject", "@interface KYTestEngine : NSProxy",
     "@interface KYTestEngine", False),
    ("property attribute", "KYStartEngineConfig.h", "@property (nonatomic, copy) NSString *appKey;",
     "@property (nonatomic, strong) NSString *appKey;", "property appKey", False),
    ("property type", "KYStartEngineConfig.h", "@property (nonatomic, assign) CGFloat seek;",
     "@property (nonatomic, assign) NSInteger seek;", "property seek", False),
    ("property removed", "KYTestConfig.h", "@property (nonatomic, assign) BOOL blend_phoneme_enable;", "",
     "property blend_phoneme_enable", False),
    ("property added", "KYTestConfig.h", "@property (nonatomic, assign) BOOL blend_phoneme_enable;",
     "@property (nonatomic, assign) BOOL blend_phoneme_enable;\n@property (nonatomic, assign) BOOL extra;",
     "property extra", False),
    ("selector", "KYTestEngine.h", "- (void)playWithPath:(NSString *)wavPath void:(KYPlayFinishBlock)playFinishBlock;",
     "- (void)playWithPath:(NSString *)wavPath then:(KYPlayFinishBlock)playFinishBlock;", "-playWithPath:void:", False),
    ("parameter type", "KYTestEngine.h", "- (void)feedAudioData:(void *)audioData audioLength:(int)length;",
     "- (void)feedAudioData:(char *)audioData audioLength:(int)length;", "-feedAudioData:audioLength:", False),
    ("return type", "KYTestEngine.h", "- (BOOL)getEngineStatus;", "- (NSInteger)getEngineStatus;", "-getEngineStatus",
     False),
    ("class to instance method", "KYTestEngine.h", "+ (instancetype)sharedInstance;", "- (instancetype)sharedInstance;",
     "+sharedInstance", False),
    ("block parameter type", "KYTestEngine.h", "onTickBlock:(void(^)(CGFloat millisUntilFinished, CGFloat percentUntilFinished))",
     "onTickBlock:(void(^)(double millisUntilFinished, CGFloat percentUntilFinished))", "onTickBlock", False),
    ("enum value", "KYTestConfig.h", "KYCompress_Speex = 1,", "KYCompress_Speex = 2,", "enum-case KYCompress_Speex",
     False),
    ("enum case removed", "KYTestConfig.h", "    KYTestType_Wordspell,", "", "KYTestType_Wordspell", False),
    ("enum case inserted", "KYTestConfig.h", "    KYTestType_Open,", "    KYTestType_New,\n    KYTestType_Open,",
     "enum-case KYTestType_Open", False),
    ("enum underlying type", "KYTestEngine.h", "typedef enum : NSUInteger {", "typedef enum : NSInteger {",
     "enum KYEngineType", False),
    ("typedef", "KYTestEngine.h", "typedef void(^KYPlayFinishBlock)(void);", "typedef void(^KYPlayFinishBlock)(BOOL done);",
     "typedef KYPlayFinishBlock", False),
    ("extern constant type", "KYStartEngineConfig.h", "FOUNDATION_EXPORT int const KYLOG_ERROR;",
     "FOUNDATION_EXPORT long const KYLOG_ERROR;", "variable KYLOG_ERROR", False),
    ("extern string constant", "KYTestConfig.h", "extern NSString *const KYEngineCloud;", "extern NSString *KYEngineCloud;",
     "variable KYEngineCloud", False),
    ("function attribute", "KYStartEngineConfig.h", "NS_FORMAT_FUNCTION(2,3) NS_NO_TAIL_CALL;",
     "NS_FORMAT_FUNCTION(2,3);", "function KYLog", False),
    ("protocol optional to required", "KYTestEngine.h", "- (void)kyTestEngineDidPlayEnd;",
     "@required\n- (void)kyTestEngineDidPlayEnd;", "-kyTestEngineDidPlayEnd", False),
    ("protocol method removed", "KYTestEngine.h", "- (void)kyTestEngineDidRecordFeedFail:(NSString *)str;", "",
     "-kyTestEngineDidRecordFeedFail:", False),
    ("protocol inheritance", "KYTestEngine.h", "@protocol KYTestEngineDelegate <NSObject>",
     "@protocol KYTestEngineDelegate <NSObject, NSCopying>", "@protocol KYTestEngineDelegate", False),
    ("nullability region", "KYTestConfig.h", "@interface KYTestConfig : NSObject",
     "NS_ASSUME_NONNULL_BEGIN\n@interface KYTestConfig : NSObject", "property", False),
    ("nullability qualifier", "KYTestEngine.h", "- (NSString *)getLastRecordPath;",
     "- (nullable NSString *)getLastRecordPath;", "-getLastRecordPath", False),
    ("include guard", "KYStartEngineConfig.h", "#ifndef KYStartEngineConfig_H_\n#define KYStartEngineConfig_H_",
     "#ifndef KYSTART_H\n#define KYSTART_H", "include-guard", False),
    ("local import", "STKouyuEngine.h", '#import "KYTestEngine.h"', "", "import KYTestEngine.h", False),
    ("version symbol type", "STKouyuEngine.h", "FOUNDATION_EXPORT double STKouyuEngineVersionNumber;",
     "FOUNDATION_EXPORT float STKouyuEngineVersionNumber;", "variable STKouyuEngineVersionNumber", False),
    ("C function parameter", "skegn.h", "skegn_feed(struct skegn *engine, const void *data, int size);",
     "skegn_feed(struct skegn *engine, const void *data, long size);", "function skegn_feed", False),
    ("C callback typedef", "skegn.h", "int type, const void *message,", "int type, void *message,",
     "typedef skegn_callback", False),
    ("macro value", "skegn.h", '#define SKEGN_VERSION "2.7.6"', '#define SKEGN_VERSION "2.7.7"', "macro SKEGN_VERSION",
     False),
    ("C enum order", "skegn.h", "  SKEGN_OPT_GET_MODULES,\n  SKEGN_OPT_GET_TRAFFIC,",
     "  SKEGN_OPT_GET_TRAFFIC,\n  SKEGN_OPT_GET_MODULES,", "enum-case SKEGN_OPT_GET_MODULES", False),
    ("parameter name, strict", "KYTestEngine.h", "- (void)playWithPath:(NSString *)wavPath;",
     "- (void)playWithPath:(NSString *)path;", "-playWithPath:", True),
    ("comments and whitespace only", "KYTestConfig.h", "@property (nonatomic, copy) NSString *refText;",
     "/* moved */ @property   (nonatomic,copy)   NSString*\n   refText ; // trailing", None, True),
]


def run(orig_dir, strict):
    report, failed = hd.compare(hd.collect([orig_dir]), OURS, strict)
    return report, failed


def main():
    failures = 0
    tmp = tempfile.mkdtemp(prefix="headers-diff-test-")
    try:
        base = os.path.join(tmp, "orig")
        shutil.copytree(OURS, base)
        os.remove(os.path.join(base, "YuguCompat.h"))  # extras are not part of the original API
        report, failed = run(base, True)
        if failed or report["declarations"] < 200:
            print("FAIL baseline: %d differences, %d declarations" % (len(report["differences"]), report["declarations"]))
            failures += 1
        else:
            print("ok   baseline: identical copy, %d declarations, 0 differences" % report["declarations"])
        for name, fname, old, new, expect, strict in MUTATIONS:
            work = os.path.join(tmp, "case")
            shutil.rmtree(work, ignore_errors=True)
            shutil.copytree(base, work)
            path = os.path.join(work, fname)
            text = open(path, encoding="utf-8").read()
            if old not in text:
                print("FAIL %s: text to mutate not found in %s" % (name, fname))
                failures += 1
                continue
            open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
            report, failed = run(work, strict)
            keys = [d["key"] for d in report["differences"] + (report["name_differences"] if strict else [])]
            if expect is None:
                ok = not failed
            else:
                ok = failed and any(expect in k for k in keys)
            print("%s %s%s" % ("ok  " if ok else "FAIL", name, "" if ok else ": %s" % json.dumps(keys)[:300]))
            failures += 0 if ok else 1
        # a header of the original API that this package does not have
        os.makedirs(os.path.join(tmp, "partial"))
        for f in os.listdir(OURS):
            if f != "KYTestConfig.h":
                shutil.copy(os.path.join(OURS, f), os.path.join(tmp, "partial", f))
        report, failed = hd.compare(hd.collect([base]), os.path.join(tmp, "partial"), False)
        ok = failed and any(d["kind"] == "file missing" for d in report["differences"])
        print("%s file missing in ours" % ("ok  " if ok else "FAIL"))
        failures += 0 if ok else 1
        rc = hd.main(["--original", base, "--quiet"])
        print("%s command line exit 0 on identical headers" % ("ok  " if rc == 0 else "FAIL"))
        failures += 0 if rc == 0 else 1
        rc = hd.main(["--original", os.path.join(tmp, "does-not-exist"), "--quiet"])
        print("%s command line exit 2 on a missing path" % ("ok  " if rc == 2 else "FAIL"))
        failures += 0 if rc == 2 else 1
        # the positional form used in SHENGTONG-MIGRATION.md: headers-diff.py ORIGINAL OURS
        rc = hd.main([base, OURS, "--quiet"])
        print("%s positional ORIGINAL OURS form" % ("ok  " if rc == 0 else "FAIL"))
        failures += 0 if rc == 0 else 1
        rc = hd.main([os.path.join(tmp, "partial-orig-missing"), "--quiet"])
        print("%s positional form with a missing path exits 2" % ("ok  " if rc == 2 else "FAIL"))
        failures += 0 if rc == 2 else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    total = len(MUTATIONS) + 6
    print("headers-diff self test: %d checks, %d failed" % (total, failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
