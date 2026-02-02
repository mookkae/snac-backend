package com.ureca.snac.support.fixture;

/**
 * 이벤트 JSON Payload 테스트 Fixture
 * SignupBonusListenerTest, WalletCreationListenerTest
 */
public class EventFixture {

    public static String memberJoinEventJson(Long memberId) {
        return String.format("{\"memberId\":%d}", memberId);
    }

    public static String walletCreatedEventJson(Long memberId, Long walletId) {
        return String.format("{\"memberId\":%d,\"walletId\":%d}", memberId, walletId);
    }

    public static String invalidJson() {
        return "{\"invalid\": }";
    }
}
