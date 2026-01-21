package com.ureca.snac.support.fixture;

import com.ureca.snac.member.dto.request.JoinRequest;

import java.time.LocalDate;
import java.util.UUID;

// JoinRequest 테스트 Fixture
public class JoinRequestFixture {

    /**
     * 중복 없는 무조건 성공하는 회원가입 요청 DTO 생성
     * - 이메일, 닉네임, 전화번호 모두 랜덤
     */
    public static JoinRequest create() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);

        return JoinRequest.builder()
                .email("test-" + uniqueId + "@snac.com")
                .password("password123!")
                .name("테스트")
                .nickname("user-" + uniqueId)
                .phone("010" + generatePhoneNumbers())
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();
    }

    // 여러 회원 생성 시 편의 메서드
    public static JoinRequest create(int index) {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);

        return JoinRequest.builder()
                .email("test-" + index + "-" + uniqueId + "@snac.com")
                .password("password123!")
                .name("테스트" + index)
                .nickname("user-" + index + "-" + uniqueId)
                .phone("010" + generatePhoneNumbers())
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();
    }

    // 전화번호 랜덤 생성
    private static String generatePhoneNumbers() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append((int) (Math.random() * 10));
        }
        return sb.toString();
    }
}
