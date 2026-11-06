package com.bpm.core.external.v1;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CaseCommandTest {
    private static final String BODY = """
            {"requestId":"10000000-0000-4000-8000-000000000001","workflowCode":"a","bindingVersion":1,
             "caseRef":{"domain":"a","applicationId":"B","submissionId":"C","workflowRole":"d","submissionRevision":1},
             "actor":{"type":"AGENT","agentCode":"AG1","issuer":"https://agent.test","subject":"shared"},
             "processDefinitionId":"p:1:id","formData":{"formVersion":1,"formDefinitionId":"20000000-0000-4000-8000-000000000001",
             "dataJson":"{\\"a\\":1,\\"b\\":[1,2]}"},"variables":{"amount":123456789.01234567890123456789}}
            """;

    @Test
    void hashNormalizesNestedObjectsAndExactDecimalsButPreservesArrayAndIdentity() {
        var baseline = CaseCommand.parse(BODY).hash();
        assertThat(CaseCommand
                           .parse(BODY.replace("\\\"a\\\":1,\\\"b\\\":[1,2]", "\\\"b\\\":[1.00,2e0],\\\"a\\\":1.0")
                                           .replace("6789}}", "678900}}"))
                           .hash())
                .isEqualTo(baseline);
        for (String change : new String[] {BODY.replace("[1,2]", "[2,1]"), BODY.replace("AG1", "AG2"),
                     BODY.replace("6789}}", "6788}}"), BODY.replace("shared", "other")})
            assertThat(CaseCommand.parse(change).hash()).isNotEqualTo(baseline);
    }
    @Test
    void rejectsAmbiguousJsonAndControlFieldsBeforeAcceptance() {
        for (String malformed : new String[] {BODY + "{}", BODY.replace("\"amount\":", "\"amount\":1,\"amount\":"),
                     BODY.replace("\\\"b\\\"", "\\\"a\\\""), BODY.replace("\"amount\"", "\"onBehalfOf\""),
                     BODY.replace("\\\"a\\\"", "\\\"approved\\\""),
                     BODY.replace("123456789.01234567890123456789", "NaN"),
                     BODY.replace("\"agentCode\"", "\"account\""), BODY.replace("\"AGENT\"", "\"TALENT\""),
                     BODY.replace("\"formVersion\":1", "\"formVersion\":1.5"),
                     BODY.replace("\"caseRef\":", "\"callbackUrl\":1,\"caseRef\":")})
            assertThatThrownBy(() -> CaseCommand.parse(malformed)).isInstanceOf(CaseError.Failure.class);
    }
}
