package tw.tib.financisto.ai;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 雲端 STT：WAV 檔 → 文字。同步方法，須在背景執行緒呼叫。
 *
 * 兩種請求形狀，由 provider 決定（端點設死在 AiPreferences.sttEndpointUrl）：
 * - Gemini：原生 generateContent，inline base64 音訊 + 轉寫指示，key 走 x-goog-api-key。
 * - Groq / OpenAI：multipart POST audio/transcriptions，key 走 Bearer。
 *   language=zh + 繁中 prompt 偏置（whisper 系對 zh 預設常吐簡體）。
 */
public class SpeechTranscriber {

    private static final MediaType WAV = MediaType.parse("audio/wav");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** whisper 系的 prompt 偏置：讓輸出走繁體與台灣用語。 */
    private static final String WHISPER_PROMPT = "以下是台灣使用者的繁體中文記帳語句。";

    private static final String GEMINI_INSTRUCTION =
            "把這段語音逐字轉寫成繁體中文（台灣用語）文字。"
            + "只輸出轉寫結果本身，不要加任何說明、翻譯或標記。聽不清楚的部分依讀音給最可能的字。";

    private final AiPreferences prefs;
    private final OkHttpClient client;

    public SpeechTranscriber(AiPreferences prefs) {
        this.prefs = prefs;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    public static class TranscribeException extends Exception {
        public TranscribeException(String message) { super(message); }
        public TranscribeException(String message, Throwable cause) { super(message, cause); }
    }

    public String transcribe(File wavFile) throws TranscribeException {
        String provider = prefs.getSttProvider();
        String url = AiPreferences.sttEndpointUrl(provider);
        String key = prefs.getSttApiKey();
        if (key == null || key.isEmpty()) {
            throw new TranscribeException("尚未設定該服務的 API key");
        }
        String text = AiPreferences.PROVIDER_GEMINI.equals(provider)
                ? transcribeGemini(wavFile, url, key)
                : transcribeOpenAiCompatible(wavFile, url, key);
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) throw new TranscribeException("沒辨識出文字");
        return text;
    }

    // ---------------- OpenAI 相容（Groq / OpenAI / 自訂） ----------------

    private String transcribeOpenAiCompatible(File wavFile, String url, String key)
            throws TranscribeException {
        String model = prefs.getSttModel();
        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", RequestBody.create(WAV, wavFile))
                .addFormDataPart("language", "zh")
                .addFormDataPart("prompt", WHISPER_PROMPT)
                .addFormDataPart("response_format", "json");
        if (model != null && !model.isEmpty()) {
            body.addFormDataPart("model", model);
        }
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + key)
                .post(body.build())
                .build();
        String responseBody = execute(request);
        try {
            return new JSONObject(responseBody).optString("text", "");
        } catch (JSONException e) {
            throw new TranscribeException("解析回應失敗：" + shorten(responseBody), e);
        }
    }

    // ---------------- Gemini 原生 ----------------

    private String transcribeGemini(File wavFile, String baseUrl, String key)
            throws TranscribeException {
        String model = prefs.getSttModel();
        if (model == null || model.isEmpty()) {
            model = AiPreferences.defaultSttModel(AiPreferences.PROVIDER_GEMINI);
        }
        // 模型清單（OpenAI 相容層）回的 id 是 models/gemini-…，原生 path 只要後半
        if (model.startsWith("models/")) model = model.substring("models/".length());
        String b = baseUrl;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        String url = b + "/models/" + model + ":generateContent";

        String audioB64;
        try {
            audioB64 = readBase64(wavFile);
        } catch (IOException e) {
            throw new TranscribeException("讀錄音檔失敗：" + e.getMessage(), e);
        }

        String requestJson;
        try {
            JSONObject inline = new JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", audioB64);
            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("text", GEMINI_INSTRUCTION))
                    .put(new JSONObject().put("inline_data", inline));
            JSONObject content = new JSONObject().put("parts", parts);
            requestJson = new JSONObject()
                    .put("contents", new JSONArray().put(content))
                    .put("generationConfig", new JSONObject().put("temperature", 0))
                    .toString();
        } catch (JSONException e) {
            throw new TranscribeException("組請求失敗", e);
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", key)
                .post(RequestBody.create(JSON, requestJson))
                .build();
        String responseBody = execute(request);
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                throw new TranscribeException("回應無 candidates：" + shorten(responseBody));
            }
            JSONArray parts = candidates.getJSONObject(0)
                    .getJSONObject("content").optJSONArray("parts");
            StringBuilder sb = new StringBuilder();
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    sb.append(parts.getJSONObject(i).optString("text", ""));
                }
            }
            return sb.toString();
        } catch (JSONException e) {
            throw new TranscribeException("解析回應失敗：" + shorten(responseBody), e);
        }
    }

    // ---------------- 共用 ----------------

    private String execute(Request request) throws TranscribeException {
        String responseBody;
        int code;
        try (Response response = client.newCall(request).execute()) {
            code = response.code();
            responseBody = response.body() != null ? response.body().string() : "";
        } catch (IOException e) {
            throw new TranscribeException("網路錯誤：" + e.getMessage(), e);
        }
        if (code < 200 || code >= 300) {
            throw new TranscribeException("API 回傳 " + code + "：" + shorten(responseBody));
        }
        return responseBody;
    }

    private static String readBase64(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("檔案讀取中斷");
                off += n;
            }
        }
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
