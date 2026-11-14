package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.knowledge.RoadmapCreateRequest;
import com.alumni.alumni_connect.dto.knowledge.RoadmapItemUpdateRequest;
import com.alumni.alumni_connect.dto.knowledge.RoadmapResponse;
import com.alumni.alumni_connect.service.KnowledgeRoadmapService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/roadmaps")
@PreAuthorize("hasRole('STUDENT')")
public class KnowledgeRoadmapController {
    private final KnowledgeRoadmapService roadmaps;
    public KnowledgeRoadmapController(KnowledgeRoadmapService roadmaps) { this.roadmaps = roadmaps; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public RoadmapResponse create(@Valid @RequestBody RoadmapCreateRequest request) { return roadmaps.create(request); }
    @GetMapping public List<RoadmapResponse> list() { return roadmaps.list(); }
    @GetMapping("/{id}") public RoadmapResponse get(@PathVariable long id) { return roadmaps.get(id); }
    @PutMapping("/{roadmapId}/items/{itemId}")
    public RoadmapResponse updateItem(@PathVariable long roadmapId, @PathVariable long itemId,
            @Valid @RequestBody RoadmapItemUpdateRequest request) {
        return roadmaps.updateItem(roadmapId, itemId, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { roadmaps.delete(id); }
}
