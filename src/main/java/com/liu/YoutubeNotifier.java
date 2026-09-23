package com.liu;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeNotifier {

    private static final String LAST_VIDEO_FILE = "last_video_id.txt";

    public static void main(String[] args) throws Exception {
        String channelId = System.getenv("YOUTUBE_CHANNEL_ID");
        String webhookUrl = System.getenv("DISCORD_WEBHOOK_URL");
        String apiKey = System.getenv("YOUTUBE_API_KEY");

        if (channelId == null || webhookUrl == null || apiKey == null) {
            System.err.println("錯誤：環境變數 YOUTUBE_CHANNEL_ID, DISCORD_WEBHOOK_URL, YOUTUBE_API_KEY 未設定完整！");
            System.exit(1);
        }

        HttpClient client = HttpClient.newHttpClient();

        // 1. 先抓 RSS 取得最新 videoId
        String rssUrl = "https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId;
        HttpRequest rssRequest = HttpRequest.newBuilder()
                .uri(URI.create(rssUrl))
                .setHeader("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        HttpResponse<String> rssResponse = client.send(rssRequest, HttpResponse.BodyHandlers.ofString());
        if (rssResponse.statusCode() != 200) {
            System.err.println("無法讀取 RSS Feed，HTTP 狀態碼: " + rssResponse.statusCode());
            return;
        }

        Pattern entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
        Matcher entryMatcher = entryPattern.matcher(rssResponse.body());

        if (entryMatcher.find()) {
            String firstEntry = entryMatcher.group(1);
            Pattern idPattern = Pattern.compile("<yt:videoId>(.*?)</yt:videoId>");
            Matcher idMatcher = idPattern.matcher(firstEntry);

            if (idMatcher.find()) {
                String latestVideoId = idMatcher.group(1);
                String lastVideoId = readLastVideoId();

                System.out.println("當前最新影片 ID: " + latestVideoId);
                System.out.println("上次紀錄影片 ID: " + lastVideoId);

                // 偵測到新影片時才處理
                if (!latestVideoId.equals(lastVideoId)) {
                    System.out.println("偵測到新內容，正在呼叫 YouTube API 查詢詳細類型...");

                    // 2. 呼叫 YouTube Data API 查詢詳細影片狀態與分類
                    checkAndSendNotification(client, apiKey, webhookUrl, latestVideoId);

                    // 3. 更新紀錄檔
                    saveLastVideoId(latestVideoId);
                } else {
                    System.out.println("沒有新內容上傳。");
                }
            }
        }
    }

    private static void checkAndSendNotification(HttpClient client, String apiKey, String webhookUrl, String videoId) throws Exception {
        String apiUrl = String.format(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet,liveStreamingDetails&id=%s&key=%s",
                videoId, apiKey
        );

        HttpRequest apiRequest = HttpRequest.newBuilder().uri(URI.create(apiUrl)).GET().build();
        HttpResponse<String> apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

        if (apiResponse.statusCode() != 200) {
            System.err.println("YouTube API 呼叫失敗，狀態碼: " + apiResponse.statusCode());
            return;
        }

        String json = apiResponse.body();

        String title = escapeJson(extractJsonValue(json, "title"));
        String channelTitle = escapeJson(extractJsonValue(json, "channelTitle"));
        String liveBroadcastContent = extractJsonValue(json, "liveBroadcastContent"); // none, live, upcoming

        String videoUrl = "https://www.youtube.com/watch?v=" + videoId;
        String thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        // 分類判斷與 Discord Embed 樣式設定
        String statusPrefix;
        int embedColor; // 10 進位 RGB 色碼

        if ("live".equals(liveBroadcastContent)) {
            statusPrefix = "【正在直播中】";
            embedColor = 15158332; // 紅色 (0xE74C3C)
        } else if ("upcoming".equals(liveBroadcastContent)) {
            statusPrefix = "【直播 / 首播排程】";
            embedColor = 15844367; // 黃色/金色 (0xF1C40F)
        } else {
            statusPrefix = "【新影片發布】";
            embedColor = 3447003;  // 藍色 (0x3498DB)
        }

        // 組Discord Webhook JSON payload
        String jsonPayload = String.format("""
        {
          "content": "%s **%s** 發布了新內容！",
          "embeds": [
            {
              "title": "%s",
              "url": "%s",
              "color": %d,
              "author": {
                "name": "%s"
              },
              "image": {
                "url": "%s"
              },
              "footer": {
                "text": "YouTube 通知 Bot"
              }
            }
          ]
        }
        """, statusPrefix, channelTitle, title, videoUrl, embedColor, channelTitle, thumbnailUrl);

        System.out.println("發送jsonPayload: " + jsonPayload);
        sendDiscordWebhook(client, webhookUrl, jsonPayload);
    }

    private static String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"(.*?)\"");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String readLastVideoId() {
        try {
            Path path = Paths.get(LAST_VIDEO_FILE);
            if (Files.exists(path)) return Files.readString(path).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private static void saveLastVideoId(String videoId) {
        try {
            Files.writeString(Paths.get(LAST_VIDEO_FILE), videoId);
        } catch (Exception ignored) {}
    }

    private static void sendDiscordWebhook(HttpClient client, String webhookUrl, String jsonPayload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Discord Webhook 發送狀態碼: " + response.statusCode());
    }
}