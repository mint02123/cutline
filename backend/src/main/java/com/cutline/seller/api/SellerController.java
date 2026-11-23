package com.cutline.seller.api;

import com.cutline.seller.Product;
import com.cutline.seller.Seller;
import com.cutline.seller.SellerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자·상품 등록. 1단계에는 판매자 인증이 없어 경로의 sellerId를 그대로 믿는다 (인증은 이후 단계).
 */
@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerService service;

    public SellerController(SellerService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<SellerResponse> register(@Valid @RequestBody RegisterSellerRequest request) {
        Seller seller = service.register(request.name(), request.feeRateBps());
        return ResponseEntity.created(URI.create("/api/sellers/" + seller.getId()))
                .body(new SellerResponse(seller.getId(), seller.getName(), seller.getFeeRateBps()));
    }

    @PostMapping("/{sellerId}/products")
    ResponseEntity<ProductResponse> registerProduct(@PathVariable Long sellerId,
                                                    @Valid @RequestBody RegisterProductRequest request) {
        Product product = service.registerProduct(sellerId, request.name(), request.price());
        return ResponseEntity.created(URI.create("/api/products/" + product.getId()))
                .body(new ProductResponse(product.getId(), product.getSellerId(), product.getName(), product.getPrice()));
    }

    public record RegisterSellerRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(0) @Max(10_000) int feeRateBps) {
    }

    public record RegisterProductRequest(
            @NotBlank @Size(max = 200) String name,
            @Positive long price) {
    }

    public record SellerResponse(Long id, String name, int feeRateBps) {
    }

    public record ProductResponse(Long id, Long sellerId, String name, long price) {
    }
}
