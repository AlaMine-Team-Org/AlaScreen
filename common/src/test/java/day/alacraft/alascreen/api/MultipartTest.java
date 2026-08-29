package day.alacraft.alascreen.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire format of an upload.
 *
 * <p>Worth testing precisely because nothing else can catch it: a body that is
 * subtly malformed comes back as a 422 from the site, with no stack trace and
 * nothing in the game log to say which of the six parts was wrong.
 */
class MultipartTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static UploadRequest request(String fileName) {
        return new UploadRequest(fileName, PNG_MAGIC, 1920, 1080, "ru", "26.2", "fabric");
    }

    private static String text(Multipart.Body body) {
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void carriesEveryFieldTheSiteRecords() {
        String body = text(Multipart.build(request("shot.png"), "alascreen/0.1.0"));

        assertTrue(body.contains("name=\"source\""), "source part");
        assertTrue(body.contains("alascreen/0.1.0"));
        assertTrue(body.contains("name=\"mc_version\""));
        assertTrue(body.contains("26.2"));
        assertTrue(body.contains("name=\"loader\""));
        assertTrue(body.contains("fabric"));
        assertTrue(body.contains("name=\"locale\""));
        assertTrue(body.contains("name=\"width\""));
        assertTrue(body.contains("1920"));
        assertTrue(body.contains("name=\"height\""));
        assertTrue(body.contains("1080"));
        assertTrue(body.contains("name=\"file\"; filename=\"shot.png\""));
        assertTrue(body.contains("Content-Type: image/png"));
    }

    @Test
    void opensAndClosesWithTheBoundary() {
        Multipart.Body built = Multipart.build(request("shot.png"), "src");
        String body = text(built);

        assertTrue(body.startsWith("--" + built.boundary() + "\r\n"),
                "the body must open with the boundary");
        assertTrue(body.endsWith("--" + built.boundary() + "--\r\n"),
                "a body without the closing delimiter is read as truncated");
    }

    @Test
    void keepsTheImageBytesIntact() {
        byte[] body = Multipart.build(request("shot.png"), "src").bytes();

        // The PNG signature must survive byte for byte: any charset conversion
        // on the way in would corrupt every image the mod ever sent.
        int at = indexOf(body, PNG_MAGIC);
        assertTrue(at >= 0, "the PNG signature is missing from the body");
    }

    @Test
    void omitsPartsWithNoValue() {
        String body = text(Multipart.build(request("shot.png"), ""));

        assertFalse(body.contains("name=\"source\""),
                "an empty source must be left out, not sent as an empty string");
    }

    @Test
    void boundaryIsDifferentEveryTime() {
        String first = Multipart.build(request("a.png"), "src").boundary();
        String second = Multipart.build(request("a.png"), "src").boundary();

        assertNotEquals(first, second);
    }

    @Test
    void fileNameCannotInjectHeaders() {
        // A crafted name is the classic way a file upload becomes request
        // smuggling: a newline would end the Content-Disposition line and let
        // everything after it be read as its own header.
        //
        // What matters is that no CR, LF or quote survives INTO the name. The
        // words themselves are harmless once they cannot break the line, so
        // asserting their absence would test the wrong property and go red
        // against a sanitiser that is doing its job.
        String hostile = "evil\"\r\nContent-Type: text/html\r\n\r\n<script>.png";

        String cleaned = Multipart.sanitiseFileName(hostile);

        assertFalse(cleaned.contains("\r"), "carriage return survived the sanitiser");
        assertFalse(cleaned.contains("\n"), "line feed survived the sanitiser");
        assertFalse(cleaned.contains("\""), "an unescaped quote would close the filename early");

        String body = text(Multipart.build(request(hostile), "src"));

        // The file part still declares exactly one content type, and it is ours.
        assertEquals(1, countOccurrences(body, "Content-Type: image/png"));
        assertFalse(body.contains("\r\nContent-Type: text/html"),
                "the injected header must not appear at the start of a line");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);

        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }

        return count;
    }

    @Test
    void sanitiserReplacesQuotesAndSlashes() {
        assertEquals("a_b_c_d.png", Multipart.sanitiseFileName("a\"b\\c\nd.png"));
    }

    @Test
    void sanitiserFallsBackWhenNothingUsableIsLeft() {
        assertEquals("screenshot.png", Multipart.sanitiseFileName("   "));
        assertEquals("screenshot.png", Multipart.sanitiseFileName(null));
    }

    @Test
    void sanitiserKeepsTheExtensionOnAnAbsurdlyLongName() {
        String name = "x".repeat(400) + ".png";

        String cleaned = Multipart.sanitiseFileName(name);

        assertEquals(120, cleaned.length());
        assertTrue(cleaned.endsWith(".png"),
                "trimming from the wrong end would throw away the extension");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
