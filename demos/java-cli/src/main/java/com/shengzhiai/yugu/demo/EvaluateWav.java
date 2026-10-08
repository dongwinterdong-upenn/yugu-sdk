package com.shengzhiai.yugu.demo;

import com.shengzhiai.yugu.StreamListener;
import com.shengzhiai.yugu.StreamSession;
import com.shengzhiai.yugu.YuguClient;
import com.shengzhiai.yugu.errors.YuguException;
import com.shengzhiai.yugu.model.EvalResult;
import com.shengzhiai.yugu.model.EvaluateConfig;
import com.shengzhiai.yugu.model.SentenceScore;
import com.shengzhiai.yugu.model.Warning;
import com.shengzhiai.yugu.model.WordScore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates a WAV file and prints the scores.
 *
 * <pre>
 * YUGU_APP_KEY=... YUGU_SECRET_KEY=... ./mvnw -q compile exec:java -Dexec.args="audio.wav 今天天气很好"
 * </pre>
 *
 * Arguments: {@code <wav> [referenceText] [--core sentence] [--lang zh-CN] [--stream]}. Credentials come
 * from YUGU_APP_KEY and YUGU_SECRET_KEY (or YUGU_TOKEN); YUGU_BASE_URL overrides the platform address.
 */
public final class EvaluateWav {
    private EvaluateWav() {
    }

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        String core = EvaluateConfig.CORE_SENTENCE;
        String lang = "zh-CN";
        boolean stream = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--core":
                    core = args[++i];
                    break;
                case "--lang":
                    lang = args[++i];
                    break;
                case "--stream":
                    stream = true;
                    break;
                default:
                    positional.add(args[i]);
            }
        }
        if (positional.isEmpty()) {
            System.err.println("usage: EvaluateWav <wav> [referenceText] [--core sentence] [--lang zh-CN] [--stream]");
            System.exit(2);
        }
        Path wav = Paths.get(positional.get(0));
        String refText = positional.size() > 1 ? positional.get(1) : "今天天气很好";
        EvaluateConfig config = new EvaluateConfig(core, refText, lang).includeReport(true);

        YuguClient.Builder builder = YuguClient.builder();
        String token = System.getenv("YUGU_TOKEN");
        if (token != null && !token.isEmpty()) {
            builder.token(token);
        } else {
            builder.apiKey(System.getenv("YUGU_APP_KEY"), System.getenv("YUGU_SECRET_KEY"));
        }
        String base = System.getenv("YUGU_BASE_URL");
        if (base != null && !base.isEmpty()) {
            builder.baseUrl(base);
        }

        try (YuguClient client = builder.build()) {
            EvalResult result = stream ? streamFile(client, wav, config) : client.evaluate(wav, config);
            print(result);
        } catch (YuguException e) {
            System.err.println("evaluation failed: " + e.getCategory() + " code=" + e.getCode()
                    + " retryable=" + e.isRetryable() + " " + e.getRawMessage());
            System.exit(1);
        }
    }

    /** Streams the PCM of the file in 20 ms frames, the way a live source would. */
    private static EvalResult streamFile(YuguClient client, Path wav, EvaluateConfig config) throws Exception {
        byte[] bytes = Files.readAllBytes(wav);
        int dataOffset = 44;
        for (int i = 12; i + 8 <= bytes.length; ) {
            String id = new String(bytes, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (bytes[i + 4] & 0xFF) | (bytes[i + 5] & 0xFF) << 8 | (bytes[i + 6] & 0xFF) << 16 | (bytes[i + 7] & 0xFF) << 24;
            if ("data".equals(id)) {
                dataOffset = i + 8;
                break;
            }
            i += 8 + size + (size & 1);
        }
        StreamSession session = client.streamEvaluate(config, new StreamListener() {
            @Override
            public void onReconnecting(int attempt, long delayMs, YuguException cause) {
                System.out.println("reconnecting " + attempt + " in " + delayMs + " ms: " + cause.getRawMessage());
            }

            @Override
            public void onResult(EvalResult result) {
                System.out.println("stream result received");
            }

            @Override
            public void onError(YuguException error) {
                System.out.println("stream failed: " + error.getMessage());
            }
        });
        for (int off = dataOffset; off < bytes.length; off += 640) {
            session.sendAudio(Arrays.copyOfRange(bytes, off, Math.min(bytes.length, off + 640)));
        }
        session.end();
        return session.result().get(5, TimeUnit.MINUTES);
    }

    private static void print(EvalResult r) {
        System.out.println("recordId       " + r.getRecordId());
        System.out.println("overall        " + r.getOverall());
        System.out.println("pronunciation  " + r.getDims().getPronunciation());
        System.out.println("fluency        " + r.getDims().getFluency());
        System.out.println("integrity      " + r.getDims().getIntegrity());
        System.out.println("tone           " + r.getDims().getTone());
        System.out.println("rhythm         " + r.getDims().getRhythm());
        for (SentenceScore s : r.getSentences()) {
            System.out.println("sentence       " + s.getSentence() + "  " + s.getOverall());
        }
        for (WordScore w : r.getWords()) {
            System.out.println("  " + w.getWord() + "  " + w.getScores().getOverall() + "  " + w.getReadStatus());
        }
        for (Warning w : r.getWarnings()) {
            System.out.println("warning        " + w.getCode() + " " + w.getMessage());
        }
        for (Warning w : r.getLocalWarnings()) {
            System.out.println("precheck       " + w.getCode() + " " + w.getMessage());
        }
        System.out.println("idempotencyKey " + r.getIdempotencyKey() + (r.isReplayed() ? " (replayed)" : ""));
    }
}
