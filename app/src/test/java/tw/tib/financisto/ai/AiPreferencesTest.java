package tw.tib.financisto.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AiPreferencesTest {

    @Test
    public void normalizeBaseUrl_acceptsWhatPeoplePaste() {
        String want = "http://100.64.0.1:8080/v1";
        assertEquals(want, AiPreferences.normalizeBaseUrl("  http://100.64.0.1:8080/v1/  "));
        assertEquals(want, AiPreferences.normalizeBaseUrl("http://100.64.0.1:8080/v1/chat/completions"));
        assertEquals(want, AiPreferences.normalizeBaseUrl("http://100.64.0.1:8080/v1/models"));
        assertEquals("沒寫協定補 http", want, AiPreferences.normalizeBaseUrl("100.64.0.1:8080/v1"));
        assertEquals("https 照留", "https://llm.example.com/v1",
                AiPreferences.normalizeBaseUrl("https://llm.example.com/v1"));
        assertEquals("", AiPreferences.normalizeBaseUrl("   "));
        assertEquals("", AiPreferences.normalizeBaseUrl(null));
    }
}
