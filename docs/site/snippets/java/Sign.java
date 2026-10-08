import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class Sign {
    static String sign(Map<String, String> params, String secretKey) throws Exception {
        // 丢弃 null 与空串，按键名字典序拼成 k1=v1&k2=v2，不做 URL 编码
        String payload = new TreeMap<>(params).entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public static void main(String[] args) throws Exception {
        System.out.println(sign(Map.of("coreType", "sent.eval.cn", "language", "zh-CN", "refText", "北京你好"), "test_secret_key_123"));
        // 输出 A+6uVB/D7khxQEt8tzgCNjMUC1QtQQd1UF+NCYVYZqE=
    }
}
