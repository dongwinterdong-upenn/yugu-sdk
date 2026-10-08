import com.fasterxml.jackson.databind.JsonNode;
import com.shengzhiai.yugu.YuguClient;
import com.shengzhiai.yugu.model.ReportResult;
import com.shengzhiai.yugu.model.TtsRequest;
import com.shengzhiai.yugu.model.TtsResult;

public class TtsAndReport {
    public static void main(String[] args) {
        try (YuguClient client = YuguClient.builder()
                .apiKey(System.getenv("YUGU_APP_KEY"), System.getenv("YUGU_SECRET_KEY"))
                .build()) {
            // [START tts]
            TtsResult tts = client.tts(new TtsRequest("你好世界", "zh-CN", "xiaoyan").format("mp3"));
            System.out.println("示范音 " + tts.getAbsoluteUrl() + "，时长 " + tts.getDuration() + " 秒");
            // [END tts]
            // [START report]
            ReportResult report = client.getReport(args[0]);
            JsonNode data = report.getData();
            System.out.println("总分 " + data.path("score").path("overall").asDouble());
            System.out.println("点评 " + data.path("report").path("summary").asText());
            // [END report]
        }
    }
}
