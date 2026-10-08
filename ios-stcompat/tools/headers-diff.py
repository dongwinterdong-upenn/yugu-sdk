#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Compare Objective-C / C public headers declaration by declaration.

Checks that the STKouyuEngine compatibility package declares exactly the API of the Shengtong
STKouyuEngine.framework headers: classes with superclass and protocols, properties with
attributes and types, methods with selectors, return and parameter types, protocol methods with
@optional/@required, enums with case names and values, typedefs (blocks and function pointers),
structs, extern constants and functions with attributes, macros with values, include guards and
the imports between the framework's own headers. Comments, whitespace and line breaks are ignored.
System imports (UIKit, Foundation) are not compared. Preprocessor conditionals are ignored and
every branch is read, on both sides alike.

Usage:
  headers-diff.py --original /path/STKouyuEngine.framework/Headers [--original /path/skegn.h]
                  [--ours Sources/STKouyuEngine/include/STKouyuEngine] [--strict] [--json out.json]
  headers-diff.py /path/STKouyuEngine.framework/Headers [Sources/STKouyuEngine/include/STKouyuEngine]

Every header file found under --original (files or directories, *.h) is compared with the file of
the same name under --ours. Headers that only exist under --ours (YuguCompat.h, the extras of this
package) are listed and not compared. Exit status: 0 identical, 1 differences, 2 usage or parse
error. --strict also fails on differences of parameter names, which do not change the API.

No dependencies besides Python 3.7+.
"""
import argparse
import json
import os
import re
import sys

STORAGE = {"extern", "FOUNDATION_EXPORT", "FOUNDATION_EXTERN", "UIKIT_EXTERN", "CF_EXPORT", "OBJC_EXPORT",
           "SKEGN_IMPORT_OR_EXPORT", "NS_INLINE", "static", "inline", "__inline__"}
CALLCONV = {"SKEGN_CALL", "__stdcall", "__cdecl"}
TYPE_KEYWORDS = {"void", "char", "short", "int", "long", "float", "double", "signed", "unsigned", "_Bool", "bool",
                 "const", "volatile", "struct", "union", "enum", "__unsafe_unretained", "__strong", "__weak",
                 "__autoreleasing", "_Nullable", "_Nonnull", "_Null_unspecified", "__nullable", "__nonnull",
                 "nullable", "nonnull", "null_unspecified", "__kindof", "restrict", "__restrict"}
QUALIFIERS = {"const", "volatile", "struct", "union", "enum", "__kindof", "__unsafe_unretained", "__strong", "__weak"}
NONNULL_BEGIN = {"NS_ASSUME_NONNULL_BEGIN", "CF_ASSUME_NONNULL_BEGIN"}
NONNULL_END = {"NS_ASSUME_NONNULL_END", "CF_ASSUME_NONNULL_END"}


class ParseError(Exception):
    pass


# ------------------------------------------------------------------ lexing


def strip_comments(text):
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            if j < 0:
                raise ParseError("unterminated comment")
            out.append(" " + "\n" * text.count("\n", i, j))
            i = j + 2
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


TOKEN = re.compile(r"""
    (?P<ws>\s+)
  | (?P<str>@?"(?:\\.|[^"\\])*")
  | (?P<chr>'(?:\\.|[^'\\])*')
  | (?P<num>0[xX][0-9a-fA-F]+[uUlL]*|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[uUlLfF]*)
  | (?P<id>@?[A-Za-z_][A-Za-z0-9_]*)
  | (?P<op>\.\.\.|<<|>>|->|::|&&|\|\||[{}()\[\];,:<>*^&|=+\-~!?/%.#@])
""", re.X)


def tokenize(text):
    toks = []
    pos = 0
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m:
            raise ParseError("unexpected character %r" % text[pos])
        pos = m.end()
        if m.lastgroup != "ws":
            toks.append(m.group(0))
    return toks


def preprocess(text):
    """Returns (code without directives, macros, guards, local imports)."""
    text = strip_comments(text).replace("\\\n", " ")
    code_lines = []
    macros = {}
    guards = []
    imports = []
    last_ifndef = None
    for line in text.split("\n"):
        s = line.strip()
        if not s.startswith("#"):
            code_lines.append(line)
            if s:
                last_ifndef = None
            continue
        code_lines.append("")
        d = s[1:].strip()
        m = re.match(r"(import|include)\s*([<\"])([^>\"]+)[>\"]", d)
        if m:
            target = m.group(3)
            if m.group(2) == '"' or target.startswith("STKouyuEngine/"):
                imports.append(os.path.basename(target))
            continue
        m = re.match(r"ifndef\s+([A-Za-z_]\w*)\s*$", d)
        if m:
            last_ifndef = m.group(1)
            continue
        m = re.match(r"define\s+([A-Za-z_]\w*)(\([^)]*\))?\s*(.*)$", d)
        if m:
            name, params, value = m.group(1), m.group(2) or "", m.group(3).strip()
            if not value and not params and name == last_ifndef:
                guards.append(name)
            else:
                macros.setdefault(name + params, []).append(" ".join(tokenize(value)))
            last_ifndef = None
            continue
        last_ifndef = None
    return "\n".join(code_lines), macros, guards, imports


# ------------------------------------------------------------------ helpers


def match_close(toks, i, open_, close):
    depth = 0
    for j in range(i, len(toks)):
        if toks[j] == open_:
            depth += 1
        elif toks[j] == close:
            depth -= 1
            if depth == 0:
                return j
    raise ParseError("unbalanced %s at token %d" % (open_, i))


def split_top(toks, sep=","):
    parts, cur, depth = [], [], 0
    for t in toks:
        if t in "([{<":
            depth += 1
        elif t in ")]}>":
            depth -= 1
        if t == sep and depth == 0:
            parts.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        parts.append(cur)
    return parts


def is_ident(t):
    return re.match(r"^[A-Za-z_]\w*$", t) is not None


def strip_names(toks):
    """Removes parameter names from parameter lists inside a type. Returns (tokens, names)."""
    out, names = [], []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t == "(":
            j = match_close(toks, i, "(", ")")
            inner = toks[i + 1:j]
            prev = out[-1] if out else None
            is_param_list = prev is not None and (prev == ")" or is_ident(prev)) and not (inner and inner[0] in ("^", "*"))
            if is_param_list:
                params = []
                for p in split_top(inner):
                    p2, n2 = strip_param(p)
                    params.append(p2)
                    names.extend(n2)
                flat = []
                for k, p in enumerate(params):
                    if k:
                        flat.append(",")
                    flat.extend(p)
                out.append("(")
                out.extend(flat)
                out.append(")")
            else:
                sub, n2 = strip_names(inner)
                names.extend(n2)
                out.append("(")
                out.extend(sub)
                out.append(")")
            i = j + 1
            continue
        out.append(t)
        i += 1
    return out, names


def strip_param(p):
    """One C parameter declaration: drop the declared name, keep the type."""
    if not p:
        return p, []
    # a declarator group like (^name) or (*name)
    for k, t in enumerate(p):
        if t == "(" and k + 1 < len(p) and p[k + 1] in ("^", "*"):
            j = match_close(p, k, "(", ")")
            group = p[k + 1:j]
            if group and is_ident(group[-1]) and group[-1] not in CALLCONV:
                name = group[-1]
                rest = p[:k + 1] + group[:-1] + p[j:]
                sub, n2 = strip_names(rest)
                return sub, [name] + n2
            sub, n2 = strip_names(p)
            return sub, n2
    sub, names = strip_names(p)
    if sub and sub[-1] == "]":
        k = len(sub) - 1
        while k >= 0 and sub[k] != "[":
            k -= 1
        if k > 0 and is_ident(sub[k - 1]) and len(sub[:k]) >= 2 and sub[k - 2] not in QUALIFIERS:
            return sub[:k - 1] + sub[k:], names + [sub[k - 1]]
        return sub, names
    if len(sub) >= 2 and is_ident(sub[-1]) and sub[-1] not in TYPE_KEYWORDS and sub[-2] not in QUALIFIERS:
        return sub[:-1], names + [sub[-1]]
    return sub, names


def join(toks):
    return " ".join(toks)


# ------------------------------------------------------------------ enum values


def eval_int(expr, env):
    if not expr:
        return None
    src = []
    for t in expr:
        if re.match(r"^0[xX][0-9a-fA-F]+[uUlL]*$", t):
            src.append(str(int(re.sub(r"[uUlL]+$", "", t), 16)))
        elif re.match(r"^\d+[uUlL]*$", t):
            src.append(re.sub(r"[uUlL]+$", "", t))
        elif re.match(r"^'(.+)'$", t):
            chars = t[1:-1]
            if "\\" in chars:
                return None
            v = 0
            for ch in chars:
                v = (v << 8) | ord(ch)
            src.append(str(v))
        elif t in env and isinstance(env[t], int):
            src.append(str(env[t]))
        elif t in ("<<", ">>", "|", "&", "+", "-", "*", "(", ")", "~"):
            src.append(t)
        elif t in ("NSUInteger", "NSInteger", "int", "unsigned", "long"):
            continue  # casts like (NSUInteger)1
        else:
            return None
    try:
        return int(eval(" ".join(src), {"__builtins__": {}}, {}))  # noqa: S307 - digits and operators only
    except Exception:  # noqa: BLE001
        return None


# ------------------------------------------------------------------ declarations


class Header:
    def __init__(self, path):
        self.path = path
        self.decls = {}   # key -> signature
        self.names = {}   # key -> parameter names
        self.order = []
        self.nonnull = False

    def add(self, key, sig, names=None):
        if self.nonnull:
            sig += " [assume_nonnull]"
        if key in self.decls and self.decls[key] != sig:
            # the same name declared twice in one header: keep both
            n = 2
            while "%s #%d" % (key, n) in self.decls:
                n += 1
            key = "%s #%d" % (key, n)
        self.decls[key] = sig
        self.names[key] = names or []
        self.order.append(key)


def parse_header(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        raw = f.read()
    code, macros, guards, imports = preprocess(raw)
    toks = tokenize(code)
    h = Header(path)
    for g in guards:
        h.add("include-guard " + g, "defined")
    for imp in imports:
        h.add("import " + imp, "local import")
    for name, values in macros.items():
        h.add("macro " + name, " | ".join(values))
    parse_tokens(toks, h)
    return h


def parse_tokens(toks, h):
    i = 0
    extern_c_depth = 0
    n = len(toks)
    while i < n:
        t = toks[i]
        if t in NONNULL_BEGIN:
            h.nonnull = True
            i += 1
            continue
        if t in NONNULL_END:
            h.nonnull = False
            i += 1
            continue
        if t in ("NS_HEADER_AUDIT_BEGIN",):
            h.nonnull = True
            i = match_close(toks, i + 1, "(", ")") + 1
            continue
        if t in ("NS_HEADER_AUDIT_END",):
            h.nonnull = False
            i = match_close(toks, i + 1, "(", ")") + 1
            continue
        if t == "extern" and i + 2 < n and toks[i + 1] == '"C"' and toks[i + 2] == "{":
            extern_c_depth += 1
            i += 3
            continue
        if t == "}" and extern_c_depth:
            extern_c_depth -= 1
            i += 1
            continue
        if t == ";":
            i += 1
            continue
        if t == "@class":
            j = toks.index(";", i)
            for name in split_top(toks[i + 1:j]):
                h.add("@class " + join(name), "forward")
            i = j + 1
            continue
        if t in ("@interface", "@protocol"):
            i = parse_objc_container(toks, i, h)
            continue
        if t == "@end":
            raise ParseError("@end without @interface")
        i = parse_c_declaration(toks, i, h)


def parse_objc_container(toks, i, h):
    kind = toks[i]
    name = toks[i + 1]
    j = i + 2
    if kind == "@protocol" and j < len(toks) and toks[j] in (";", ","):
        k = toks.index(";", i)
        for p in split_top(toks[i + 1:k]):
            h.add("@protocol " + join(p), "forward")
        return k + 1
    generics = ""
    category = None
    superclass = ""
    protocols = []
    if j < len(toks) and toks[j] == "<" and kind == "@interface":
        k = match_close(toks, j, "<", ">")
        generics = join(toks[j:k + 1])
        j = k + 1
    if j < len(toks) and toks[j] == "(":
        k = match_close(toks, j, "(", ")")
        category = join(toks[j + 1:k])
        j = k + 1
    if j < len(toks) and toks[j] == ":":
        superclass = toks[j + 1]
        j += 2
        if j < len(toks) and toks[j] == "<" and kind == "@interface" and toks[j + 1] not in (",",):
            # superclass generics or protocol list: protocol lists come right after the superclass
            k = match_close(toks, j, "<", ">")
            protocols = [join(p) for p in split_top(toks[j + 1:k])]
            j = k + 1
    if j < len(toks) and toks[j] == "<":
        k = match_close(toks, j, "<", ">")
        protocols = [join(p) for p in split_top(toks[j + 1:k])]
        j = k + 1
    if kind == "@protocol":
        owner = "@protocol " + name
        h.add(owner, "inherits [%s]" % ", ".join(sorted(protocols)))
    elif category is not None:
        owner = "@interface %s(%s)" % (name, category)
        h.add(owner, "category protocols [%s]" % ", ".join(sorted(protocols)))
    else:
        owner = "@interface " + name
        h.add(owner, "superclass %s%s protocols [%s]" % (superclass or "(root)", " generics " + generics if generics
                                                       else "", ", ".join(sorted(protocols))))
    if j < len(toks) and toks[j] == "{":
        k = match_close(toks, j, "{", "}")
        parse_ivars(toks[j + 1:k], owner, h)
        j = k + 1
    optional = False
    while j < len(toks) and toks[j] != "@end":
        t = toks[j]
        if t == "@optional":
            optional = True
            j += 1
        elif t == "@required":
            optional = False
            j += 1
        elif t == "@property":
            j = parse_property(toks, j, owner, h)
        elif t in ("-", "+"):
            j = parse_method(toks, j, owner, h, optional if kind == "@protocol" else None)
        elif t == ";":
            j += 1
        elif t in NONNULL_BEGIN or t in NONNULL_END:
            h.nonnull = t in NONNULL_BEGIN
            j += 1
        else:
            j = parse_c_declaration(toks, j, h, owner=owner)
    if j >= len(toks):
        raise ParseError("missing @end for %s" % owner)
    return j + 1


def parse_ivars(toks, owner, h):
    vis = "@protected"
    k = 0
    while k < len(toks):
        if toks[k] in ("@public", "@private", "@protected", "@package"):
            vis = toks[k]
            k += 1
            continue
        e = k
        while e < len(toks) and toks[e] != ";":
            e += 1
        decl = toks[k:e]
        if decl:
            sub, _ = strip_names(decl)
            h.add("%s ivar %s" % (owner, sub[-1]), "%s %s" % (vis, join(sub[:-1])))
        k = e + 1


def parse_property(toks, j, owner, h):
    k = j + 1
    attrs = []
    if toks[k] == "(":
        e = match_close(toks, k, "(", ")")
        attrs = sorted(join(a) for a in split_top(toks[k + 1:e]))
        k = e + 1
    e = k
    while toks[e] != ";":
        e += 1
    decl = toks[k:e]
    # trailing attribute macros (NS_AVAILABLE(...), API_DEPRECATED(...)) stay in the type text
    parts = split_top(decl)
    first = parts[0]
    name_idx = max(idx for idx, t in enumerate(first) if is_ident(t) and t not in TYPE_KEYWORDS)
    base_type = first[:name_idx]
    trailing = first[name_idx + 1:]
    stars = [t for t in base_type if t == "*"]
    base_wo_stars = [t for t in base_type if t != "*"]
    for p_i, p in enumerate(parts):
        if p_i == 0:
            name, ptype = first[name_idx], base_type
        else:
            idents = [t for t in p if is_ident(t)]
            name = idents[-1]
            ptype = base_wo_stars + [t for t in p if t == "*"]
        sig = "property attrs [%s] type %s%s" % (", ".join(attrs), join(ptype), (" " + join(trailing)) if trailing else "")
        h.add("%s property %s" % (owner, name), sig)
    return e + 1


def parse_method(toks, j, owner, h, optional):
    sign = toks[j]
    k = j + 1
    ret = ["id"]
    if toks[k] == "(":
        e = match_close(toks, k, "(", ")")
        ret, _ = strip_names(toks[k + 1:e])
        k = e + 1
    pieces, types, names = [], [], []
    variadic = False
    attrs = []
    while toks[k] != ";" and toks[k] != "{":
        t = toks[k]
        if is_ident(t) and k + 1 < len(toks) and toks[k + 1] == ":":
            pieces.append(t + ":")
            k += 2
            ptype = ["id"]
            if toks[k] == "(":
                e = match_close(toks, k, "(", ")")
                ptype, inner_names = strip_names(toks[k + 1:e])
                names.extend(inner_names)
                k = e + 1
            if is_ident(toks[k]):
                names.append(toks[k])
                k += 1
            types.append(join(ptype))
        elif t == ":" and pieces:
            pieces.append(":")  # anonymous selector piece
            k += 1
        elif t == "," and toks[k + 1] == "...":
            variadic = True
            k += 2
        elif not pieces and is_ident(t):
            pieces.append(t)
            k += 1
        else:
            attrs.append(t)
            k += 1
    selector = "".join(pieces)
    key = "%s %s%s" % (owner, sign, selector)
    sig = "method returns %s args [%s]%s%s%s" % (
        join(ret), "; ".join(types), " variadic" if variadic else "", (" attrs " + join(attrs)) if attrs else "",
        "" if optional is None else (" @optional" if optional else " @required"))
    h.add(key, sig, names)
    if toks[k] == "{":
        k = match_close(toks, k, "{", "}")
    return k + 1


def enum_cases(body, underlying_name, h, start_env):
    value = -1
    env = dict(start_env)
    names = []
    for case in split_top(body):
        if not case:
            continue
        name = case[0]
        if "=" in case:
            expr = case[case.index("=") + 1:]
            v = eval_int(expr, env)
            value_repr = v if v is not None else join(expr)
            value = v if v is not None else None
        else:
            value = (value + 1) if isinstance(value, int) else None
            value_repr = value if value is not None else "(after non-constant)"
        env[name] = value
        names.append(name)
        h.add("enum-case " + name, "%s = %s" % (underlying_name, value_repr))
    return names


def parse_c_declaration(toks, i, h, owner=None):
    # find the end of the declaration at depth 0
    j = i
    depth = 0
    while j < len(toks):
        t = toks[j]
        if t in "([":
            depth += 1
        elif t in ")]":
            depth -= 1
        elif t == "{":
            k = match_close(toks, j, "{", "}")
            j = k + 1
            # function definition body (static inline): the declaration ends here
            if depth == 0 and j < len(toks) and toks[j] != ";" and toks[i] not in ("typedef", "enum", "struct",
                                                                                     "union") and \
                    not any(x in ("enum", "struct", "union", "NS_ENUM", "NS_OPTIONS") for x in toks[i:j]):
                handle_function_like(toks[i:j], h, definition=True)
                return j
            continue
        elif t == ";" and depth == 0:
            break
        j += 1
    decl = toks[i:j]
    if decl:
        handle_declaration(decl, h, owner)
    return j + 1


def handle_declaration(decl, h, owner):
    typedef = decl[0] == "typedef"
    body_tokens = decl[1:] if typedef else decl
    storage = []
    while body_tokens and body_tokens[0] in STORAGE:
        storage.append("extern" if body_tokens[0] not in ("static", "inline", "__inline__", "NS_INLINE")
                       else body_tokens[0])
        body_tokens = body_tokens[1:]
    body_tokens = [t for t in body_tokens if t not in CALLCONV]
    # enums
    if body_tokens and body_tokens[0] in ("NS_ENUM", "NS_OPTIONS", "NS_CLOSED_ENUM", "NS_ERROR_ENUM",
                                           "CF_ENUM", "CF_OPTIONS"):
        e = match_close(body_tokens, 1, "(", ")")
        args = split_top(body_tokens[2:e])
        underlying, name = join(args[0]), join(args[1]) if len(args) > 1 else ""
        b = body_tokens.index("{")
        c = match_close(body_tokens, b, "{", "}")
        cases = enum_cases(body_tokens[b + 1:c], name or "(anonymous)", h, {})
        h.add("enum " + (name or "(anonymous %s)" % (cases[0] if cases else "")),
              "%s(%s) cases [%s]" % (body_tokens[0], underlying, ", ".join(cases)))
        return
    if body_tokens and body_tokens[0] == "enum":
        k = 1
        tag = ""
        underlying = ""
        if k < len(body_tokens) and is_ident(body_tokens[k]):
            tag = body_tokens[k]
            k += 1
        if k < len(body_tokens) and body_tokens[k] == ":":
            b = body_tokens.index("{") if "{" in body_tokens else len(body_tokens)
            underlying = join(body_tokens[k + 1:b])
            k = b
        if k < len(body_tokens) and body_tokens[k] == "{":
            c = match_close(body_tokens, k, "{", "}")
            after = body_tokens[c + 1:]
            name = after[-1] if typedef and after else tag
            label = name or "(anonymous %s)" % join(split_top(body_tokens[k + 1:c])[0][:1]) if body_tokens[k + 1:c] else ""
            cases = enum_cases(body_tokens[k + 1:c], label, h, {})
            h.add("enum " + (label or "(anonymous)"), "enum%s%s cases [%s]%s" % (
                (" tag " + tag) if tag else "", (" : " + underlying) if underlying else "", ", ".join(cases),
                " typedef" if typedef else ""))
            if typedef and tag and name != tag:
                h.add("typedef " + name, "enum " + tag)
            return
    # structs and unions
    if body_tokens and body_tokens[0] in ("struct", "union"):
        if len(body_tokens) == 2 and not typedef:
            h.add("%s %s" % (body_tokens[0], body_tokens[1]), "opaque")
            return
        if "{" in body_tokens and body_tokens.index("{") <= 2:
            b = body_tokens.index("{")
            c = match_close(body_tokens, b, "{", "}")
            fields = []
            for f in split_top(body_tokens[b + 1:c], ";"):
                if f:
                    sub, _ = strip_names(f)
                    fields.append(join(sub))
            tag = body_tokens[1] if b == 2 else ""
            after = body_tokens[c + 1:]
            name = after[-1] if after else tag
            h.add("%s %s" % (body_tokens[0], name or tag or "(anonymous)"),
                  "fields [%s]%s" % ("; ".join(fields), " typedef" if typedef else ""))
            return
    if typedef:
        handle_typedef(body_tokens, h)
        return
    handle_function_like(body_tokens, h, storage=storage)


def handle_typedef(toks, h):
    # block or function pointer: T (^Name)(...)  /  T (*Name)(...)
    for k, t in enumerate(toks):
        if t == "(" and k + 1 < len(toks) and toks[k + 1] in ("^", "*"):
            e = match_close(toks, k, "(", ")")
            group = toks[k + 1:e]
            if group and is_ident(group[-1]):
                name = group[-1]
                rest = toks[:k + 1] + group[:-1] + toks[e:]
                sub, names = strip_names(rest)
                h.add("typedef " + name, join(sub), names)
                return
    # plain: typedef <type> Name [array]
    toks2 = list(toks)
    arr = []
    if toks2 and toks2[-1] == "]":
        b = len(toks2) - 1 - toks2[::-1].index("[")
        arr = toks2[b:]
        toks2 = toks2[:b]
    name = toks2[-1]
    sub, names = strip_names(toks2[:-1] + arr)
    h.add("typedef " + name, join(sub), names)


def handle_function_like(toks, h, storage=None, definition=False):
    storage = list(storage or [])
    while toks and toks[0] in STORAGE:
        storage.append("extern" if toks[0] not in ("static", "inline", "__inline__", "NS_INLINE") else toks[0])
        toks = toks[1:]
    toks = [t for t in toks if t not in CALLCONV]
    if definition:
        b = toks.index("{")
        toks = toks[:b]
    # function: the first '(' at depth 0 preceded by an identifier that is not inside a declarator group
    for k, t in enumerate(toks):
        if t == "(" and k > 0 and is_ident(toks[k - 1]) and toks[k - 1] not in TYPE_KEYWORDS:
            e = match_close(toks, k, "(", ")")
            name = toks[k - 1]
            ret = toks[:k - 1]
            params_raw = toks[k:e + 1]
            params, names = strip_names([name] + params_raw)
            attrs = toks[e + 1:]
            sig = "function %s returns %s params %s%s" % (" ".join(sorted(set(storage))) or "(none)", join(ret),
                                                          join(params[1:]), (" attrs " + join(attrs)) if attrs else "")
            h.add("function " + name, sig, names)
            return
    # variables, possibly several declarators
    parts = split_top(toks)
    first = parts[0]
    arr = []
    core = first
    if core and core[-1] == "]":
        b = len(core) - 1 - core[::-1].index("[")
        arr = core[b:]
        core = core[:b]
    if not core:
        raise ParseError("cannot parse declaration: %s" % join(toks))
    name = core[-1]
    vtype = core[:-1] + arr
    h.add("variable " + name, "variable %s type %s" % (" ".join(sorted(set(storage))) or "(none)", join(vtype)))
    base = [t for t in core[:-1] if t != "*"]
    for p in parts[1:]:
        idents = [t for t in p if is_ident(t)]
        h.add("variable " + idents[-1], "variable %s type %s" % (" ".join(sorted(set(storage))) or "(none)",
                                                                 join(base + [t for t in p if t == "*"])))


# ------------------------------------------------------------------ compare


def collect(paths):
    files = {}
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                for n in sorted(names):
                    if n.endswith(".h"):
                        files.setdefault(n, os.path.join(root, n))
        elif os.path.isfile(p):
            files.setdefault(os.path.basename(p), p)
        else:
            raise FileNotFoundError(p)
    return files


def compare(orig_files, our_dir, strict):
    report = {"files": [], "differences": [], "name_differences": [], "declarations": 0, "extra_headers": []}
    our_files = collect([our_dir])
    for base, opath in sorted(orig_files.items()):
        entry = {"file": base, "original": opath, "ours": our_files.get(base), "declarations": 0}
        report["files"].append(entry)
        if base not in our_files:
            report["differences"].append({"file": base, "kind": "file missing", "key": base})
            continue
        o = parse_header(opath)
        u = parse_header(our_files[base])
        entry["declarations"] = len(o.decls)
        report["declarations"] += len(o.decls)
        for key in o.order:
            if key not in u.decls:
                report["differences"].append({"file": base, "kind": "missing", "key": key, "original": o.decls[key]})
            elif o.decls[key] != u.decls[key]:
                report["differences"].append({"file": base, "kind": "changed", "key": key, "original": o.decls[key],
                                              "ours": u.decls[key]})
            elif o.names[key] != u.names[key]:
                report["name_differences"].append({"file": base, "kind": "parameter names", "key": key,
                                                   "original": o.names[key], "ours": u.names[key]})
        for key in u.order:
            if key not in o.decls:
                report["differences"].append({"file": base, "kind": "extra", "key": key, "ours": u.decls[key]})
    report["extra_headers"] = sorted(set(our_files) - set(orig_files))
    failed = bool(report["differences"]) or (strict and bool(report["name_differences"]))
    return report, failed


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    default_ours = os.path.join(os.path.dirname(here), "Sources", "STKouyuEngine", "include", "STKouyuEngine")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("positional", nargs="*", metavar="PATH",
                    help="ORIGINAL [OURS], the same as --original ORIGINAL --ours OURS; with --original or --ours "
                         "given, positional paths are further original headers")
    ap.add_argument("--original", action="append",
                    help="original header directory or file (repeatable), e.g. STKouyuEngine.framework/Headers")
    ap.add_argument("--ours", help="headers of this package (default: %s)" % default_ours)
    ap.add_argument("--strict", action="store_true", help="also fail on parameter name differences")
    ap.add_argument("--json", help="write the full report as JSON")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args(argv)
    originals = list(a.original or [])
    ours = a.ours
    if a.positional:
        if originals or ours:
            # with --original or --ours given, positional paths are further original headers
            originals.extend(a.positional)
        elif len(a.positional) <= 2:
            # the documented short form: ORIGINAL [OURS]
            originals = [a.positional[0]]
            if len(a.positional) == 2:
                ours = a.positional[1]
        else:
            ap.error("give ORIGINAL [OURS], or --original PATH (repeatable) with --ours PATH")
    if not originals:
        ap.error("the original headers are required (--original PATH or a positional PATH)")
    a.original = originals
    a.ours = ours or default_ours
    try:
        orig = collect(a.original)
        if not orig:
            print("no .h files under --original", file=sys.stderr)
            return 2
        if not os.path.isdir(a.ours):
            print("--ours is not a directory: %s" % a.ours, file=sys.stderr)
            return 2
        report, failed = compare(orig, a.ours, a.strict)
    except FileNotFoundError as e:
        print("headers-diff: no such file or directory: %s" % e, file=sys.stderr)
        return 2
    except (ParseError, ValueError, IndexError) as e:
        print("headers-diff: cannot parse: %s" % e, file=sys.stderr)
        return 2
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
    if not a.quiet:
        for e in report["files"]:
            print("%-26s %4d declarations  %s" % (e["file"], e["declarations"], e["ours"] or "(missing in ours)"))
        for d in report["differences"]:
            print("DIFF %-8s %s: %s" % (d["kind"], d["file"], d["key"]))
            if "original" in d:
                print("       original: %s" % d["original"])
            if "ours" in d:
                print("       ours:     %s" % d["ours"])
        for d in report["name_differences"]:
            print("%s parameter names %s: %s %s -> %s" % ("DIFF" if a.strict else "note", d["file"], d["key"],
                                                         d["original"], d["ours"]))
        if report["extra_headers"]:
            print("headers only in this package (not compared): %s" % ", ".join(report["extra_headers"]))
    print("headers-diff: %d files, %d declarations compared, %d differences, %d parameter name differences%s" % (
        len(report["files"]), report["declarations"], len(report["differences"]), len(report["name_differences"]),
        " (strict)" if a.strict else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
