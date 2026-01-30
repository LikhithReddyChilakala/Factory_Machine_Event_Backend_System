package com.antigravity.Factory_Machine_Event_Backend_System.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchIngestResponse {

    private int accepted = 0;
    private int deduped = 0;
    private int updated = 0;
    private int rejected = 0;

    @NotNull
    @JsonProperty("rejections")
    private List<Rejection> rejections = new ArrayList<>();

    public void addRejection(String eventId, String reason) {
        this.rejected++;
        this.rejections.add(new Rejection(eventId, reason));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Rejection {
        @NotBlank
        @JsonProperty("eventId")
        private String eventId;

        @NotBlank
        @JsonProperty("reason")
        private String reason;
    }
}
