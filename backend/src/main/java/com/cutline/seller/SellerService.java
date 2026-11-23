package com.cutline.seller;

import com.cutline.common.error.CutlineException;
import com.cutline.common.error.ErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SellerService {

    private final SellerRepository sellers;
    private final ProductRepository products;
    private final Clock clock;

    public SellerService(SellerRepository sellers, ProductRepository products, Clock clock) {
        this.sellers = sellers;
        this.products = products;
        this.clock = clock;
    }

    @Transactional
    public Seller register(String name, int feeRateBps) {
        return sellers.save(new Seller(name, feeRateBps, clock.instant()));
    }

    @Transactional
    public Product registerProduct(Long sellerId, String name, long price) {
        if (!sellers.existsById(sellerId)) {
            throw new CutlineException(ErrorCode.SELLER_NOT_FOUND);
        }
        return products.save(new Product(sellerId, name, price, clock.instant()));
    }
}
