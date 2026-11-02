import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/pr-wall/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml; secrets live only here. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "pr-wall");
    static final Path FILE = DIR.resolve("config");

    String repo;
    String githubUser;
    List<String> labels;
    boolean skipDrafts;
    int maxPrs;
    int maxNew;
    int maxChars;
    /** Empty when not set: the dry run works without it, a saved run doesn't. */
    String apiKey;
    String model;
    boolean sumUp;
    /** Bot accounts that are known automation, like "dependabot": a bot not on it shows as an agent. */
    List<String> automation;

    static Config load() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        Config c = new Config();
        c.repo = raw.getOrDefault("REPO", "quarkusio/quarkus");
        c.githubUser = raw.getOrDefault("GITHUB_USER", "");
        c.labels = Arrays.stream(raw.getOrDefault("LABELS", "").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        c.skipDrafts = Boolean.parseBoolean(raw.getOrDefault("SKIP_DRAFTS", "true"));
        c.maxPrs = Integer.parseInt(raw.getOrDefault("MAX_PRS", "100"));
        c.maxNew = Integer.parseInt(raw.getOrDefault("MAX_NEW", "25"));
        c.maxChars = Integer.parseInt(raw.getOrDefault("MAX_CHARS", "60000"));
        c.apiKey = raw.getOrDefault("TYPESAFE_API_KEY", "");
        c.model = raw.getOrDefault("JEV_MODEL", "jev-latest");
        c.sumUp = Boolean.parseBoolean(raw.getOrDefault("SUM_UP", "false"));
        c.automation = Arrays.stream(raw.getOrDefault("AUTOMATION", "dependabot, renovate").split(","))
                .map(s -> Provenance.account(s.strip())).filter(s -> !s.isEmpty()).toList();
        if (!c.repo.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}")) throw new IllegalStateException("Set the repo as owner/name in PR wall's settings on the desk");
        if (c.githubUser.isBlank()) throw new IllegalStateException("Set the GitHub account in PR wall's settings on the desk");
        if (c.model.isBlank() || !c.model.matches("[A-Za-z0-9._-]{1,60}")) throw new IllegalStateException("The jev model is a name like jev-latest");
        return c;
    }
}
