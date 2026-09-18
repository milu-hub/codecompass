package com.codecompass.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.ProgressService;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.ProgressUpdateRequest;

/** F5 阅读进度 REST。 */
@RestController
public class ProgressController {

    private final ProgressService service;

    public ProgressController(ProgressService service) {
        this.service = service;
    }

    @PutMapping("/api/progress")
    public ResponseEntity<?> update(@RequestBody ProgressUpdateRequest request) {
        String clientId = ClientIdentityHolder.get();
        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()
                || request.codeUnitId() == null || request.codeUnitId().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("repoUrl / codeUnitId 不能为空"));
        }
        try {
            return ResponseEntity.ok(service.update(clientId, request.repoUrl(),
                    request.codeUnitId(), request.status()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    @GetMapping("/api/progress")
    public ResponseEntity<?> list(@RequestParam String repoUrl) {
        String clientId = ClientIdentityHolder.get();
        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
        }
        return ResponseEntity.ok(service.list(clientId, repoUrl));
    }
}
