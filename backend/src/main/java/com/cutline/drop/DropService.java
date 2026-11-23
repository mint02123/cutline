package com.cutline.drop;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import com.cutline.seller.Product;
import com.cutline.seller.ProductRepository;
import com.cutline.seller.SellerRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DropService {

    private final DropRepository drops;
    private final ProductRepository products;
    private final SellerRepository sellers;
    private final Clock clock;

    public DropService(DropRepository drops, ProductRepository products, SellerRepository sellers, Clock clock) {
        this.drops = drops;
        this.products = products;
        this.sellers = sellers;
        this.clock = clock;
    }

    @Transactional
    public DropEvent schedule(Long sellerId, Long productId, int totalQty, int perMemberLimit, Instant opensAt, int holdMinutes) {
        if (!sellers.existsById(sellerId)) {
            throw new CutlineException(ErrorCode.SELLER_NOT_FOUND);
        }
        Product product = products.findById(productId)
                .orElseThrow(() -> new CutlineException(ErrorCode.PRODUCT_NOT_FOUND));
        if (!product.isOwnedBy(sellerId)) {
            throw new CutlineException(ErrorCode.PRODUCT_NOT_OWNED);
        }
        Instant now = clock.instant();
        if (!opensAt.isAfter(now)) {
            throw new CutlineException(ErrorCode.INVALID_REQUEST, "오픈 시각은 현재 이후여야 합니다.");
        }
        if (perMemberLimit > totalQty) {
            throw new CutlineException(ErrorCode.INVALID_REQUEST, "1인 구매 한도는 총수량보다 클 수 없습니다.");
        }
        return drops.save(DropEvent.schedule(productId, totalQty, perMemberLimit, opensAt, holdMinutes, now));
    }

    @Transactional(readOnly = true)
    public DropView get(Long dropId) {
        DropEvent drop = drops.findById(dropId)
                .orElseThrow(() -> new CutlineException(ErrorCode.DROP_NOT_FOUND));
        Product product = products.findById(drop.getProductId())
                .orElseThrow(() -> new CutlineException(ErrorCode.PRODUCT_NOT_FOUND));
        return new DropView(drop, product);
    }

    @Transactional
    public int openDueDrops() {
        return drops.openDue(clock.instant());
    }

    public record DropView(DropEvent drop, Product product) {
    }
}
