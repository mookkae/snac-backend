package com.ureca.snac.support.fixture;

import com.ureca.snac.member.Activated;
import com.ureca.snac.member.Role;
import com.ureca.snac.member.entity.Member;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Member 공통 테스트 Fixture
 * 모든 도메인에서 재사용 가능
 */
public class MemberFixture {

    public static MemberBuilder builder() {
        return new MemberBuilder();
    }

    /**
     * 기본 회원 (ID: 1L)
     */
    public static Member createMember() {
        return createMember(1L);
    }

    /**
     * 특정 ID 회원
     */
    public static Member createMember(Long memberId) {
        return builder()
                .id(memberId)
                .build();
    }

    public static class MemberBuilder {
        private Long id = 1L;
        private String email = "user1@snac.com";
        private String password = "pw1234";
        private String name = "테스트";
        private String nickname = "user1";
        private String phone = "01012345678";
        private LocalDate birthDate = LocalDate.of(2000, 1, 1);
        private Integer ratingScore = 1000;
        private Role role = Role.USER;
        private Activated activated = Activated.NORMAL;

        public MemberBuilder id(Long id) {
            this.id = id;
            this.email = "user" + id + "@snac.com";
            this.nickname = "user" + id;
            return this;
        }

        public Member build() {
            Member member = Member.builder()
                    .email(email)
                    .password(password)
                    .name(name)
                    .nickname(nickname)
                    .nicknameUpdatedAt(LocalDateTime.now())
                    .phone(phone)
                    .birthDate(birthDate)
                    .ratingScore(ratingScore)
                    .role(role)
                    .activated(activated)
                    .build();

            if (id != null) {
                setField(member, "id", id);
            }

            return member;
        }

        private void setField(Object target, String fieldName, Object value) {
            try {
                Field field = getField(target.getClass(), fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException("필드 설정 실패: " + fieldName, e);
            }
        }

        private Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null) {
                    return getField(superClass, fieldName);
                }
                throw e;
            }
        }
    }
}