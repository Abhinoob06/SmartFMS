package com.smartfms.util;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Binary-safe parser for multipart/form-data HTTP request bodies.
 */
public final class MultipartParser {
    private MultipartParser() {}

    public record FilePart(String fieldName, String filename, String contentType, byte[] data) {}

    public record ParsedMultipart(Map<String, String> fields, List<FilePart> files) {}

    public static ParsedMultipart parse(byte[] body, String contentTypeHeader) {
        String boundary = extractBoundary(contentTypeHeader);
        if (boundary == null) {
            throw new IllegalArgumentException("Missing multipart boundary");
        }
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        Map<String, String> fields = new LinkedHashMap<>();
        List<FilePart> files = new ArrayList<>();

        int pos = indexOf(body, boundaryBytes, 0);
        if (pos < 0) return new ParsedMultipart(fields, files);
        pos += boundaryBytes.length;

        while (pos < body.length) {
            // Check for closing "--"
            if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-') {
                break;
            }
            // Skip CRLF after boundary
            if (pos + 1 < body.length && body[pos] == '\r' && body[pos + 1] == '\n') {
                pos += 2;
            } else if (pos < body.length && body[pos] == '\n') {
                pos += 1;
            }

            // Find end of headers (\r\n\r\n)
            int headerEnd = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}, pos);
            int sepLen = 4;
            if (headerEnd < 0) {
                headerEnd = indexOf(body, new byte[]{'\n', '\n'}, pos);
                sepLen = 2;
            }
            if (headerEnd < 0) break;

            String headersRaw = new String(body, pos, headerEnd - pos, StandardCharsets.UTF_8);
            int dataStart = headerEnd + sepLen;

            int nextBoundary = indexOf(body, boundaryBytes, dataStart);
            if (nextBoundary < 0) break;

            // Trim trailing CRLF before boundary
            int dataEnd = nextBoundary;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            } else if (dataEnd >= 1 && body[dataEnd - 1] == '\n') {
                dataEnd -= 1;
            }

            byte[] partData = Arrays.copyOfRange(body, dataStart, dataEnd);
            String disposition = null;
            String partContentType = "application/octet-stream";
            for (String line : headersRaw.split("\\r?\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String hName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String hVal = line.substring(colon + 1).trim();
                    if (hName.equals("content-disposition")) disposition = hVal;
                    else if (hName.equals("content-type")) partContentType = hVal;
                }
            }

            if (disposition != null) {
                String name = extractParam(disposition, "name");
                String filename = extractParam(disposition, "filename");
                if (filename != null && !filename.isEmpty()) {
                    // Strip any client path prefix
                    int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
                    if (slash >= 0) filename = filename.substring(slash + 1);
                    files.add(new FilePart(name, filename, partContentType, partData));
                } else if (name != null) {
                    fields.put(name, new String(partData, StandardCharsets.UTF_8));
                }
            }

            pos = nextBoundary + boundaryBytes.length;
        }

        return new ParsedMultipart(fields, files);
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = trimmed.substring(9).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static String extractParam(String header, String paramName) {
        String needle = paramName + "=\"";
        int idx = header.indexOf(needle);
        if (idx >= 0) {
            int start = idx + needle.length();
            int end = header.indexOf('"', start);
            if (end > start) return header.substring(start, end);
        }
        return null;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
