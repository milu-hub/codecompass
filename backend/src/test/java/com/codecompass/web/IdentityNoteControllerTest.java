package com.codecompass.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.codecompass.persistence.AnonymousUserEntity;
import com.codecompass.persistence.AnonymousUserRepository;
import com.codecompass.persistence.NoteEntity;
import com.codecompass.persistence.NoteRepository;
import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.IdentityService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/me 与 /api/notes 契约（standalone + ClientIdentityHolder 注入身份）。
 */
class IdentityNoteControllerTest {

    private IdentityService identityService;
    private NoteRepository noteRepository;
    private MockMvc mockMvc;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        AnonymousUserRepository userRepository = Mockito.mock(AnonymousUserRepository.class);
        when(userRepository.findById("client-1")).thenReturn(Optional.of(
                new AnonymousUserEntity("client-1", "小明",
                        Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:00:00Z"))));
        identityService = new IdentityService(userRepository, clock);
        noteRepository = Mockito.mock(NoteRepository.class);
        ClientIdentityHolder.set("client-1");
        mockMvc = MockMvcBuilders.standaloneSetup(
                new MeController(identityService),
                new NoteController(noteRepository, clock)).build();
    }

    @AfterEach
    void tearDown() {
        ClientIdentityHolder.clear();
    }

    @Test
    @DisplayName("GET /api/me 返回 clientId + 昵称")
    void meReturnsIdentity() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.nickname").value("小明"));
    }

    @Test
    @DisplayName("POST /api/notes 新建笔记，绑定 clientId")
    void createNote() throws Exception {
        when(noteRepository.findByClientIdAndRepoUrlAndCodeUnitId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/notes")
                        .contentType("application/json")
                        .content("{\"repoUrl\":\"https://github.com/a/b\",\"codeUnitId\":\"u1\",\"content\":\"这是笔记\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.content").value("这是笔记"));

        Mockito.verify(noteRepository).save(Mockito.any(NoteEntity.class));
    }

    @Test
    @DisplayName("删除他人笔记 → 404（所有权校验，跨用户不可见）")
    void deleteOthersNoteReturns404() throws Exception {
        NoteEntity other = new NoteEntity("client-2", "r", "u1", "x",
                Instant.now(), Instant.now());
        when(noteRepository.findById(99L)).thenReturn(Optional.of(other));

        mockMvc.perform(delete("/api/notes/99"))
                .andExpect(status().isNotFound());
    }
}
