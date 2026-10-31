package com.alumni.alumni_connect.service;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ExperienceEvidencePdfValidator {
    private static final byte[] EOF_MARKER = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    public boolean isValid(byte[] pdf) {
        if (pdf == null || !startsWithHeader(pdf) || !endsWithEofMarker(pdf)) {
            return false;
        }

        try (RandomAccessReadBuffer input = new RandomAccessReadBuffer(pdf);
             PDDocument document = new StrictPdfParser(input).parse()) {
            return !document.isEncrypted()
                    && document.getDocumentCatalog() != null
                    && document.getNumberOfPages() > 0;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean startsWithHeader(byte[] pdf) {
        byte[] header = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (pdf.length < header.length) {
            return false;
        }
        for (int index = 0; index < header.length; index++) {
            if (pdf[index] != header[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean endsWithEofMarker(byte[] pdf) {
        int markerIndex = -1;
        for (int index = pdf.length - EOF_MARKER.length; index >= 0; index--) {
            boolean matches = true;
            for (int markerOffset = 0; markerOffset < EOF_MARKER.length; markerOffset++) {
                if (pdf[index + markerOffset] != EOF_MARKER[markerOffset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                markerIndex = index;
                break;
            }
        }
        if (markerIndex < 0) {
            return false;
        }
        for (int index = markerIndex + EOF_MARKER.length; index < pdf.length; index++) {
            if (!isPdfWhitespace(pdf[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean isPdfWhitespace(byte value) {
        return value == 0 || value == 9 || value == 10 || value == 12 || value == 13 || value == 32;
    }

    private static final class StrictPdfParser extends PDFParser {
        private StrictPdfParser(RandomAccessReadBuffer input) throws IOException {
            super(input);
            setLenient(false);
        }
    }
}
