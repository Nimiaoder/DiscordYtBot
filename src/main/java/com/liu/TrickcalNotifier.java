package com.liu;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrickcalNotifier {

    private static final String GAME_ID = "trickcal_tw";
    private static final String GAME_BASE_ID = "113406";
    // 預設縮圖網址
    private static final String IMAGE_URL = "https://creator-static.biligames.com/ff11301a1d9124da59e5168700515115bea08aed39b4f58bc22c01bfff109d79.png";

    public static void main(String[] args) throws Exception {
        String webhookUrl = System.getenv("DISCORD_WEBHOOK_URL");
        String dbUrl = System.getenv("NEON_DB_URL");

        if (webhookUrl == null || dbUrl == null) {
            System.err.println("錯誤：環境變數 DISCORD_WEBHOOK_URL, NEON_DB_URL 未設定完整！");
            System.exit(1);
        }

        HttpClient client = HttpClient.newHttpClient();

        // 1. 呼叫台服 Bilibili 新聞 API
        String apiUrl = "https://l11-web-api.biligames.com/game/news/page?page_num=1&page_size=1&game_base_id=" + GAME_BASE_ID + "&show_position=1&lang=zh";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "application/json")
                .header("Origin", "https://trickcal.biligames.com")
                .header("Referer", "https://trickcal.biligames.com/")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("Bilibili API 呼叫失敗，HTTP 狀態碼: " + response.statusCode());
            return;
        }

        String json = response.body();

        // 2. 使用 Regex 提取 list中第一筆資料的 news_id, title 與 publish_time
        String newsId = extractJsonValue(json, "news_id");
        String title = extractJsonValue(json, "title");
        String publishTime = extractJsonValue(json, "publish_time");

        if (newsId.isEmpty() || title.isEmpty()) {
            System.err.println("解析 API JSON 失敗，內容為: " + json);
            return;
        }

        // 組合成官方公告詳情網址
        String noticeUrl = "https://trickcal.biligames.com/news/zh-tw/?news_detail_id=" + newsId + "#news_detail_id=" + newsId;

        // 3. 查上次儲存的最新公告ID
        String lastNewsId = getDbLastNoticeId(dbUrl, GAME_ID);

        System.out.println("當前最新公告 ID: " + newsId);
        System.out.println("當前最新公告標題: " + title);
        System.out.println("發布時間: " + publishTime);
        System.out.println("上次紀錄公告 ID: " + lastNewsId);

        // 4. 比對是否有新公告
        if (!newsId.equals(lastNewsId)) {
            System.out.println("偵測到最新公告！發送 Discord 通知中...");

            // 發送 Discord 通知
            sendDiscordNotification(client, webhookUrl, title, noticeUrl, publishTime);

            // 更新DB
            saveDbLastNoticeId(dbUrl, GAME_ID, newsId);
        } else {
            System.out.println("目前沒有新公告上架。");
        }
    }

    // 查上次儲存的最新公告
    private static String getDbLastNoticeId(String dbUrl, String gameId) {
        String sql = "SELECT last_notice_id FROM trickcal WHERE game_id = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, gameId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String lastId = rs.getString("last_notice_id");
                    return lastId != null ? lastId : "";
                }
            }
        } catch (Exception e) {
            System.err.println("讀取 DB 失敗: " + e.getMessage());
        }
        return "";
    }

    // 更新DB的last_notice_id
    private static void saveDbLastNoticeId(String dbUrl, String gameId, String noticeId) {
        String sql = """
            INSERT INTO trickcal (game_id, last_notice_id, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (game_id) 
            DO UPDATE SET last_notice_id = EXCLUDED.last_notice_id, updated_at = CURRENT_TIMESTAMP;
        """;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, gameId);
            stmt.setString(2, noticeId);
            stmt.executeUpdate();
            System.out.println("成功更新 DB 紀錄為: " + noticeId);
        } catch (Exception e) {
            System.err.println("寫入 DB 失敗: " + e.getMessage());
        }
    }

    // 發送 Discord Embed 訊息
    private static void sendDiscordNotification(HttpClient client, String webhookUrl, String title, String noticeUrl, String publishTime) throws Exception {
        String jsonPayload = String.format("""
        {
          "content": "**《嘟嘟臉惡作劇》發布了最新公告！**",
          "embeds": [
            {
              "title": "%s",
              "url": "%s",
              "color": 16753920,
              "image": {
                "url": "%s"
              },
              "fields": [
                {
                  "name": "發布時間",
                  "value": "%s",
                  "inline": true
                }
              ],
              "footer": {
                "text": "嘟嘟臉惡作劇 官方公告"
              }
            }
          ]
        }
        """, escapeJson(title), noticeUrl, IMAGE_URL, publishTime);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Discord Webhook 發送狀態碼: " + response.statusCode());
    }

    // 提取 JSON key 對應的值
    private static String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?([^\",}]+)\"?");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}