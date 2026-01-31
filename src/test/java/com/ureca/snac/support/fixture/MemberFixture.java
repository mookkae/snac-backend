package com.ureca.snac.support.fixture;

import com.ureca.snac.member.Activated;
import com.ureca.snac.member.Role;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

// Member 공통 테스트 Fixture
public class MemberFixture {

    public static MemberBuilder builder() {
        return new MemberBuilder();
    }

    public static Member createMember() {
        return createMember(1L);
    }

    public static Member createMember(Long memberId) {
        return builder().id(memberId).build();
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
            if (id != null) {
                this.email = "user" + id + "@snac.com";
                this.nickname = "user" + id;
            }
            return this;
        }

        public MemberBuilder email(String email) {
            this.email = email;
            return this;
        }

        public MemberBuilder nickname(String nickname) {
            this.nickname = nickname;
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
                TestReflectionUtils.setField(member, "id", id);
            }

            return member;
        }
    }
}
