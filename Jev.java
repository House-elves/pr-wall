import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * TypeSafe's jev, over plain HTTP: one POST per PR with every question in it. Questions are "noul" (yes/no, answered
 * with P(yes)) or "choice" (answered with the option it picks, a probability per option and a confidence). Answers
 * are kept as jev gave them: nothing here rounds, combines or judges them.
 */
public class Jev {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The property is for tests, to point it at a stub: the desk never sets it. */
    static final String URL = System.getProperty("pr-wall.jev-url", "https://api.typesafe.ai/v1/systemone");
    static final int MAX_ANSWER_BYTES = 1_000_000;

    /** jev said the submission is over what it takes: not worth sending again until the PR changes. */
    static class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }
    }

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    final String apiKey;
    final String model;

    Jev(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    /** The request body: the model, the input as "state", and the questions keyed by id. */
    static ObjectNode body(String model, String input, JsonNode questions) {
        ObjectNode body = JSON.createObjectNode().put("model", model).put("state", input);
        ObjectNode qs = body.putObject("questions");
        for (JsonNode q : questions) {
            ObjectNode out = qs.putObject(q.path("id").asText()).put("instructions", q.path("text").asText());
            if (q.path("type").asText().equals("yes-no")) {
                out.put("type", "noul");
                out.putObject("criteria").put("true", q.path("yes").asText("")).put("false", q.path("no").asText(""));
            } else {
                out.put("type", "choice");
                out.set("criteria", q.path("options"));
            }
        }
        return body;
    }

    /** jev's answers, checked: every question answered, every probability between 0 and 1. */
    JsonNode ask(String input, JsonNode questions) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "pr-wall (House Elves)")
                .POST(HttpRequest.BodyPublishers.ofString(body(model, input, questions).toString())).build();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String text;
        try (var in = res.body()) {
            text = new String(in.readNBytes(MAX_ANSWER_BYTES), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new IOException("TypeSafe didn't accept the API key. Check it in PR wall's settings on the desk");
        }
        if (res.statusCode() == 400 && text.contains("max_tokens_exceeded")) throw new TooLarge("jev says the diff is too large");
        if (res.statusCode() != 200) throw new IOException("jev answered " + res.statusCode() + ": " + clip(text, 300));
        JsonNode answer = JSON.readTree(text);
        for (JsonNode q : questions) {
            String id = q.path("id").asText();
            JsonNode a = answer.path("answers").path(id);
            if (q.path("type").asText().equals("yes-no")) {
                probability(id, a.path("noul"));
            } else {
                if (!q.path("options").has(a.path("choice").asText())) throw new IOException("jev's answer to " + id + " isn't one of its options");
                a.path("probabilities").forEach(p -> probability(id, p));
            }
        }
        return answer;
    }

    static void probability(String id, JsonNode p) {
        if (!p.isNumber() || p.asDouble() < -1e-6 || p.asDouble() > 1 + 1e-6) {
            throw new IllegalStateException("jev's answer to " + id + " has a probability that isn't one: " + clip(p.toString(), 40));
        }
    }

    static String clip(String s, int max) {
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
