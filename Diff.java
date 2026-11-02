import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A PR's unified diff, split per file, and what of it jev is sent. Noise (lock files, generated and binary files) is
 * listed but its content left out; when the rest is still over the limit, the files the repo's context says to spare
 * first (docs) lose their content too. Code is never cut part way: a PR still over the limit is "too large", rather
 * than classified on half a diff.
 */
public class Diff {

    /** One file's part of the diff. {@code text} is everything from its "diff --git" line up to the next. */
    record File(String path, String kind, String oldPath, boolean binary, String text) {
        String describe() {
            String d = switch (kind) {
                case "renamed" -> "renamed from " + oldPath;
                default -> kind;
            };
            return binary ? d + ", binary" : d;
        }
    }

    /** What jev is sent, and what was left out of it. */
    record Submission(String input, List<String> omitted, int testFiles, boolean fits) {
    }

    /** The repo's context: its text for jev, and the paths that are tests, noise, and spared first. */
    record Context(String text, List<Pattern> testPaths, List<Pattern> noisePaths, List<Pattern> spareFirst) {
        boolean isTest(String path) {
            return testPaths.stream().anyMatch(p -> p.matcher(path).find());
        }

        boolean isNoise(File f) {
            return f.binary() || noisePaths.stream().anyMatch(p -> p.matcher(f.path()).find());
        }

        boolean spare(String path) {
            return spareFirst.stream().anyMatch(p -> p.matcher(path).find());
        }
    }

    static Submission submission(List<File> files, Context context, int maxChars) {
        int testFiles = (int) files.stream().filter(f -> context.isTest(f.path())).count();
        List<String> omitted = new ArrayList<>(files.stream().filter(context::isNoise).map(File::path).toList());
        String input = input(files, context, testFiles, omitted);
        if (input.length() > maxChars) {
            files.stream().filter(f -> !omitted.contains(f.path()) && context.spare(f.path())).forEach(f -> omitted.add(f.path()));
            input = input(files, context, testFiles, omitted);
        }
        return new Submission(input, List.copyOf(omitted), testFiles, input.length() <= maxChars);
    }

    /**
     * The context, the facts we know for sure (so jev isn't asked them), the changed files, then the diff. The PR's
     * title and body are not sent: they say what the author meant, and the questions are about what the code does.
     */
    static String input(List<File> files, Context context, int testFiles, List<String> omitted) {
        long deleted = files.stream().filter(f -> f.kind().equals("deleted") && context.isTest(f.path())).count();
        StringBuilder s = new StringBuilder(context.text()).append("\n\nFacts, from the file paths: this PR ")
                .append(testFiles == 0 ? "adds or modifies no tests." : "adds or modifies " + testFiles + " test file" + (testFiles == 1 ? "" : "s")
                        + (deleted == 0 ? ", deleting none of them." : ", deleting " + deleted + " of them."))
                .append("\n\nChanged files:\n");
        for (File f : files) {
            s.append("- ").append(f.path()).append(" (").append(f.describe());
            if (omitted.contains(f.path())) s.append(", content left out of the diff below");
            s.append(")\n");
        }
        s.append("\nUnified diff:\n");
        files.stream().filter(f -> !omitted.contains(f.path())).forEach(f -> s.append(f.text()));
        return s.toString();
    }

    static List<File> parse(String diff) {
        List<File> files = new ArrayList<>();
        int start = -1;
        int at = 0;
        while (at < diff.length()) {
            int end = diff.indexOf('\n', at);
            end = end < 0 ? diff.length() : end + 1;
            if (diff.startsWith("diff --git ", at)) {
                if (start >= 0) files.add(file(diff.substring(start, at)));
                start = at;
            }
            at = end;
        }
        if (start >= 0) files.add(file(diff.substring(start)));
        return files;
    }

    /** Its paths come from the "---"/"+++" and rename lines, which git writes unambiguously, not the first line. */
    static File file(String text) {
        String oldPath = null, newPath = null, kind = "modified";
        boolean binary = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("@@")) break;
            if (line.startsWith("new file mode")) kind = "added";
            else if (line.startsWith("deleted file mode")) kind = "deleted";
            else if (line.startsWith("rename from ")) { kind = "renamed"; oldPath = unquote(line.substring(12)); }
            else if (line.startsWith("rename to ")) newPath = unquote(line.substring(10));
            else if (line.startsWith("--- ") && oldPath == null && !line.equals("--- /dev/null")) oldPath = strip(unquote(line.substring(4)), "a/");
            else if (line.startsWith("+++ ") && newPath == null && !line.equals("+++ /dev/null")) newPath = strip(unquote(line.substring(4)), "b/");
            else if (line.startsWith("Binary files ") || line.equals("GIT binary patch")) binary = true;
        }
        if (oldPath == null && newPath == null) {
            // No ---/+++ lines (a mode change, an empty or binary file): the first line's paths, equal unless renamed.
            String paths = text.substring("diff --git ".length(), Math.max("diff --git ".length(), text.indexOf('\n'))).strip();
            int b = paths.lastIndexOf(" b/");
            newPath = b >= 0 ? paths.substring(b + 3) : paths;
            if (newPath.endsWith("\"")) newPath = unquote("\"" + newPath);
        }
        String path = "deleted".equals(kind) ? (oldPath != null ? oldPath : newPath) : (newPath != null ? newPath : oldPath);
        return new File(path, kind, "renamed".equals(kind) ? oldPath : null, binary, text);
    }

    static String strip(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** Git writes a path with special characters as a C string, each non-ASCII byte as \NNN octal. */
    static String unquote(String s) {
        if (s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length() - 1) {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                continue;
            }
            char n = s.charAt(++i);
            if (n >= '0' && n <= '3' && i + 2 < s.length() - 1 && s.substring(i, i + 3).matches("[0-7]{3}")) {
                out.write(Integer.parseInt(s.substring(i, i + 3), 8));
                i += 2;
            } else {
                out.write(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"', '\\' -> n;
                    default -> n;
                });
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
