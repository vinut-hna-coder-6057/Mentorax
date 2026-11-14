package com.alumni.alumni_connect.dto;

import java.math.BigDecimal;
import java.util.List;

public record StructuredEvidenceExtraction(
        String schemaVersion,
        long evidenceId,
        int evidenceVersion,
        String documentSha256,
        String responseHash,
        String analysisStatus,
        String documentClassification,
        BigDecimal classificationConfidence,
        List<Provenance> classificationProvenance,
        String ocrStatus,
        List<Field> fields,
        List<Statement> statements,
        List<String> warnings) {

    public record Field(
            int ordinal,
            String name,
            String status,
            String value,
            List<String> candidateValues,
            BigDecimal confidence,
            BigDecimal modelConfidence,
            String extractionMethod,
            List<Provenance> provenance) {}

    public record Statement(int ordinal, String kind, String text, List<Provenance> provenance) {}

    public record Provenance(
            long sourceEvidenceId,
            int evidenceVersion,
            int pageNumber,
            String textExcerpt,
            String extractionMethod,
            BigDecimal sourceConfidence,
            BoundingBox boundingBox) {}

    public record BoundingBox(
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height) {}
}
