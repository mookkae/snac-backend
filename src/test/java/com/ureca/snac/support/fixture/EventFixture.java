package com.ureca.snac.support.fixture;

/**
 * 이벤트 JSON Payload 테스트 Fixture
 * <p>
 * 모든 도메인에서 재사용 가능
 * Jackson 의존성 제거 (직접 JSON 작성)
 */
public class EventFixture {

    /**
     * 회원가입 이벤트 JSON
     */
    public static String memberJoinEventJson(Long memberId) {
        return String.format(
                "{\"memberId\":%d}",
                memberId
        );
    }

    /**
     * 지갑 생성 이벤트 JSON
     */
    public static String walletCreatedEventJson(Long memberId, Long walletId) {
        return String.format(
                "{\"memberId\":%d,\"walletId\":%d}",
                memberId, walletId
        );
    }

    /**
     * 잘못된 JSON (파싱 실패 테스트용)
     */
    public static String invalidJson() {
        return "{\"invalid\": }";
    }

    /**
     * 빈 JSON
     */
    public static String emptyJson() {
        return "{}";
    }

    /**
     * 필드 누락 JSON (memberId 없음)
     */
    public static String missingMemberIdJson() {
        return "{\"walletId\":1}";
    }

    /**
     * 필드 누락 JSON (walletId 없음)
     */
    public static String missingWalletIdJson() {
        return "{\"memberId\":1}";
    }
}