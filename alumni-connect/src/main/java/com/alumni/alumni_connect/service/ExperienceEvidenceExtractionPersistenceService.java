package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionCandidate;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionField;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionProvenance;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionStatement;
import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionWarning;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionCandidateRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionFieldRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionProvenanceRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionStatementRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionWarningRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ExperienceEvidenceExtractionPersistenceService {
    private static final String SCHEMA_VERSION = "mentorax.document-understanding.v1";
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

    private final ExperienceEvidenceExtractionRepository extractionRepository;
    private final ExperienceEvidenceExtractionFieldRepository fieldRepository;
    private final ExperienceEvidenceExtractionCandidateRepository candidateRepository;
    private final ExperienceEvidenceExtractionStatementRepository statementRepository;
    private final ExperienceEvidenceExtractionProvenanceRepository provenanceRepository;
    private final ExperienceEvidenceExtractionWarningRepository warningRepository;

    public ExperienceEvidenceExtractionPersistenceService(
            ExperienceEvidenceExtractionRepository extractionRepository,
            ExperienceEvidenceExtractionFieldRepository fieldRepository,
            ExperienceEvidenceExtractionCandidateRepository candidateRepository,
            ExperienceEvidenceExtractionStatementRepository statementRepository,
            ExperienceEvidenceExtractionProvenanceRepository provenanceRepository,
            ExperienceEvidenceExtractionWarningRepository warningRepository) {
        this.extractionRepository = extractionRepository;
        this.fieldRepository = fieldRepository;
        this.candidateRepository = candidateRepository;
        this.statementRepository = statementRepository;
        this.provenanceRepository = provenanceRepository;
        this.warningRepository = warningRepository;
    }

    @Transactional
    public Long persist(ExperienceClaimEvidence evidence, StructuredEvidenceExtraction data) {
        validateIdentity(evidence, data);
        var existing = extractionRepository.findByEvidenceIdAndEvidenceVersionAndResponseHash(
                evidence.getId(), evidence.getEvidenceVersion(), data.responseHash());
        if (existing.isPresent()) return existing.get().getId();

        ExperienceEvidenceExtraction extraction = extractionRepository.saveAndFlush(
                new ExperienceEvidenceExtraction(
                        evidence.getId(),
                        evidence.getEvidenceVersion(),
                        data.schemaVersion(),
                        data.documentSha256(),
                        data.responseHash(),
                        data.analysisStatus(),
                        data.documentClassification(),
                        data.classificationConfidence(),
                        data.ocrStatus(),
                        LocalDateTime.now()));

        int provenanceOrdinal = 0;
        for (StructuredEvidenceExtraction.Field field : data.fields()) {
            ExperienceEvidenceExtractionField savedField = fieldRepository.save(
                    new ExperienceEvidenceExtractionField(
                            extraction.getId(),
                            field.ordinal(),
                            field.name(),
                            field.status(),
                            field.value(),
                            field.confidence(),
                            field.modelConfidence(),
                            field.extractionMethod()));
            for (int candidateOrdinal = 0; candidateOrdinal < field.candidateValues().size(); candidateOrdinal++) {
                candidateRepository.save(new ExperienceEvidenceExtractionCandidate(
                        savedField.getId(), candidateOrdinal, field.candidateValues().get(candidateOrdinal)));
            }
            for (StructuredEvidenceExtraction.Provenance provenance : field.provenance()) {
                saveProvenance(extraction, "FIELD", savedField.getId(), null,
                        provenanceOrdinal++, provenance);
            }
        }

        for (StructuredEvidenceExtraction.Statement statement : data.statements()) {
            ExperienceEvidenceExtractionStatement savedStatement = statementRepository.save(
                    new ExperienceEvidenceExtractionStatement(
                            extraction.getId(), statement.ordinal(), statement.kind(), statement.text()));
            for (StructuredEvidenceExtraction.Provenance provenance : statement.provenance()) {
                saveProvenance(extraction, "STATEMENT", null, savedStatement.getId(),
                        provenanceOrdinal++, provenance);
            }
        }

        for (StructuredEvidenceExtraction.Provenance provenance : data.classificationProvenance()) {
            saveProvenance(extraction, "CLASSIFICATION", null, null, provenanceOrdinal++, provenance);
        }
        for (int warningOrdinal = 0; warningOrdinal < data.warnings().size(); warningOrdinal++) {
            warningRepository.save(new ExperienceEvidenceExtractionWarning(
                    extraction.getId(), warningOrdinal, data.warnings().get(warningOrdinal)));
        }
        return extraction.getId();
    }

    @Transactional(readOnly = true)
    public Optional<StructuredEvidenceExtraction> findLatest(Long evidenceId, Integer evidenceVersion) {
        if (evidenceId == null || evidenceId <= 0 || evidenceVersion == null || evidenceVersion <= 0) {
            return Optional.empty();
        }
        return extractionRepository
                .findFirstByEvidenceIdAndEvidenceVersionOrderByProcessedAtDescIdDesc(evidenceId, evidenceVersion)
                .map(this::toStructuredExtraction);
    }

    private StructuredEvidenceExtraction toStructuredExtraction(ExperienceEvidenceExtraction extraction) {
        Long extractionId = extraction.getId();
        List<ExperienceEvidenceExtractionProvenance> provenanceRows =
                provenanceRepository.findByExtractionIdOrderByProvenanceOrdinalAscIdAsc(extractionId);

        List<StructuredEvidenceExtraction.Field> fields =
                fieldRepository.findByExtractionIdOrderByFieldOrdinalAscIdAsc(extractionId).stream()
                        .map(field -> new StructuredEvidenceExtraction.Field(
                                field.getFieldOrdinal(),
                                field.getFieldName(),
                                field.getFieldStatus(),
                                field.getExtractedValue(),
                                candidateRepository.findByFieldIdOrderByCandidateOrdinalAscIdAsc(field.getId())
                                        .stream()
                                        .map(ExperienceEvidenceExtractionCandidate::getCandidateValue)
                                        .toList(),
                                field.getConfidence(),
                                field.getModelConfidence(),
                                field.getExtractionMethod(),
                                provenanceRows.stream()
                                        .filter(row -> "FIELD".equals(row.getRecordType())
                                                && field.getId().equals(row.getFieldId()))
                                        .map(ExperienceEvidenceExtractionPersistenceService::toProvenance)
                                        .toList()))
                        .toList();

        List<StructuredEvidenceExtraction.Statement> statements =
                statementRepository.findByExtractionIdOrderByStatementOrdinalAscIdAsc(extractionId).stream()
                        .map(statement -> new StructuredEvidenceExtraction.Statement(
                                statement.getStatementOrdinal(),
                                statement.getStatementKind(),
                                statement.getStatementText(),
                                provenanceRows.stream()
                                        .filter(row -> "STATEMENT".equals(row.getRecordType())
                                                && statement.getId().equals(row.getStatementId()))
                                        .map(ExperienceEvidenceExtractionPersistenceService::toProvenance)
                                        .toList()))
                        .toList();

        List<StructuredEvidenceExtraction.Provenance> classificationProvenance = provenanceRows.stream()
                .filter(row -> "CLASSIFICATION".equals(row.getRecordType()))
                .map(ExperienceEvidenceExtractionPersistenceService::toProvenance)
                .toList();
        List<String> warnings = warningRepository
                .findByExtractionIdOrderByWarningOrdinalAscIdAsc(extractionId).stream()
                .map(ExperienceEvidenceExtractionWarning::getWarningText)
                .toList();

        return new StructuredEvidenceExtraction(
                extraction.getSchemaVersion(),
                extraction.getEvidenceId(),
                extraction.getEvidenceVersion(),
                extraction.getDocumentSha256(),
                extraction.getResponseHash(),
                extraction.getAnalysisStatus(),
                extraction.getDocumentClassification(),
                extraction.getClassificationConfidence(),
                classificationProvenance,
                extraction.getOcrStatus(),
                fields,
                statements,
                warnings);
    }

    private static StructuredEvidenceExtraction.Provenance toProvenance(
            ExperienceEvidenceExtractionProvenance provenance) {
        boolean hasBoundingBox = provenance.getBoundingBoxX() != null
                || provenance.getBoundingBoxY() != null
                || provenance.getBoundingBoxWidth() != null
                || provenance.getBoundingBoxHeight() != null;
        StructuredEvidenceExtraction.BoundingBox box = null;
        if (hasBoundingBox) {
            if (provenance.getBoundingBoxX() == null || provenance.getBoundingBoxY() == null
                    || provenance.getBoundingBoxWidth() == null || provenance.getBoundingBoxHeight() == null) {
                throw new IllegalStateException("Persisted extraction provenance has an incomplete bounding box");
            }
            box = new StructuredEvidenceExtraction.BoundingBox(
                    provenance.getBoundingBoxX(),
                    provenance.getBoundingBoxY(),
                    provenance.getBoundingBoxWidth(),
                    provenance.getBoundingBoxHeight());
        }
        return new StructuredEvidenceExtraction.Provenance(
                provenance.getSourceEvidenceId(),
                provenance.getSourceEvidenceVersion(),
                provenance.getPageNumber(),
                provenance.getTextExcerpt(),
                provenance.getExtractionMethod(),
                provenance.getSourceConfidence(),
                box);
    }

    private void saveProvenance(
            ExperienceEvidenceExtraction extraction,
            String recordType,
            Long fieldId,
            Long statementId,
            int ordinal,
            StructuredEvidenceExtraction.Provenance provenance) {
        if (!extraction.getEvidenceId().equals(provenance.sourceEvidenceId())
                || !extraction.getEvidenceVersion().equals(provenance.evidenceVersion())) {
            throw new IllegalArgumentException("Extraction provenance does not match its evidence version");
        }
        StructuredEvidenceExtraction.BoundingBox box = provenance.boundingBox();
        provenanceRepository.save(new ExperienceEvidenceExtractionProvenance(
                extraction.getId(),
                recordType,
                fieldId,
                statementId,
                ordinal,
                provenance.sourceEvidenceId(),
                provenance.evidenceVersion(),
                provenance.pageNumber(),
                provenance.textExcerpt(),
                provenance.extractionMethod(),
                provenance.sourceConfidence(),
                box == null ? null : box.x(),
                box == null ? null : box.y(),
                box == null ? null : box.width(),
                box == null ? null : box.height()));
    }

    private static void validateIdentity(ExperienceClaimEvidence evidence, StructuredEvidenceExtraction data) {
        if (evidence == null || evidence.getId() == null || data == null
                || !SCHEMA_VERSION.equals(data.schemaVersion())
                || data.evidenceId() != evidence.getId()
                || data.evidenceVersion() != evidence.getEvidenceVersion()
                || data.evidenceVersion() <= 0
                || data.documentSha256() == null || !SHA256.matcher(data.documentSha256()).matches()
                || data.responseHash() == null || !SHA256.matcher(data.responseHash()).matches()
                || data.fields() == null || data.statements() == null
                || data.classificationProvenance() == null || data.warnings() == null
                || SensitiveEvidenceContentValidator.containsSensitiveContent(data)) {
            throw new IllegalArgumentException("Structured extraction does not match a persisted evidence version");
        }
        List<StructuredEvidenceExtraction.Provenance> provenance = new java.util.ArrayList<>(
                data.classificationProvenance());
        for (StructuredEvidenceExtraction.Field field : data.fields()) {
            if (field.candidateValues() == null || field.provenance() == null) {
                throw new IllegalArgumentException("Structured extraction field is incomplete");
            }
            provenance.addAll(field.provenance());
        }
        for (StructuredEvidenceExtraction.Statement statement : data.statements()) {
            if (statement.provenance() == null) {
                throw new IllegalArgumentException("Structured extraction statement is incomplete");
            }
            provenance.addAll(statement.provenance());
        }
        if (provenance.stream().anyMatch(item -> item == null
                || item.sourceEvidenceId() != data.evidenceId()
                || item.evidenceVersion() != data.evidenceVersion())) {
            throw new IllegalArgumentException("Structured extraction provenance has a different evidence identity");
        }
    }
}
