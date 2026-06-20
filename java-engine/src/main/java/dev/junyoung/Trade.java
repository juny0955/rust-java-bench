package dev.junyoung;

/**
 * 체결 결과. rust-engine의 {@code Trade} struct와 동일하다.
 *
 * <p>{@code price}는 maker(대기 주문)의 가격이며, {@code sequence}는 엔진 전역 거래 시퀀스다.
 */
public record Trade(
        long makerOrderId,
        long takerOrderId,
        long price,
        long quantity,
        long sequence) {
}
