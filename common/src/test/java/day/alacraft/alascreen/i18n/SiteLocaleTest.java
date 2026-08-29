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
    void unservedLanguagesFallBackToEnglish() {
        assertEquals("en", SiteLocale.of("pt_br"));
        assertEquals("en", SiteLocale.of("zh_cn"));
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
