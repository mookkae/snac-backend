package com.ureca.snac.support.fixture;

/**
 * 이벤트 JSON Payload 테스트 Fixture
 * SignupBonusListenerTest, WalletCreationListenerTest, PaymentCancelCompensationListenerTest
 */
public class EventFixture {

    public static String memberJoinEventJson(Long memberId) {
        return String.format("{\"memberId\":%d}", memberId);
    }

    public static String walletCreatedEventJson(Long memberId, Long walletId) {
        return String.format("{\"memberId\":%d,\"walletId\":%d}", memberId, walletId);
    }

    public static String paymentCancelCompensationEventJson(
            Long paymentId, Long memberId, Long amount, String reason, java.time.OffsetDateTime canceledAt) {
        return String.format(
                "{\"paymentId\":%d,\"memberId\":%d,\"amount\":%d,\"reason\":\"%s\",\"canceledAt\":\"%s\"}",
                paymentId, memberId, amount, reason, canceledAt.toString());
    }

    public static String invalidJson() {
        return "{\"invalid\": }";
    }
}
