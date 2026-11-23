package com.cutline.drop;

public enum DropStatus {
    /** 오픈 시각 전. 주문 불가. */
    SCHEDULED,
    /** 주문 가능. */
    OPEN,
    /** 판매자가 닫았거나 판매 기간이 끝남. */
    CLOSED
}
