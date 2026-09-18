package com.codecompass.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.service.AchievementService;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.web.dto.ErrorResponse;

/** F5 成就 REST：返回全部定义 + 解锁状态。 */
@RestController
public class AchievementController {

    private final AchievementService service;

    public AchievementController(AchievementService service) {
        this.service = service;
    }

    @GetMapping("/api/achievements")
    public ResponseEntity<?> list() {
        String clientId = ClientIdentityHolder.get();
        if (clientId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
        }
        return ResponseEntity.ok(service.unlocked(clientId));
    }
}
