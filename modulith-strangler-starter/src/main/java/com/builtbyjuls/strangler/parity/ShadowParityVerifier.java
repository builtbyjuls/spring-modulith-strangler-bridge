package com.builtbyjuls.strangler.parity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Deep recursive comparator for verifying payload parity between local and remote module executions.
 * Normalizes BigDecimal scale (e.g. 144.00 vs 144.000000) and produces concise JSONPath diff summaries.
 */
public class ShadowParityVerifier {

    private static final int MAX_DIFF_ENTRIES = 10;
    private final ObjectMapper objectMapper;

    /**
     * Outcome of a shadow parity comparison.
     *
     * @param matched     whether local and remote payloads match
     * @param diffSummary concise human-readable mismatch description, or null when matched
     */
    public record ParityOutcome(boolean matched, String diffSummary) {

        public static ParityOutcome match() {
            return new ParityOutcome(true, null);
        }

        public static ParityOutcome mismatch(String diffSummary) {
            return new ParityOutcome(false, diffSummary);
        }
    }

    public ShadowParityVerifier() {
        this(createDefaultObjectMapper());
    }

    public ShadowParityVerifier(ObjectMapper objectMapper) {
        if (objectMapper != null) {
            ObjectMapper copy = objectMapper.copy();
            copy.setNodeFactory(new com.fasterxml.jackson.databind.node.JsonNodeFactory(true));
            this.objectMapper = copy;
        } else {
            this.objectMapper = createDefaultObjectMapper();
        }
    }

    /**
     * Compares local and remote return objects by converting them to Jackson JsonNode trees.
     *
     * @param localResult  authoritative return object from local bean
     * @param remoteResult return object from remote HTTP bridge
     * @return ParityOutcome indicating whether payloads matched and any diff summary
     */
    public ParityOutcome verify(Object localResult, Object remoteResult) {
        JsonNode localNode = toJsonNode(localResult);
        JsonNode remoteNode = toJsonNode(remoteResult);

        List<String> differences = new ArrayList<>();
        compareNodes("$", localNode, remoteNode, differences);

        if (differences.isEmpty()) {
            return ParityOutcome.match();
        }

        String summary;
        if (differences.size() <= MAX_DIFF_ENTRIES) {
            summary = String.join("; ", differences);
        } else {
            List<String> head = differences.subList(0, MAX_DIFF_ENTRIES);
            summary = String.join("; ", head) + "; (" + (differences.size() - MAX_DIFF_ENTRIES) + " additional mismatches)";
        }

        return ParityOutcome.mismatch(summary);
    }

    /**
     * Convenience method comparing local and remote objects and building a complete ShadowParityResult.
     */
    public ShadowParityResult verify(
            String moduleName,
            String interfaceName,
            String methodName,
            Object localResult,
            Object remoteResult,
            long localDurationMs,
            long remoteDurationMs
    ) {
        ParityOutcome outcome = verify(localResult, remoteResult);
        return new ShadowParityResult(
                moduleName,
                interfaceName,
                methodName,
                outcome.matched(),
                localDurationMs,
                remoteDurationMs,
                outcome.diffSummary(),
                null,
                false
        );
    }

    private JsonNode toJsonNode(Object obj) {
        if (obj == null) {
            return NullNode.getInstance();
        }
        if (obj instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        return objectMapper.valueToTree(obj);
    }

    private void compareNodes(String path, JsonNode local, JsonNode remote, List<String> differences) {
        boolean localNull = local == null || local.isNull();
        boolean remoteNull = remote == null || remote.isNull();

        if (localNull && remoteNull) {
            return;
        }

        if (localNull) {
            differences.add(path + ": local=null vs remote=" + formatNode(remote));
            return;
        }

        if (remoteNull) {
            differences.add(path + ": local=" + formatNode(local) + " vs remote=null");
            return;
        }

        // Numeric comparison with BigDecimal scale normalization
        if (local.isNumber() && remote.isNumber()) {
            BigDecimal localDec = local.decimalValue();
            BigDecimal remoteDec = remote.decimalValue();
            if (localDec.compareTo(remoteDec) != 0) {
                differences.add(path + ": local=" + formatNode(local) + " vs remote=" + formatNode(remote));
            }
            return;
        }

        // Boolean comparison
        if (local.isBoolean() && remote.isBoolean()) {
            if (local.booleanValue() != remote.booleanValue()) {
                differences.add(path + ": local=" + local.booleanValue() + " vs remote=" + remote.booleanValue());
            }
            return;
        }

        // Textual comparison
        if (local.isTextual() && remote.isTextual()) {
            if (!local.textValue().equals(remote.textValue())) {
                differences.add(path + ": local=" + local.textValue() + " vs remote=" + remote.textValue());
            }
            return;
        }

        // Array comparison
        if (local.isArray() && remote.isArray()) {
            if (local.size() != remote.size()) {
                differences.add(path + ": array size mismatch (local=" + local.size() + " vs remote=" + remote.size() + ")");
                return;
            }
            for (int i = 0; i < local.size(); i++) {
                String childPath = path + "[" + i + "]";
                compareNodes(childPath, local.get(i), remote.get(i), differences);
            }
            return;
        }

        // Object comparison
        if (local.isObject() && remote.isObject()) {
            Set<String> allFields = new TreeSet<>();
            local.fieldNames().forEachRemaining(allFields::add);
            remote.fieldNames().forEachRemaining(allFields::add);

            for (String fieldName : allFields) {
                String childPath = "$".equals(path) ? "$." + fieldName : path + "." + fieldName;
                if (!local.has(fieldName)) {
                    differences.add(childPath + ": missing in local, present in remote (" + formatNode(remote.get(fieldName)) + ")");
                } else if (!remote.has(fieldName)) {
                    differences.add(childPath + ": present in local (" + formatNode(local.get(fieldName)) + "), missing in remote");
                } else {
                    compareNodes(childPath, local.get(fieldName), remote.get(fieldName), differences);
                }
            }
            return;
        }

        // Mismatched types or other unsupported node structures
        differences.add(path + ": local=" + formatNode(local) + " vs remote=" + formatNode(remote));
    }

    private String formatNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return node.toString();
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setNodeFactory(new com.fasterxml.jackson.databind.node.JsonNodeFactory(true));
        return mapper;
    }
}
