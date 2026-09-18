package com.codecompass.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.persistence.NoteEntity;
import com.codecompass.persistence.NoteRepository;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.Note;
import com.codecompass.web.dto.ErrorResponse;
import com.codecompass.web.dto.NoteRequest;

/** F5 笔记 CRUD：绑定到 (clientId, repoUrl, codeUnitId)，所有权校验。 */
@RestController
public class NoteController {

    private final NoteRepository repository;
    private final Clock clock;

    public NoteController(NoteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping("/api/notes")
    public ResponseEntity<?> list(@RequestParam String repoUrl) {
        String clientId = requireClient();
        if (clientId == null) {
            return unauthorized();
        }
        List<Note> notes = repository.findByClientIdAndRepoUrl(clientId, repoUrl).stream()
                .map(NoteController::toNote).toList();
        return ResponseEntity.ok(notes);
    }

    @PostMapping("/api/notes")
    public ResponseEntity<?> create(@RequestBody NoteRequest request) {
        String clientId = requireClient();
        if (clientId == null) {
            return unauthorized();
        }
        if (request == null || request.repoUrl() == null || request.repoUrl().isBlank()
                || request.codeUnitId() == null || request.codeUnitId().isBlank()
                || request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("repoUrl / codeUnitId / content 不能为空"));
        }
        Instant now = clock.instant();
        NoteEntity note = repository.findByClientIdAndRepoUrlAndCodeUnitId(
                        clientId, request.repoUrl(), request.codeUnitId())
                .map(existing -> {
                    existing.setContent(request.content());
                    existing.setUpdatedAt(now);
                    return existing;
                })
                .orElseGet(() -> new NoteEntity(clientId, request.repoUrl(), request.codeUnitId(),
                        request.content(), now, now));
        repository.save(note);
        return ResponseEntity.ok(toNote(note));
    }

    @PutMapping("/api/notes/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody NoteRequest request) {
        String clientId = requireClient();
        if (clientId == null) {
            return unauthorized();
        }
        NoteEntity note = repository.findById(id).orElse(null);
        if (note == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("笔记不存在"));
        }
        if (!note.getClientId().equals(clientId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("笔记不存在"));
        }
        note.setContent(request == null || request.content() == null ? "" : request.content());
        note.setUpdatedAt(clock.instant());
        repository.save(note);
        return ResponseEntity.ok(toNote(note));
    }

    @DeleteMapping("/api/notes/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        String clientId = requireClient();
        if (clientId == null) {
            return unauthorized();
        }
        NoteEntity note = repository.findById(id).orElse(null);
        if (note == null || !note.getClientId().equals(clientId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("笔记不存在"));
        }
        repository.delete(note);
        return ResponseEntity.noContent().build();
    }

    private static String requireClient() {
        return ClientIdentityHolder.get();
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("缺少匿名身份"));
    }

    private static Note toNote(NoteEntity entity) {
        return new Note(entity.getId(), entity.getClientId(), entity.getRepoUrl(),
                entity.getCodeUnitId(), entity.getContent(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
