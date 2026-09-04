package day.alacraft.alascreen.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which page the link points at.
 *
 * <p>Small, and load-bearing: the link is the whole product, and a Russian
 * player handed an English page is being shown someone else's version of their
 * own screenshot.
 */
class SiteLocaleTest {

    @Test
    void mapsEveryLanguageTheSiteServes() {
        assertEquals("en", SiteLocale.of("en_us"));
        assertEquals("ru", SiteLocale.of("ru_ru"));
        assertEquals("uk", SiteLocale.of("uk_ua"));
        assertEquals("de", SiteLocale.of("de_de"));
        assertEquals("fr", SiteLocale.of("fr_fr"));
        assertEquals("es", SiteLocale.of("es_es"));
        assertEquals("ja", SiteLocale.of("ja_jp"));
        assertEquals("zh", SiteLocale.of("zh_cn"));
    }

    @Test
    void ukrainianIsUkNotUa() {
        // The site's own rule, and an easy one to get wrong: the language code
        // is uk, the country code is ua, and the URL wants the language.
        assertEquals("uk", SiteLocale.of("uk_ua"));
    }

    @Test
    void regionalVariantsReachTheirLanguage() {
        // Clients really do report these, and falling back to English for an
        // Austrian player would be a silent downgrade.
        assertEquals("de", SiteLocale.of("de_at"));
        assertEquals("es", SiteLocale.of("es_mx"));
        assertEquals("en", SiteLocale.of("en_gb"));
        assertEquals("fr", SiteLocale.of("fr_ca"));
    }

    @Test
    void traditionalChineseFallsBackToTheSimplifiedPage() {
        // The site has no zh-Hant version. Simplified still beats English for
        // a Chinese-reading player, so zh_tw and zh_hk join zh_cn rather than
        // falling all the way back to en.
        assertEquals("zh", SiteLocale.of("zh_tw"));
        assertEquals("zh", SiteLocale.of("zh_hk"));
    }

    @Test
    void unservedLanguagesFallBackToEnglish() {
        assertEquals("en", SiteLocale.of("pt_br"));
        assertEquals("en", SiteLocale.of("lol_us"));
    }

    @Test
    void toleratesTheShapesAClientCanActuallySend() {
        assertEquals("ru", SiteLocale.of("RU_ru"));
        assertEquals("ru", SiteLocale.of("ru-RU"));
        assertEquals("ru", SiteLocale.of("  ru_ru  "));
        assertEquals("ru", SiteLocale.of("ru"));
    }

    @Test
    void missingLanguageIsEnglishRatherThanACrash() {
        assertEquals("en", SiteLocale.of(null));
        assertEquals("en", SiteLocale.of(""));
        assertEquals("en", SiteLocale.of("   "));
    }
}
