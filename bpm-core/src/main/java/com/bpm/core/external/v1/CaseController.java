package com.bpm.core.external.v1;

import static com.bpm.core.external.v1.CaseError.*;

import com.bpm.core.model.ExternalSystem;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/external/v1")
class CaseController {
    private static final int LIMIT = 1024 * 1024;
    private final ExternalCases cases;
    CaseController(ExternalCases cases) {
        this.cases = cases;
    }

    @PostMapping("/process-instances")
    ResponseEntity<?> start(@RequestAttribute("externalSystem") ExternalSystem sys, HttpServletRequest req)
            throws IOException {
        if (req.getContentLengthLong() > LIMIT)
            throw PAYLOAD_TOO_LARGE.fail();
        byte[] bytes = req.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT)
            throw PAYLOAD_TOO_LARGE.fail();
        CaseCommand command;
        try {
            command =
                    CaseCommand.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            throw VALIDATION_ERROR.fail();
        }
        req.setAttribute("caseRequestId", command.requestId());
        var started = cases.start(sys, command, CaseError.trace(req));
        var receipt = started.row().receipt();
        CaseError error = receipt.errorCode() == null ? null : CaseError.valueOf(receipt.errorCode().substring(4));
        int status = switch (receipt.state()) {
            case "CONFIRMED" -> started.replay() ? 200 : 201;
            case "PROCESSING" -> 202;
            case "REJECTED", "UNCERTAIN" -> error.status;
            default -> throw OPERATION_UNCERTAIN.fail();
        };
        var response = ResponseEntity.status(status);
        if (started.replay() && status != 202)
            response.header("Idempotency-Replayed", "true");
        if (status == 202)
            response.header("Retry-After", "2");
        return response.body(error == null ? receipt : error.envelope(req));
    }
    @GetMapping("/requests/{id}")
    CaseStore.Receipt request(
            @RequestAttribute("externalSystem") ExternalSystem sys, @PathVariable String id, HttpServletRequest req) {
        if (!id.matches(CaseCommand.UUID_PATTERN))
            throw VALIDATION_ERROR.fail();
        req.setAttribute("caseRequestId", id);
        return cases.receipt(sys, id);
    }
    @GetMapping("/process-instances/{id}")
    ExternalCases.Status process(@RequestAttribute("externalSystem") ExternalSystem sys, @PathVariable String id) {
        return cases.byProcess(sys, id);
    }
    @GetMapping("/process-instances")
    Object search(@RequestAttribute("externalSystem") ExternalSystem sys, HttpServletRequest req) {
        String[] keys = req.getParameterValues("businessKey");
        if (req.getParameterMap().size() != 1 || keys == null || keys.length != 1
                || !keys[0].matches(CaseCommand.BUSINESS_KEY))
            throw VALIDATION_ERROR.fail();
        return Map.of("items", cases.byKey(sys, keys[0]));
    }
    @ExceptionHandler(CaseError.Failure.class)
    ResponseEntity<?> failure(CaseError.Failure failure, HttpServletRequest req) {
        return ResponseEntity.status(failure.error.status).body(failure.error.envelope(req));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception failure, HttpServletRequest req) {
        LoggerFactory.getLogger(getClass())
                .error("External case request failed: trace={} failure={}", CaseError.trace(req),
                        failure.getClass().getSimpleName());
        CaseError error = "POST".equals(req.getMethod()) ? OPERATION_UNCERTAIN : INTERNAL_ERROR;
        return ResponseEntity.status(error.status).body(error.envelope(req));
    }
}
