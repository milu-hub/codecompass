package com.codecompass.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.service.AnonymousUser;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.IdentityService;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.UpdateNicknameRequest;

/** F5：GET /api/me 返回当前匿名身份；PUT /api/me 改昵称。 */
@RestController
public class MeController {

    private final IdentityService identityService;

    public MeController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping("/api/me")
    public ResponseEntity<?> me() {
        String clientId = ClientIdentityHolder.get();
        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
        }
        return identityService.find(clientId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("身份不存在")));
    }

    @PutMapping("/api/me")
    public ResponseEntity<?> updateNickname(@RequestBody UpdateNicknameRequest request) {
        String clientId = ClientIdentityHolder.get();
        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
        }
        try {
            AnonymousUser user = identityService.updateNickname(
                    clientId, request == null ? null : request.nickname());
            return ResponseEntity.ok(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
        }
    }
}
