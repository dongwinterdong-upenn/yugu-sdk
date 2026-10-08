// SPDX-License-Identifier: Apache-2.0
//
// API comparison of two jars, signatures only (never method bodies). Two modes:
//
//   java ApiDiff.java <original.jar> <compat.jar> [--report FILE] [--allow-extra] [--allow-class NAME]...
//   java ApiDiff.java --japicmp-xml <japicmp.xml> [--report FILE] [--allow-class NAME]...
//
// Mode 1, javap:
// For every public class of the original jar, the compat jar must have the same class header
// (modifiers, generic signature, super types) and exactly the same public and protected members
// (modifiers, generic signatures, throws clauses, constant values), compared as printed by
// "javap -protected -constants". The modifier "native" is ignored (the compat layer implements
// native methods in Java). Package-private classes such as anonymous classes ($1, $2) are not API.
// Public classes of the compat jar that the original jar lacks are errors, except the ones passed
// with --allow-class (com.stkouyu.YuguCompat is always allowed). --allow-extra turns extra members
// and classes into warnings. Exit code 0 when nothing is missing or changed, 1 otherwise, 2 on usage
// errors. Requires JDK 11 or newer.
//
// Mode 2, strict reading of a japicmp XML report (japicmp -a protected, without -m): inside the
// packages of the original jar, every class, method, constructor, field, annotation, modifier and
// generic element must be UNCHANGED; the only tolerated difference is a newer class file version
// (the compat layer ships Java 8 bytecode). New classes are errors except the allowed ones.

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class ApiDiff {

    static final class ClassApi {
        final String name;
        final String header;
        final List<String> members = new ArrayList<>();

        ClassApi(String name, String header) {
            this.name = name;
            this.header = header;
        }

        boolean isPublic() {
            return header.startsWith("public ") || header.startsWith("protected ");
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        Path report = null;
        boolean allowExtra = false;
        Set<String> allowedClasses = new LinkedHashSet<>();
        allowedClasses.add("com.stkouyu.YuguCompat");
        Path japicmpXml = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--japicmp-xml") && i + 1 < args.length) {
                japicmpXml = Paths.get(args[++i]);
            } else if (a.equals("--report") && i + 1 < args.length) {
                report = Paths.get(args[++i]);
            } else if (a.equals("--allow-extra")) {
                allowExtra = true;
            } else if (a.equals("--allow-class") && i + 1 < args.length) {
                allowedClasses.add(args[++i]);
            } else if (a.startsWith("--")) {
                usage("unknown option " + a);
            } else {
                positional.add(a);
            }
        }
        if (japicmpXml != null) {
            if (!positional.isEmpty()) {
                usage("--japicmp-xml takes no jars");
            }
            System.exit(checkJapicmp(japicmpXml, allowedClasses, report));
        }
        if (positional.size() != 2) {
            usage("two jars expected");
        }
        Path original = Paths.get(positional.get(0));
        Path compat = Paths.get(positional.get(1));
        for (Path p : new Path[] {original, compat}) {
            if (!Files.isRegularFile(p)) {
                usage("not a file: " + p);
            }
        }

        List<String> origClasses = classNames(original);
        Set<String> packages = new TreeSet<>();
        for (String c : origClasses) {
            packages.add(c.contains(".") ? c.substring(0, c.lastIndexOf('.')) : "");
        }
        List<String> compatClasses = new ArrayList<>();
        for (String c : classNames(compat)) {
            String pkg = c.contains(".") ? c.substring(0, c.lastIndexOf('.')) : "";
            if (packages.contains(pkg)) {
                compatClasses.add(c);
            }
        }

        Map<String, ClassApi> o = dump(original, origClasses);
        Map<String, ClassApi> c = dump(compat, compatClasses);

        StringBuilder out = new StringBuilder();
        out.append("api-diff (javap -protected -constants)\n");
        out.append("original: ").append(original).append('\n');
        out.append("compat:   ").append(compat).append("\n\n");

        int classes = 0;
        int members = 0;
        int missing = 0;
        int changed = 0;
        int extra = 0;
        int skipped = 0;
        for (ClassApi oc : o.values()) {
            if (!oc.isPublic()) {
                skipped++;
                continue;
            }
            classes++;
            members += oc.members.size();
            ClassApi cc = c.get(oc.name);
            if (cc == null || !cc.isPublic()) {
                missing += 1 + oc.members.size();
                out.append("MISSING CLASS ").append(oc.header).append('\n');
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (!norm(oc.header).equals(norm(cc.header))) {
                changed++;
                problems.add("  CHANGED HEADER\n    - " + oc.header + "\n    + " + cc.header);
            }
            Set<String> om = new LinkedHashSet<>();
            for (String m : oc.members) {
                om.add(norm(m));
            }
            Set<String> cm = new LinkedHashSet<>();
            for (String m : cc.members) {
                cm.add(norm(m));
            }
            Map<String, List<String>> missingByKey = new TreeMap<>();
            Map<String, List<String>> extraByKey = new TreeMap<>();
            for (String m : om) {
                if (!cm.contains(m)) {
                    missingByKey.computeIfAbsent(key(m, oc.name), k -> new ArrayList<>()).add(m);
                }
            }
            for (String m : cm) {
                if (!om.contains(m)) {
                    extraByKey.computeIfAbsent(key(m, oc.name), k -> new ArrayList<>()).add(m);
                }
            }
            for (Map.Entry<String, List<String>> e : missingByKey.entrySet()) {
                List<String> ex = extraByKey.remove(e.getKey());
                if (ex != null) {
                    for (String m : e.getValue()) {
                        changed++;
                        problems.add("  CHANGED " + e.getKey() + "\n    - " + m + "\n    + " + String.join("\n    + ", ex));
                    }
                } else {
                    for (String m : e.getValue()) {
                        missing++;
                        problems.add("  MISSING " + m);
                    }
                }
            }
            for (List<String> ex : extraByKey.values()) {
                for (String m : ex) {
                    extra++;
                    problems.add("  EXTRA   " + m);
                }
            }
            if (problems.isEmpty()) {
                out.append("OK    ").append(oc.name).append(" (").append(oc.members.size()).append(" members)\n");
            } else {
                out.append("DIFF  ").append(oc.name).append('\n');
                for (String p : problems) {
                    out.append(p).append('\n');
                }
            }
        }
        for (ClassApi cc : c.values()) {
            if (!cc.isPublic() || o.containsKey(cc.name)) {
                continue;
            }
            if (allowedClasses.contains(cc.name)) {
                out.append("ALLOWED EXTRA CLASS ").append(cc.name).append('\n');
            } else {
                extra++;
                out.append("EXTRA CLASS ").append(cc.header).append('\n');
            }
        }
        int elements = classes + members;
        boolean failed = missing > 0 || changed > 0 || (!allowExtra && extra > 0);
        out.append('\n');
        out.append(String.format("compared %d public classes and %d public or protected members (%d elements); "
                        + "%d package-private classes skipped%n", classes, members, elements, skipped));
        out.append(String.format("missing %d, changed %d, extra %d%s%n", missing, changed, extra,
                allowExtra && extra > 0 ? " (extra allowed)" : ""));
        out.append(failed ? "RESULT: FAIL\n" : "RESULT: OK\n");
        System.out.print(out);
        if (report != null) {
            Path parent = report.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(report, out.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.exit(failed ? 1 : 0);
    }

    static void usage(String msg) {
        System.err.println("api-diff: " + msg);
        System.err.println("usage: java ApiDiff.java <original.jar> <compat.jar> [--report FILE] [--allow-extra] [--allow-class NAME]");
        System.err.println("       java ApiDiff.java --japicmp-xml <japicmp.xml> [--report FILE] [--allow-class NAME]");
        System.exit(2);
    }

    /** Binary class names of a jar (com.a.B$C), without module-info and META-INF versions. */
    static List<String> classNames(Path jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")
                        && !n.endsWith("package-info.class")) {
                    names.add(n.substring(0, n.length() - 6).replace('/', '.'));
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    static Map<String, ClassApi> dump(Path jar, List<String> classes) {
        Map<String, ClassApi> result = new LinkedHashMap<>();
        if (classes.isEmpty()) {
            return result;
        }
        Optional<ToolProvider> tool = ToolProvider.findFirst("javap");
        if (!tool.isPresent()) {
            System.err.println("api-diff: javap not found, a JDK (not a JRE) is required");
            System.exit(2);
        }
        List<String> a = new ArrayList<>();
        a.add("-protected");
        a.add("-constants");
        a.add("-cp");
        a.add(jar.toAbsolutePath().toString());
        a.addAll(classes);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        tool.get().run(new PrintWriter(out), new PrintWriter(err), a.toArray(new String[0]));
        ClassApi current = null;
        for (String line : out.toString().split("\\R")) {
            if (line.startsWith("Compiled from") || line.trim().isEmpty()) {
                continue;
            }
            if (!line.startsWith(" ") && line.endsWith("{")) {
                String header = line.substring(0, line.length() - 1).trim();
                current = new ClassApi(className(header), header);
                result.put(current.name, current);
            } else if (line.equals("}")) {
                current = null;
            } else if (current != null) {
                current.members.add(line.trim());
            }
        }
        return result;
    }

    static String className(String header) {
        String[] t = header.split("\\s+");
        for (int i = 0; i < t.length - 1; i++) {
            if (t[i].equals("class") || t[i].equals("interface") || t[i].equals("enum")) {
                String n = t[i + 1];
                int g = n.indexOf('<');
                return g >= 0 ? n.substring(0, g) : n;
            }
        }
        return header;
    }

    /** Ignores "native" and runs of white space. */
    static String norm(String s) {
        return s.replaceAll("\\bnative\\s+", "").replaceAll("\\s+", " ").trim();
    }

    /** Element key: field name, or method name with parameter list. */
    static String key(String member, String owner) {
        String m = member.endsWith(";") ? member.substring(0, member.length() - 1) : member;
        int paren = m.indexOf('(');
        if (paren < 0) {
            int eq = m.indexOf(" = ");
            String decl = eq >= 0 ? m.substring(0, eq) : m;
            return "field " + decl.substring(decl.lastIndexOf(' ') + 1);
        }
        String head = m.substring(0, paren);
        String name = head.substring(head.lastIndexOf(' ') + 1);
        int close = m.indexOf(')', paren);
        String params = m.substring(paren, close + 1).replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "");
        if (name.equals(owner)) {
            return "constructor " + params;
        }
        return "method " + name + params;
    }

    // ---------------------------------------------------------------- mode 2: japicmp XML

    static int checkJapicmp(Path xml, Set<String> allowedClasses, Path report) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(xml.toFile());
        NodeList classes = doc.getElementsByTagName("class");
        Set<String> packages = new TreeSet<>();
        for (int i = 0; i < classes.getLength(); i++) {
            Element c = (Element) classes.item(i);
            if (!"NEW".equals(c.getAttribute("changeStatus"))) {
                packages.add(pkg(c.getAttribute("fullyQualifiedName")));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("api-diff strict japicmp check: ").append(xml).append('\n');
        int checked = 0;
        int problems = 0;
        Set<String> versionNotes = new TreeSet<>();
        for (int i = 0; i < classes.getLength(); i++) {
            Element c = (Element) classes.item(i);
            String name = c.getAttribute("fullyQualifiedName");
            if (!packages.contains(pkg(name))) {
                continue;
            }
            String status = c.getAttribute("changeStatus");
            if (allowedClasses.contains(name)) {
                out.append("ALLOWED ").append(status).append(" CLASS ").append(name).append('\n');
                continue;
            }
            checked++;
            if (!"UNCHANGED".equals(status) && !"MODIFIED".equals(status)) {
                problems++;
                out.append(status).append(" CLASS ").append(name).append('\n');
                continue;
            }
            List<String> found = new ArrayList<>();
            walk(c, "", found, versionNotes);
            NodeList changes = c.getElementsByTagName("compatibilityChange");
            for (int k = 0; k < changes.getLength(); k++) {
                found.add("compatibility change " + ((Element) changes.item(k)).getAttribute("type"));
            }
            if (!found.isEmpty()) {
                problems += found.size();
                out.append("DIFF ").append(name).append('\n');
                for (String p : found) {
                    out.append("  ").append(p).append('\n');
                }
            }
        }
        for (String v : versionNotes) {
            out.append("NOTE ").append(v).append('\n');
        }
        out.append(String.format("checked %d classes in %s: %d differences%n", checked, packages, problems));
        out.append(problems == 0 ? "RESULT: OK\n" : "RESULT: FAIL\n");
        System.out.print(out);
        if (report != null) {
            Path parent = report.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(report, out.toString().getBytes(StandardCharsets.UTF_8));
        }
        return problems == 0 ? 0 : 1;
    }

    private static void walk(Element e, String path, List<String> found, Set<String> versionNotes) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) {
                continue;
            }
            Element k = (Element) n;
            String tag = k.getTagName();
            String label = describe(k);
            String status = k.getAttribute("changeStatus");
            if (tag.equals("classFileFormatVersion")) {
                if (!status.isEmpty() && !"UNCHANGED".equals(status)) {
                    versionNotes.add("class file version " + k.getAttribute("majorVersionOld") + " -> "
                            + k.getAttribute("majorVersionNew") + " (tolerated: Java 8 bytecode)");
                }
                continue;
            }
            if (!status.isEmpty() && !"UNCHANGED".equals(status)) {
                found.add(status + " " + (path.isEmpty() ? "" : path + " / ") + label);
            }
            walk(k, path.isEmpty() ? label : path + " / " + label, found, versionNotes);
        }
    }

    private static String describe(Element k) {
        String tag = k.getTagName();
        for (String attr : new String[] {"name", "fullyQualifiedName", "newValue", "oldValue", "newType", "oldType"}) {
            String v = k.getAttribute(attr);
            if (!v.isEmpty() && !"n.a.".equals(v)) {
                return tag + " " + v;
            }
        }
        return tag;
    }

    private static String pkg(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }
}
