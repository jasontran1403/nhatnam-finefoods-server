package com.nhatnam.server.utils;

import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
@Log4j2
public class TelegramService {

    private static final String BOT_TOKEN = "8533906420:AAFTgzHTwxT1ocZNgMbHnf8147Mm5OTlMzM";
    private static final String BASE_URL = "https://api.telegram.org/bot" + BOT_TOKEN;

    // Mapping tên group → chatId (dễ mở rộng sau này)
    private static final Map<String, Long> GROUP_CHAT_IDS = Map.of(
           "pos",     -1003620498133L,
            "seller",  -1003893473390L,
            "admin",  -1003893473390L
    );

//    private static final Map<String, Long> GROUP_CHAT_IDS = Map.of(
//            "pos",     -5134733925L,
//            "seller",  -5134733925L,
//            "admin",  -5134733925L
//    );

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Gửi document theo tên group ("pos", "seller", ...)
     * Đây là method khuyến nghị dùng cho invoice và các trường hợp cố định.
     */
    public void sendDocumentByGroupName(String groupName, byte[] fileBytes, String filename, String caption, Integer replyToMessageId) {
        String groupKey = groupName.toLowerCase().trim();
        Long chatId = GROUP_CHAT_IDS.get(groupKey);

        if (chatId == null) {
            throw new IllegalArgumentException(
                    "Không tìm thấy chatId cho group: '" + groupName + "'. " +
                            "Các group hỗ trợ: " + GROUP_CHAT_IDS.keySet()
            );
        }

        sendDocument(chatId, fileBytes, filename, caption, replyToMessageId);
    }

    /**
     * Gửi text message đến bất kỳ chat_id nào
     */
    public void sendMessage(long chatId, String message) {
        String url = BASE_URL + "/sendMessage";
        String payload = String.format(
                "{\"chat_id\": %d, \"text\": \"%s\", \"parse_mode\": \"HTML\"}",
                chatId, escapeJson(message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            restTemplate.postForObject(url, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception e) {
            log.error("[Telegram] sendMessage error (chat={}): {}", chatId, e.getMessage());
        }
    }

    /**
     * Gửi document đến bất kỳ chat_id nào (method chính, linh hoạt)
     *
     * @param chatId            chat_id của group/channel/user
     * @param fileBytes         nội dung file PDF/Excel/...
     * @param filename          tên file hiển thị trên Telegram
     * @param caption           nội dung caption (hỗ trợ HTML)
     * @param replyToMessageId  (tùy chọn) reply vào message cụ thể
     */
    public void sendDocument(long chatId, byte[] fileBytes, String filename, String caption, Integer replyToMessageId) {
        String url = BASE_URL + "/sendDocument";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("chat_id", String.valueOf(chatId));
        body.add("caption", caption != null ? caption : "");
        if (replyToMessageId != null) {
            body.add("reply_to_message_id", replyToMessageId);
        }

        // File resource
        ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("document", fileResource);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        try {
            restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            log.error("[Telegram] sendDocument error (chat={}, file={}): {}", chatId, filename, e.getMessage(), e);
            throw new RuntimeException("Gửi file lên Telegram thất bại: " + e.getMessage());
        }
    }

    // ── Helper ──
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}