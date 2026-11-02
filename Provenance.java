import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who opened a PR, from what GitHub attests and what the author declared: never a guess from the code or the writing.
 * The wall reports it and nothing judges it: no sort, filter, score or summary reads it, and declaring AI assistance
 * must never count against a PR. "human" means nothing was declared, not that no AI was used.
 *
 * <ul>
 * <li>bot: a bot account on the automation list (Dependabot and the like).</li>
 * <li>agent: a bot account that isn't on the list.</li>
 * <li>human+ai: a person's PR whose description or commits declare AI assistance.</li>
 * <li>human: a person's PR that declares nothing.</li>
 * </ul>
 */
public class Provenance {

    static final ObjectMapper JSON = new ObjectMapper();

    /** AI tools as people name them in prose. Words that are also ordinary words or names (cursor, devin) aren't here. */
    static final String TOOL = "(claude(?: code)?|copilot|chatgpt|gpt-?\\d[\\w.]*|codex|gemini|junie|windsurf"
            + "|(?:an? |the )?(?:ai|llm)(?: tool| assistant| agent| model| coding assistant)?s?)";

    /** A declaration in prose: "Generated with Claude Code", "I used Copilot to...", "with the help of an AI assistant". */
    static final List<Pattern> PROSE = List.of(
            Pattern.compile("\\bgenerated (?:with|by|using) \\[?" + TOOL + "\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:i|we)(?:'ve| have)?\\b[^.\\n]{0,40}?\\b(?:used|use|using|leveraged|relied on|asked)\\b[^.\\n]{0,40}?\\b"
                    + TOOL + "\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:with (?:the )?help (?:of|from)|assisted by|with assistance from|help from|(?:written|created|drafted) (?:with|by))\\s+"
                    + TOOL + "\\b", Pattern.CASE_INSENSITIVE));

    /** A trailer that declares assistance whatever it names. */
    static final Pattern ASSISTED = Pattern.compile("^\\s*(?:assisted|generated)-by:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** Co-authored-by declares assistance only when the co-author is an AI tool, by its name or its maker's address. */
    static final Pattern CO_AUTHOR = Pattern.compile("^\\s*co-authored-by:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    static final Pattern AI_CO_AUTHOR = Pattern.compile(
            "^(?:claude|copilot|chatgpt|codex|cursor(?: agent)?|gemini|aider|junie|windsurf)\\b|@(?:anthropic\\.com|openai\\.com|cursor\\.com)|copilot",
            Pattern.CASE_INSENSITIVE);

    /** Code, quotes and HTML comments (a PR template's hints) declare nothing. */
    static final Pattern NOT_PROSE = Pattern.compile("```.*?```|`[^`\\n]*`|<!--.*?-->|^\\s*>.*$", Pattern.DOTALL | Pattern.MULTILINE);

    static final int MAX_DECLARED = 3;

    /**
     * What GitHub says about one head commit, kept until the PR gets a new one: the author's association with the repo,
     * the account type, the people whose commits a bot's PR carries, and what the commit messages declare.
     */
    static ObjectNode commitFacts(String sha, JsonNode pull, List<JsonNode> commits) {
        ObjectNode f = JSON.createObjectNode().put("sha", sha)
                .put("association", pull.path("author_association").asText(""))
                .put("userType", pull.path("user").path("type").asText(""));
        Set<String> people = new LinkedHashSet<>();
        ArrayNode declared = f.putArray("declared");
        for (JsonNode c : commits) {
            if (c.path("type").asText().equals("User") && !c.path("login").asText().isEmpty()) people.add(c.path("login").asText());
            for (ObjectNode d : declarations(c.path("message").asText(""), "commit " + c.path("sha").asText())) {
                if (declared.size() < MAX_DECLARED) declared.add(d);
            }
        }
        ArrayNode humans = f.putArray("humanCommits");
        people.stream().limit(5).forEach(humans::add);
        return f;
    }

    /** The PR's provenance from its author, its description (read fresh each run) and its commit facts. */
    static ObjectNode of(JsonNode pr, JsonNode facts, List<String> automation) {
        String login = pr.path("author").path("login").asText("");
        boolean bot = pr.path("author").path("is_bot").asBoolean() || facts.path("userType").asText().equals("Bot");
        ObjectNode p = JSON.createObjectNode();
        ArrayNode declared = p.putArray("declared");
        if (!bot) {
            for (ObjectNode d : declarations(pr.path("body").asText(""), "description")) if (declared.size() < MAX_DECLARED) declared.add(d);
            for (JsonNode d : facts.path("declared")) if (declared.size() < MAX_DECLARED) declared.add(d);
        }
        p.put("kind", bot ? (automation.contains(account(login)) ? "bot" : "agent") : declared.isEmpty() ? "human" : "human+ai");
        p.put("association", facts.path("association").asText(""));
        // A bot's PR that carries a person's commits isn't only the bot's.
        if (bot) p.set("humanCommits", facts.path("humanCommits"));
        return p;
    }

    /** "app/dependabot" (gh) and "dependabot[bot]" (the REST API) are the same account: "dependabot". */
    static String account(String login) {
        return login.toLowerCase(Locale.ROOT).replaceFirst("^app/", "").replaceFirst("\\[bot]$", "");
    }

    /** Each declaration in a text: where it is, the tool it names, and the line it's on, as the author wrote it. */
    static List<ObjectNode> declarations(String text, String where) {
        List<ObjectNode> out = new ArrayList<>();
        String prose = NOT_PROSE.matcher(text).replaceAll(" ");
        Matcher m = ASSISTED.matcher(text);
        while (m.find()) out.add(declared(where, m.group(1), m.group()));
        m = CO_AUTHOR.matcher(text);
        while (m.find()) if (AI_CO_AUTHOR.matcher(m.group(1).strip()).find()) out.add(declared(where, m.group(1).replaceAll("<.*", ""), m.group()));
        for (Pattern p : PROSE) {
            m = p.matcher(prose);
            if (m.find()) {
                out.add(declared(where, m.group(1), line(prose, m.start())));
                break;
            }
        }
        return out;
    }

    static ObjectNode declared(String where, String tool, String quote) {
        return JSON.createObjectNode().put("where", where).put("tool", Jev.clip(tool.strip(), 40))
                .put("quote", Jev.clip(quote.strip().replaceAll("\\s+", " "), 160));
    }

    static String line(String text, int at) {
        int from = text.lastIndexOf('\n', at) + 1, to = text.indexOf('\n', at);
        return text.substring(from, to < 0 ? text.length() : to);
    }
}
