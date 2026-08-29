package day.alacraft.alascreen.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the {@code multipart/form-data} body of an upload.
 *
 * <p>Hand-rolled because the JDK has no multipart writer and pulling in an HTTP
 * library for one request would be the largest dependency in the mod. It is
 * thirty lines and the format has not changed since 1998.
 *
 * <p>Its own class rather than a private method on the client so it can be
 * tested without a network stack: this is the one piece of new wire format in
 * the mod, and a body that is subtly malformed fails as a confusing 422 from the
 * site rather than as anything a stack trace would explain.
 */
final class Multipart {

    /** A finished body and the boundary that describes it. */
    record Body(String boundary, byte[] bytes) {
    }

    private Multipart() {
    }

    static Body build(UploadRequest request, String source) {
        // Random boundary: it must not occur in the payload, and PNG bytes are
        // arbitrary. 64 bits of randomness plus a clock reading makes an
        // accidental collision less likely than a disk error.
        String boundary = "AlaScreen" + Long.toHexString(ThreadLocalRandom.current().nextLong())
                + Long.toHexString(System.nanoTime());

        ByteArrayOutputStream out = new ByteArrayOutputStream(request.png().length + 1024);

        try {
            field(out, boundary, "source", source);
            field(out, boundary, "mc_version", request.minecraftVersion());
            field(out, boundary, "loader", request.loader());
            field(out, boundary, "locale", request.siteLocale());
            field(out, boundary, "width", Integer.toString(request.width()));
            field(out, boundary, "height", Integer.toString(request.height()));

            out.write(ascii("--" + boundary + "\r\n"));
            out.write(ascii("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + sanitiseFileName(request.fileName()) + "\"\r\n"));
            out.write(ascii("Content-Type: image/png\r\n\r\n"));
            out.write(request.png());
            out.write(ascii("\r\n"));

            out.write(ascii("--" + boundary + "--\r\n"));
        } catch (IOException e) {
            // ByteArrayOutputStream does not do IO, so this is unreachable — but
            // the checked exception has to go somewhere, and swallowing it would
            // hide a real bug if the stream type ever changed.
            throw new ApiException(new ApiError(0, ApiErrorCode.INTERNAL, "",
                    "Could not assemble the upload body: " + e.getMessage(), Optional.empty()), e);
        }

        return new Body(boundary, out.toByteArray());
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }

        out.write(ascii("--" + boundary + "\r\n"));
        out.write(ascii("Content-Disposition: form-data; name=\"" + name + "\"\r\n"));
        // Explicitly UTF-8 rather than left to the server's guess: a locale never
        // needs it, but this is the same helper a file name would go through.
        out.write(ascii("Content-Type: text/plain; charset=UTF-8\r\n\r\n"));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(ascii("\r\n"));
    }

    /**
     * Strip anything that would break out of the quoted filename or the header.
     *
     * <p>The name comes from the local filesystem, so it can hold quotes,
     * newlines and backslashes. A newline here would let a crafted file name
     * inject its own multipart headers — the classic way a file upload turns
     * into a request-smuggling bug.
     */
    static String sanitiseFileName(String name) {
        if (name == null) {
            return "screenshot.png";
        }

        String cleaned = name.replaceAll("[\\r\\n\"\\\\]", "_").trim();

        if (cleaned.isEmpty()) {
            return "screenshot.png";
        }

        // Keep the tail rather than the head: the extension is the part a server
        // looks at, and it is at the end.
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
