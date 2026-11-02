import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A coding agent for an elf, locked down: what it reads includes other people's words (a feed, an email, a chat), so
 * it gets no tools (built in or MCP), none of your settings, and an empty folder of its own. Its answer is only text,
 * for the elf to check and save; it can't do anything.
 *
 * <p>
 * Run by the House Elves runner (0.43.0 or later), it asks the coding agent the elf's settings chose (Claude Code,
 * Codex, pi), through ELF_ASK, which locks each one down and answers as {@code claude -p --output-format json} does.
 * Run by hand, it asks Claude Code itself.
 */
public class Claude {

    /** The coding agent's answer to the prompt, or null (and why, on stderr) if it failed. */
    static String ask(String prompt, int minutes) {
        try {
            String elfAsk = System.getenv("ELF_ASK");
            boolean viaRunner = elfAsk != null && !elfAsk.isBlank();
            Process p = new ProcessBuilder(viaRunner ? List.of(elfAsk, "--minutes", String.valueOf(minutes))
                    : List.of("claude", "-p", "--model", "sonnet", "--tools", "", "--strict-mcp-config", "--setting-sources", ""))
                    .directory(Files.createTempDirectory("elf-claude-").toFile()).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (var os = p.getOutputStream()) {
                os.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(minutes, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                System.err.println("The coding agent took too long");
                return null;
            }
            if (p.exitValue() != 0) {
                System.err.println("The coding agent failed: " + err.get(10, TimeUnit.SECONDS).strip());
                return null;
            }
            String answer = out.get(10, TimeUnit.SECONDS).strip();
            // ELF_ASK answers in Claude Code's JSON: the text is its result.
            return viaRunner ? new ObjectMapper().readTree(answer).path("result").asText("").strip() : answer;
        } catch (Exception e) {
            System.err.println("Coding agent error: " + e.getMessage());
            return null;
        }
    }

    static String read(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
