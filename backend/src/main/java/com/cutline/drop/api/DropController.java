package com.cutline.drop.api;

import com.cutline.drop.DropEvent;
import com.cutline.drop.DropService;
import com.cutline.drop.DropService.DropView;
import com.cutline.drop.DropStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DropController {

    private final DropService service;

    public DropController(DropService service) {
        this.service = service;
    }

    @PostMapping("/api/sellers/{sellerId}/drops")
    ResponseEntity<DropResponse> schedule(@PathVariable Long sellerId, @Valid @RequestBody ScheduleDropRequest request) {
        DropEvent drop = service.schedule(sellerId, request.productId(), request.totalQty(),
                request.perMemberLimit(), request.opensAt(), request.holdMinutes());
        return ResponseEntity.created(URI.create("/api/drops/" + drop.getId()))
                .body(DropResponse.of(service.get(drop.getId())));
    }

    @GetMapping("/api/drops/{dropId}")
    DropResponse get(@PathVariable Long dropId) {
        return DropResponse.of(service.get(dropId));
    }

    public record ScheduleDropRequest(
            @NotNull Long productId,
            @Min(1) @Max(1_000_000) int totalQty,
            @Min(1) @Max(100) int perMemberLimit,
            @NotNull Instant opensAt,
            @Min(1) @Max(60) int holdMinutes) {
    }

    public record DropResponse(
            Long id,
            Long productId,
            String productName,
            long price,
            int totalQty,
            int remainingQty,
            int perMemberLimit,
            Instant opensAt,
            int holdMinutes,
            DropStatus status) {

        static DropResponse of(DropView view) {
            DropEvent d = view.drop();
            return new DropResponse(d.getId(), d.getProductId(), view.product().getName(), view.product().getPrice(),
                    d.getTotalQty(), d.getRemainingQty(), d.getPerMemberLimit(), d.getOpensAt(), d.getHoldMinutes(), d.getStatus());
        }
    }
}
